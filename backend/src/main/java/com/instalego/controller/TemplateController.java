package com.instalego.controller;

import com.instalego.dto.TemplateUploadResponse;
import com.instalego.model.BankTemplate;
import com.instalego.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/banks/{bankId}/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /**
     * Upload a template PDF and derive its field schema using Gemini.
     */
    @PostMapping
    public ResponseEntity<?> uploadTemplate(
            @PathVariable Long bankId,
            @RequestParam("file") MultipartFile file) {
        try {
            TemplateUploadResponse response = templateService.uploadTemplate(bankId, file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process template: " + e.getMessage()));
        }
    }

    /**
     * Save confirmed field schema for a bank's template.
     */
    @PutMapping
    public ResponseEntity<?> saveSchema(
            @PathVariable Long bankId,
            @RequestBody TemplateUploadResponse request) {
        try {
            BankTemplate template = templateService.saveSchema(bankId, request.getDerivedSchema());
            return ResponseEntity.ok(Map.of(
                    "id", template.getId(),
                    "bankId", template.getBankId(),
                    "version", template.getVersion(),
                    "fieldSchema", template.getFieldSchema()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save schema: " + e.getMessage()));
        }
    }

    /**
     * Get active template for a bank.
     */
    @GetMapping
    public ResponseEntity<?> getTemplate(@PathVariable Long bankId) {
        try {
            var template = templateService.getActiveTemplate(bankId);
            if (template.isPresent()) {
                return ResponseEntity.ok(template.get());
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all template versions for a bank.
     */
    @GetMapping("/versions")
    public ResponseEntity<List<BankTemplate>> getTemplateVersions(@PathVariable Long bankId) {
        return ResponseEntity.ok(templateService.getTemplatesForBank(bankId));
    }

    /**
     * Delete the active template for a bank.
     */
    @DeleteMapping
    public ResponseEntity<?> deleteTemplate(@PathVariable Long bankId) {
        try {
            templateService.deleteTemplate(bankId);
            return ResponseEntity.ok(Map.of("message", "Template deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete template: " + e.getMessage()));
        }
    }

    /**
     * Download the template PDF file.
     */
    @GetMapping("/download")
    public ResponseEntity<?> downloadTemplate(@PathVariable Long bankId) {
        try {
            var templateOpt = templateService.getActiveTemplate(bankId);
            if (templateOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path filePath = Path.of(templateOpt.get().getTemplatePdfPath());
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(filePath.toFile());
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"template_" + bankId + ".pdf\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
