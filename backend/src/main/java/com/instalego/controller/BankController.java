package com.instalego.controller;

import com.instalego.dto.BankRequest;
import com.instalego.model.Bank;
import com.instalego.service.BankService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;

    @PostMapping
    public ResponseEntity<?> createBank(@Valid @RequestBody BankRequest request) {
        try {
            Bank bank = bankService.createBank(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(bank);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<Bank>> getAllBanks() {
        return ResponseEntity.ok(bankService.getAllBanks());
    }

    @GetMapping("/with-template")
    public ResponseEntity<List<Bank>> getBanksWithTemplate() {
        return ResponseEntity.ok(bankService.getBanksWithActiveTemplate());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBank(@PathVariable Long id) {
        try {
            Bank bank = bankService.getBankById(id);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("id", bank.getId());
            response.put("name", bank.getName());
            response.put("createdAt", bank.getCreatedAt());
            response.put("hasReportFormat", bank.getReportTemplatePath() != null);
            response.put("reportStructure", bank.getReportStructure());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Upload a sample verification report PDF for a bank.
     * The open-source Groq model analyzes it and derives the expected report structure.
     */
    @PostMapping("/{bankId}/report-format")
    public ResponseEntity<?> uploadReportFormat(
            @PathVariable Long bankId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            var result = bankService.uploadReportFormat(bankId, file);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload report format: " + e.getMessage()));
        }
    }

    /**
     * Get the report format info for a bank.
     */
    @GetMapping("/{bankId}/report-format")
    public ResponseEntity<?> getReportFormat(@PathVariable Long bankId) {
        try {
            return ResponseEntity.ok(bankService.getReportFormat(bankId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete the report format for a bank.
     */
    @DeleteMapping("/{bankId}/report-format")
    public ResponseEntity<?> deleteReportFormat(@PathVariable Long bankId) {
        try {
            bankService.deleteReportFormat(bankId);
            return ResponseEntity.ok(Map.of("message", "Report format deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete report format: " + e.getMessage()));
        }
    }
}
