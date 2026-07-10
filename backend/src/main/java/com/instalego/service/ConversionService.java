package com.instalego.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.BankTemplate;
import com.instalego.model.ConversionJob;
import com.instalego.repository.ConversionJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private final GroqClient groqClient;
    private final VerificationService verificationService;
    private final PdfGenerationService pdfGenerationService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private ConversionService self;

    private static final long MAX_FILE_SIZE_BYTES = 15L * 1024 * 1024; // 15 MB

    // Shared Tika instance to avoid per-call parser loading overhead
    private static final Tika TIKA = new Tika();

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * Create a conversion job and return it immediately.
     */

    @Transactional
    public ConversionJob createJob(Long bankId, MultipartFile file) throws IOException {
        // Validate file size early — before any processing
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File too large: " + (file.getSize() / (1024 * 1024)) + " MB. Maximum allowed: 15 MB.");
        }

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

            String fieldSchema = template.getFieldSchema();
            if (fieldSchema == null || fieldSchema.isBlank() || "[]".equals(fieldSchema)) {
                throw new IllegalStateException("Bank template has no field schema configured");
            }

            // Phase 1: Attempt text extraction using PDFBox/Tika (code-first approach)
            String extractedText = extractTextFromFile(Path.of(job.getSourceFilePath()), job.getSourceFileType());

            Map<String, Object> extractionResult;
            String provider;

            if (extractedText != null && !extractedText.isBlank()) {
                // Text-native document → send extracted text to Groq
                provider = "Groq";
                log.info("Job {}: text extraction yielded {} chars, routing to {}", jobId, extractedText.length(), provider);
                extractionResult = groqClient.extractFieldsFromText(extractedText, fieldSchema);
                // Clear extracted text from memory now that it's been sent to Groq
                extractedText = null;
            } else {
                // Scanned/image-only pages → send page images to Gemini vision
                provider = "Gemini";
                log.info("Job {}: no extractable text, routing to Gemini vision fallback", jobId);
                byte[] fileBytes = Files.readAllBytes(Path.of(job.getSourceFilePath()));
                String base64Data = Base64.getEncoder().encodeToString(fileBytes);
                // Clear raw bytes immediately — Base64 string is all we need
                fileBytes = null;
                String mimeType = getMimeTypeForFileType(job.getSourceFileType());
                extractionResult = geminiService.extractFields(base64Data, mimeType, fieldSchema);
                // Clear Base64 data from memory now that Gemini has processed it
                base64Data = null;
            }

            log.info("Job {} served by provider: {} using model: {}", jobId, provider,
                    "Groq".equals(provider) ? groqClient.getModel() : geminiService.getModel());

            String extractedJson = objectMapper.writeValueAsString(extractionResult);
            job.setExtractedJson(extractedJson);

            // Generate output PDF
            log.info("Generating output PDF for job {} from template {}", jobId, template.getTemplatePdfPath());
            Path outputDir = Path.of(uploadDir, "outputs");
            Path outputPath = pdfGenerationService.generateOutputPdf(
                    template.getTemplatePdfPath(), extractedJson, outputDir);

            job.setOutputFilePath(outputPath.toString());

            // Run legal verification against bank's reference documents
            log.info("Running legal verification for job {} against bank {} policies", jobId, job.getBankId());
            try {
                Map<String, Object> verificationResult = verificationService.verifyDocument(extractedJson, job.getBankId());
                String verificationReportJson = objectMapper.writeValueAsString(verificationResult);
                job.setVerificationReport(verificationReportJson);
                log.info("Legal verification complete for job {}: {}", jobId,
                        verificationResult.getOrDefault("summaryVerdict", "No verdict"));
            } catch (Exception e) {
                log.warn("Legal verification failed for job {}, continuing without it: {}", jobId, e.getMessage());
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("summaryVerdict", "Verification could not be completed due to an error.");
                fallback.put("detailedFindings", List.of());
                fallback.put("confidence", "Low");
                job.setVerificationReport(objectMapper.writeValueAsString(fallback));
            }

            job.setStatus(ConversionJob.Status.DONE);
            jobRepository.save(job);

            log.info("Job {} completed successfully. Output: {}", jobId, outputPath);

            // Clean up source file after successful processing to free disk space
            try {
                Files.deleteIfExists(Path.of(job.getSourceFilePath()));
            } catch (IOException e) {
                log.warn("Could not delete source file for job {}: {}", jobId, e.getMessage());
            }

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

    /**
     * Extract plain text from a source file using PDFBox (for PDFs) or Apache Tika (for DOCX/images).
     * Returns null if text extraction fails or yields empty text (e.g., scanned pages).
     */
    private String extractTextFromFile(Path filePath, String fileType) {
        try {
            return switch (fileType.toUpperCase()) {
                case "PDF" -> extractTextFromPdf(filePath);
                case "DOCX" -> extractTextWithTika(filePath);
                case "IMAGE" -> null; // Images have no native text — always fall back to vision
                default -> extractTextWithTika(filePath);
            };
        } catch (Exception e) {
            log.warn("Text extraction failed for {} ({}): {}", filePath, fileType, e.getMessage());
            return null;
        }
    }

    /**
     * Extract text from a PDF using PDFBox's built-in stripper.
     */
    private String extractTextFromPdf(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text != null) {
                text = text.trim();
            }
            // If the extracted text is very short relative to file size, it's likely a scanned PDF
            long fileSize = Files.size(pdfPath);
            if (text == null || text.isEmpty() || (fileSize > 1024 && text.length() < 50)) {
                log.debug("PDF appears to be scanned or image-only ({} bytes, {} chars extracted)", fileSize,
                        text != null ? text.length() : 0);
                return null;
            }
            return text;
        }
    }

    /**
     * Extract text from non-PDF files (DOCX, etc.) using Apache Tika.
     */
    private String extractTextWithTika(Path filePath) throws IOException {
        try (InputStream is = new FileInputStream(filePath.toFile())) {
            try {
                String text = TIKA.parseToString(is);
                return text != null ? text.trim() : null;
            } catch (org.apache.tika.exception.TikaException e) {
                throw new IOException("Tika text extraction failed", e);
            }
        }
    }
}
