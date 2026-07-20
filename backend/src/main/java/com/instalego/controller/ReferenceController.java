package com.instalego.controller;

import com.instalego.model.LegalReference;
import com.instalego.repository.LegalReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/banks/{bankId}/references")
@RequiredArgsConstructor
@Slf4j
public class ReferenceController {

    private final LegalReferenceRepository legalReferenceRepository;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @PostMapping
    public ResponseEntity<?> uploadReference(
            @PathVariable Long bankId,
            @RequestParam("file") MultipartFile file) {
        try {
            String contentType = file.getContentType();
            String originalName = file.getOriginalFilename();
            String fileType = determineFileType(contentType, originalName);

            // Save file to disk
            String fileName = "ref_" + bankId + "_" + System.currentTimeMillis() + "_" + originalName;
            Path uploadPath = Path.of(uploadDir, "references", String.valueOf(bankId));
            Files.createDirectories(uploadPath);
            Path filePath = uploadPath.resolve(fileName);
            // A relative File resolves against the servlet container's temp dir, not the app's
            // working directory — always transfer to an absolute path.
            file.transferTo(filePath.toAbsolutePath().toFile());

            LegalReference ref = new LegalReference();
            ref.setBankId(bankId);
            ref.setFilePath(filePath.toString());
            ref.setFileName(originalName != null ? originalName : "unnamed");
            ref.setFileType(fileType);
            LegalReference saved = legalReferenceRepository.save(ref);

            log.info("Uploaded legal reference for bank {}: id={}, file={}", bankId, saved.getId(), originalName);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", saved.getId(),
                    "bankId", saved.getBankId(),
                    "fileName", saved.getFileName(),
                    "fileType", saved.getFileType(),
                    "createdAt", saved.getCreatedAt().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to upload reference for bank {}", bankId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload reference: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> listReferences(@PathVariable Long bankId) {
        try {
            List<LegalReference> refs = legalReferenceRepository.findByBankId(bankId);
            List<Map<String, Object>> result = refs.stream().map(ref -> Map.<String, Object>of(
                    "id", ref.getId(),
                    "bankId", ref.getBankId(),
                    "fileName", ref.getFileName(),
                    "fileType", ref.getFileType(),
                    "createdAt", ref.getCreatedAt() != null ? ref.getCreatedAt().toString() : null
            )).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{referenceId}")
    public ResponseEntity<?> deleteReference(
            @PathVariable Long bankId,
            @PathVariable Long referenceId) {
        try {
            Optional<LegalReference> ref = legalReferenceRepository.findById(referenceId);
            if (ref.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (!ref.get().getBankId().equals(bankId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Reference does not belong to this bank"));
            }

            // Delete file from disk
            try {
                Files.deleteIfExists(Path.of(ref.get().getFilePath()));
            } catch (IOException e) {
                log.warn("Could not delete reference file: {}", ref.get().getFilePath());
            }

            legalReferenceRepository.delete(ref.get());
            log.info("Deleted legal reference {} for bank {}", referenceId, bankId);

            return ResponseEntity.ok(Map.of("message", "Reference deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String determineFileType(String contentType, String filename) {
        if (contentType != null) {
            if (contentType.equals("application/pdf")) return "PDF";
            if (contentType.startsWith("text/")) return "TXT";
            if (contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                return "DOCX";
        }
        if (filename != null) {
            String ext = filename.toLowerCase();
            if (ext.endsWith(".pdf")) return "PDF";
            if (ext.endsWith(".txt")) return "TXT";
            if (ext.endsWith(".docx")) return "DOCX";
        }
        throw new IllegalArgumentException("Unsupported file type. Accepted: PDF, TXT, DOCX");
    }
}
