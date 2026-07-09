package com.instalego.controller;

import com.instalego.dto.JobStatusResponse;
import com.instalego.model.Bank;
import com.instalego.model.ConversionJob;
import com.instalego.service.BankService;
import com.instalego.service.ConversionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final ConversionService conversionService;
    private final BankService bankService;

    /**
     * Create a conversion job. Upload a file and select a bank.
     */
    @PostMapping
    public ResponseEntity<?> createJob(
            @RequestParam("bankId") Long bankId,
            @RequestParam("file") MultipartFile file) {
        try {
            ConversionJob job = conversionService.createJob(bankId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", job.getId(),
                    "status", job.getStatus().name(),
                    "createdAt", job.getCreatedAt().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create job: " + e.getMessage()));
        }
    }

    /**
     * Poll job status.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getJobStatus(@PathVariable Long id) {
        try {
            ConversionJob job = conversionService.getJob(id);
            String bankName = "";
            try {
                Bank bank = bankService.getBankById(job.getBankId());
                bankName = bank.getName();
            } catch (Exception ignored) {}

            JobStatusResponse response = JobStatusResponse.builder()
                    .id(job.getId())
                    .bankId(job.getBankId())
                    .bankName(bankName)
                    .status(job.getStatus().name())
                    .extractedJson(job.getExtractedJson())
                    .errorMessage(job.getErrorMessage())
                    .outputAvailable(job.getOutputFilePath() != null)
                    .createdAt(job.getCreatedAt() != null ? job.getCreatedAt().toString() : null)
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Download the output PDF for a completed job.
     */
    @GetMapping("/{id}/output")
    public ResponseEntity<Resource> downloadOutput(@PathVariable Long id) {
        try {
            ConversionJob job = conversionService.getJob(id);

            if (job.getStatus() != ConversionJob.Status.DONE) {
                return ResponseEntity.badRequest().build();
            }

            Path outputPath = conversionService.getOutputPath(id);
            Resource resource = new FileSystemResource(outputPath.toFile());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"output_" + id + ".pdf\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
