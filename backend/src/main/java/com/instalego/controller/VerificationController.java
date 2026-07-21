package com.instalego.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.VerificationJob;
import com.instalego.repository.VerificationJobRepository;
import com.instalego.security.AuthenticatedUser;
import com.instalego.service.BankService;
import com.instalego.service.LegalOpinionPdfService;
import com.instalego.service.VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
@Slf4j
public class VerificationController {

    // FAILED is included so a user can retry (optionally after adding/replacing a document)
    // without losing the documents they'd already uploaded to the session.
    private static final Set<VerificationJob.Status> UPLOADABLE_STATUSES = Set.of(
            VerificationJob.Status.PENDING, VerificationJob.Status.NEEDS_MORE_DOCUMENTS, VerificationJob.Status.FAILED);

    private final VerificationJobRepository jobRepository;
    private final VerificationService verificationService;
    private final BankService bankService;
    private final LegalOpinionPdfService legalOpinionPdfService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private VerificationController self;

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * Step 1: Create a new verification session for a bank.
     */
    @PostMapping("/start")
    public ResponseEntity<?> startSession(@RequestParam("bankId") Long bankId, Authentication authentication) {
        try {
            AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();

            VerificationJob job = new VerificationJob();
            job.setBankId(bankId);
            job.setUserId(user.id());
            job.setStatus(VerificationJob.Status.PENDING);
            job.setDocumentsJson("[]");
            job.setCurrentPhase("Ready to upload documents");
            VerificationJob saved = jobRepository.save(job);

            log.info("Started verification session {} for bank {} (user {})", saved.getId(), bankId, user.email());

            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "bankId", saved.getBankId(),
                    "status", saved.getStatus().name(),
                    "currentPhase", saved.getCurrentPhase(),
                    "createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start session: " + e.getMessage()));
        }
    }

    /**
     * Step 2: Add a document to a session. Works both for the initial upload batch (status
     * PENDING) and for supplying a previously-missing referenced document after a run flagged
     * it (status NEEDS_MORE_DOCUMENTS).
     */
    @PostMapping("/{sessionId}/add-document")
    public ResponseEntity<?> addDocument(
            @PathVariable Long sessionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            Authentication authentication) {
        try {
            Optional<VerificationJob> optJob = jobRepository.findById(sessionId);
            if (optJob.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            VerificationJob job = optJob.get();
            if (!canAccess(job, authentication)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your session"));
            }
            if (!UPLOADABLE_STATUSES.contains(job.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Cannot add documents to a session that is already " + job.getStatus().name()));
            }
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "The uploaded file is empty (0 bytes) — this can happen from a browser upload glitch. Please try selecting the file again."));
            }

            // Determine file type
            String contentType = file.getContentType();
            String originalName = file.getOriginalFilename();
            String fileType = determineFileType(contentType, originalName);

            // Save file to disk
            String fileName = "verify_" + sessionId + "_" + System.currentTimeMillis() + "_" +
                    (originalName != null ? originalName : "unnamed");
            Path uploadPath = Path.of(uploadDir, "verify", String.valueOf(sessionId));
            Files.createDirectories(uploadPath);
            Path filePath = uploadPath.resolve(fileName);
            // MultipartFile.transferTo() resolves a *relative* File against the servlet
            // container's internal temp dir, not the app's working directory — always pass an
            // absolute path or uploads silently fail to find the (correctly-created) directory.
            file.transferTo(filePath.toAbsolutePath().toFile());

            // Parse existing documents and add new one
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = objectMapper.readValue(job.getDocumentsJson(), List.class);

            Map<String, Object> docEntry = new HashMap<>();
            docEntry.put("fileName", originalName != null ? originalName : "unnamed");
            docEntry.put("filePath", filePath.toString());
            docEntry.put("fileType", fileType);
            docEntry.put("orderIndex", documents.size());
            docEntry.put("label", label != null && !label.isBlank() ? label : originalName);
            documents.add(docEntry);

            job.setDocumentsJson(objectMapper.writeValueAsString(documents));
            job.setCurrentPhase(documents.size() + " document(s) uploaded");
            jobRepository.save(job);

            log.info("Added document {} to verification session {} (total: {})", originalName, sessionId, documents.size());

            return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "documentIndex", documents.size() - 1,
                    "fileName", originalName,
                    "totalDocuments", documents.size(),
                    "status", "ADDED"
            ));
        } catch (Exception e) {
            log.error("Failed to add document to session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to add document: " + e.getMessage()));
        }
    }

    /**
     * Step 3: Trigger (or re-trigger) verification. Allowed from PENDING (first run) or from
     * NEEDS_MORE_DOCUMENTS (re-run after the user supplied a previously-missing document) — each
     * run re-analyzes the full current set of uploaded documents from scratch.
     */
    @PostMapping("/{sessionId}/run")
    public ResponseEntity<?> runVerification(@PathVariable Long sessionId, Authentication authentication) {
        try {
            Optional<VerificationJob> optJob = jobRepository.findById(sessionId);
            if (optJob.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            VerificationJob job = optJob.get();
            if (!canAccess(job, authentication)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your session"));
            }
            if (!UPLOADABLE_STATUSES.contains(job.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Session is already " + job.getStatus().name()));
            }

            // Check that at least one document was uploaded
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = objectMapper.readValue(job.getDocumentsJson(), List.class);
            if (documents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Please upload at least one document before running verification"));
            }

            // Start async verification through self-proxy so @Async goes through Spring's proxy
            final Long jobId = sessionId;
            self.runAsyncVerification(jobId);

            return ResponseEntity.ok(Map.of(
                    "id", sessionId,
                    "status", "VERIFYING",
                    "message", "Verification started"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start verification: " + e.getMessage()));
        }
    }

    /**
     * Poll: Get verification status, thinking steps, missing documents, and (when complete) the
     * final report.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getStatus(@PathVariable Long sessionId, Authentication authentication) {
        try {
            Optional<VerificationJob> optJob = jobRepository.findById(sessionId);
            if (optJob.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            VerificationJob job = optJob.get();
            if (!canAccess(job, authentication)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your session"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", job.getId());
            response.put("bankId", job.getBankId());
            response.put("status", job.getStatus().name());
            response.put("currentPhase", job.getCurrentPhase());
            response.put("errorMessage", job.getErrorMessage());
            response.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);

            // Parse and return thinking steps
            if (job.getThinkingSteps() != null && !job.getThinkingSteps().isBlank()) {
                try {
                    List<?> steps = objectMapper.readValue(job.getThinkingSteps(), List.class);
                    response.put("thinkingSteps", steps);
                } catch (Exception e) {
                    response.put("thinkingSteps", List.of());
                }
            } else {
                response.put("thinkingSteps", List.of());
            }

            // Return the report once we have one (either final DONE, or the partial analysis
            // that accompanies a NEEDS_MORE_DOCUMENTS result)
            if (job.getReportJson() != null && !job.getReportJson().isBlank()
                    && (job.getStatus() == VerificationJob.Status.DONE
                        || job.getStatus() == VerificationJob.Status.NEEDS_MORE_DOCUMENTS)) {
                try {
                    Object report = objectMapper.readValue(job.getReportJson(), Object.class);
                    response.put("report", report);
                } catch (Exception e) {
                    response.put("report", null);
                }
            }

            // Missing documents flagged by the last run (empty once verification passes DONE)
            if (job.getMissingDocumentsJson() != null && !job.getMissingDocumentsJson().isBlank()) {
                try {
                    List<?> missing = objectMapper.readValue(job.getMissingDocumentsJson(), List.class);
                    response.put("missingDocuments", missing);
                } catch (Exception e) {
                    response.put("missingDocuments", List.of());
                }
            } else {
                response.put("missingDocuments", List.of());
            }

            // Document info
            if (job.getDocumentsJson() != null) {
                try {
                    List<?> docs = objectMapper.readValue(job.getDocumentsJson(), List.class);
                    response.put("documents", docs);
                    response.put("totalDocuments", docs.size());
                } catch (Exception e) {
                    response.put("documents", List.of());
                    response.put("totalDocuments", 0);
                }
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Download the completed verification as a formatted "Legal Opinion" PDF.
     */
    @GetMapping("/{sessionId}/opinion.pdf")
    public ResponseEntity<?> downloadOpinionPdf(@PathVariable Long sessionId, Authentication authentication) {
        try {
            Optional<VerificationJob> optJob = jobRepository.findById(sessionId);
            if (optJob.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            VerificationJob job = optJob.get();
            if (!canAccess(job, authentication)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your session"));
            }
            if (job.getReportJson() == null || job.getReportJson().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No report available yet for this session"));
            }

            String bankName = null;
            try {
                bankName = bankService.getBankById(job.getBankId()).getName();
            } catch (Exception ignored) {}

            byte[] pdf = legalOpinionPdfService.generateOpinionPdf(job.getReportJson(), bankName, sessionId);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"legal-opinion-" + sessionId + ".pdf\"")
                    .body(pdf);
        } catch (Exception e) {
            log.error("Failed to generate opinion PDF for session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to generate PDF: " + e.getMessage()));
        }
    }

    /**
     * List the current user's past and in-progress verification sessions (most recent first) —
     * a simple submission history now that sessions are tied to a logged-in account.
     */
    @GetMapping("/mine")
    public ResponseEntity<?> getMine(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        List<VerificationJob> jobs = jobRepository.findByUserIdOrderByCreatedAtDesc(user.id());

        List<Map<String, Object>> response = jobs.stream().map(job -> {
            String bankName = "";
            try {
                bankName = bankService.getBankById(job.getBankId()).getName();
            } catch (Exception ignored) {}

            String verdict = null;
            if (job.getReportJson() != null && !job.getReportJson().isBlank()) {
                try {
                    Map<?, ?> report = objectMapper.readValue(job.getReportJson(), Map.class);
                    Object v = report.get("verdict");
                    verdict = v != null ? v.toString() : null;
                } catch (Exception ignored) {}
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("id", job.getId());
            entry.put("bankId", job.getBankId());
            entry.put("bankName", bankName);
            entry.put("status", job.getStatus().name());
            entry.put("verdict", verdict);
            entry.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
            return entry;
        }).toList();

        return ResponseEntity.ok(response);
    }

    /**
     * A session belongs to whoever started it; ADMINs can also view any session. Sessions created
     * before auth existed have a null userId — treated as accessible to avoid locking out
     * pre-migration local dev data.
     */
    private boolean canAccess(VerificationJob job, Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        if (job.getUserId() == null) return true;
        return job.getUserId().equals(user.id()) || "ADMIN".equals(user.role());
    }

    /**
     * Async verification. Deliberately NOT @Transactional: this runs for minutes and calls
     * jobRepository.save(job) repeatedly to report progress (phase/thinking steps). Each save()
     * already commits in its own transaction via Spring Data's SimpleJpaRepository — wrapping the
     * whole method in one outer transaction would hold all those writes uncommitted (invisible to
     * the polling GET /api/verify/{id} requests, which run in their own transactions) until the
     * entire run finished, making the UI appear frozen until everything showed up at once.
     */
    @Async
    public void runAsyncVerification(Long jobId) {
        try {
            verificationService.runVerification(jobId);
        } catch (Exception e) {
            log.error("Verification job {} failed", jobId, e);
        }
    }

    private String determineFileType(String contentType, String filename) {
        if (contentType != null) {
            if (contentType.equals("application/pdf")) return "PDF";
            if (contentType.startsWith("text/")) return "TXT";
            if (contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                return "DOCX";
            if (contentType.startsWith("image/")) return "IMAGE";
        }
        if (filename != null) {
            String ext = filename.toLowerCase();
            if (ext.endsWith(".pdf")) return "PDF";
            if (ext.endsWith(".txt")) return "TXT";
            if (ext.endsWith(".docx")) return "DOCX";
            if (ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".png")) return "IMAGE";
        }
        return "UNKNOWN";
    }
}
