package com.instalego.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.BankTemplate;
import com.instalego.model.ConversionJob;
import com.instalego.repository.ConversionJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversionService {

    private final ConversionJobRepository jobRepository;
    private final TemplateService templateService;
    private final GeminiService geminiService;
    private final PdfGenerationService pdfGenerationService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private ConversionService self;

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * Create a conversion job and return it immediately.
     */
    @Transactional
    public ConversionJob createJob(Long bankId, MultipartFile file) throws IOException {
        // Validate file type
        String contentType = file.getContentType();
        String sourceFileType = determineFileType(contentType, file.getOriginalFilename());

        // Validate bank has an active template
        Optional<BankTemplate> template = templateService.getActiveTemplate(bankId);
        if (template.isEmpty()) {
            throw new IllegalArgumentException("Bank has no active template. Please configure a template first.");
        }

        // Save uploaded file
        String fileName = "source_" + bankId + "_" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path uploadPath = Path.of(uploadDir, "sources");
        Files.createDirectories(uploadPath);
        Path filePath = uploadPath.resolve(fileName);
        file.transferTo(filePath.toFile());

        // Create job record
        ConversionJob job = new ConversionJob();
        job.setBankId(bankId);
        job.setSourceFilePath(filePath.toString());
        job.setSourceFileType(sourceFileType);
        job.setStatus(ConversionJob.Status.PENDING);
        ConversionJob savedJob = jobRepository.save(job);

        log.info("Created conversion job: id={}, bankId={}, fileType={}", savedJob.getId(), bankId, sourceFileType);

        // Kick off async processing (use self-injection to trigger @Async proxy)
        self.processJobAsync(savedJob.getId());

        return savedJob;
    }

    /**
     * Async processing of a conversion job.
     */
    @Async
    @Transactional
    public void processJobAsync(Long jobId) {
        Optional<ConversionJob> optJob = jobRepository.findById(jobId);
        if (optJob.isEmpty()) {
            log.error("Job {} not found for processing", jobId);
            return;
        }

        ConversionJob job = optJob.get();
        try {
            job.setStatus(ConversionJob.Status.PROCESSING);
            jobRepository.save(job);

            // Get the active template for the bank
            BankTemplate template = templateService.getActiveTemplate(job.getBankId())
                    .orElseThrow(() -> new IllegalStateException("No active template found for bank " + job.getBankId()));

            // Read the source file and encode to base64
            byte[] fileBytes = Files.readAllBytes(Path.of(job.getSourceFilePath()));
            String base64Data = Base64.getEncoder().encodeToString(fileBytes);
            String mimeType = getMimeTypeForFileType(job.getSourceFileType());

            log.info("Processing job {}: calling Gemini for extraction (bankId={})", jobId, job.getBankId());

            // Call Gemini to extract fields
            String fieldSchema = template.getFieldSchema();
            if (fieldSchema == null || fieldSchema.isBlank() || "[]".equals(fieldSchema)) {
                throw new IllegalStateException("Bank template has no field schema configured");
            }

            Map<String, Object> extractionResult = geminiService.extractFields(base64Data, mimeType, fieldSchema);

            String extractedJson = objectMapper.writeValueAsString(extractionResult);
            job.setExtractedJson(extractedJson);

            // Generate output PDF
            log.info("Generating output PDF for job {} from template {}", jobId, template.getTemplatePdfPath());
            Path outputDir = Path.of(uploadDir, "outputs");
            Path outputPath = pdfGenerationService.generateOutputPdf(
                    template.getTemplatePdfPath(), extractedJson, outputDir);

            job.setOutputFilePath(outputPath.toString());
            job.setStatus(ConversionJob.Status.DONE);
            jobRepository.save(job);

            log.info("Job {} completed successfully. Output: {}", jobId, outputPath);

        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            job.setStatus(ConversionJob.Status.FAILED);
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error occurred");
            jobRepository.save(job);
        }
    }

    public ConversionJob getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found with id: " + jobId));
    }

    public Path getOutputPath(Long jobId) {
        ConversionJob job = getJob(jobId);
        if (job.getOutputFilePath() == null) {
            throw new IllegalStateException("Output file not available for job " + jobId);
        }
        Path path = Path.of(job.getOutputFilePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("Output file not found on disk for job " + jobId);
        }
        return path;
    }

    private String determineFileType(String contentType, String filename) {
        if (contentType != null) {
            if (contentType.equals("application/pdf")) return "PDF";
            if (contentType.startsWith("image/")) return "IMAGE";
            if (contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                return "DOCX";
        }
        if (filename != null) {
            String ext = filename.toLowerCase();
            if (ext.endsWith(".pdf")) return "PDF";
            if (ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".png")) return "IMAGE";
            if (ext.endsWith(".docx")) return "DOCX";
        }
        throw new IllegalArgumentException("Unsupported file type. Accepted: PDF, JPG, PNG, DOCX");
    }

    private String getMimeTypeForFileType(String fileType) {
        return switch (fileType.toUpperCase()) {
            case "PDF" -> "application/pdf";
            case "IMAGE" -> "image/png";
            case "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
    }
}
