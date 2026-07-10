package com.instalego.service;

import com.instalego.dto.BankRequest;
import com.instalego.model.Bank;
import com.instalego.repository.BankRepository;
import com.instalego.repository.BankTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankService {

    private final BankRepository bankRepository;
    private final BankTemplateRepository bankTemplateRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public Bank createBank(BankRequest request) {
        if (bankRepository.findAll().stream().anyMatch(b -> b.getName().equalsIgnoreCase(request.getName()))) {
            throw new IllegalArgumentException("Bank with name '" + request.getName() + "' already exists");
        }
        Bank bank = new Bank();
        bank.setName(request.getName());
        Bank saved = bankRepository.save(bank);
        log.info("Created bank: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    public List<Bank> getAllBanks() {
        return bankRepository.findAll();
    }

    public List<Bank> getBanksWithActiveTemplate() {
        List<Bank> allBanks = bankRepository.findAll();
        return allBanks.stream()
                .filter(bank -> bankTemplateRepository.existsByBankId(bank.getId()))
                .toList();
    }

    public Bank getBankById(Long id) {
        return bankRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank not found with id: " + id));
    }

    /**
     * Upload a sample verification report PDF for a bank.
     * Gemini analyzes it to derive the report structure.
     */
    public Map<String, Object> uploadReportFormat(Long bankId, MultipartFile file) throws IOException {
        Bank bank = getBankById(bankId);

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new IllegalArgumentException("Only PDF files are accepted as report format samples");
        }

        // Save file
        String fileName = "report_format_" + bankId + "_" + System.currentTimeMillis() + ".pdf";
        Path uploadPath = Path.of(uploadDir, "report-formats");
        Files.createDirectories(uploadPath);
        Path filePath = uploadPath.resolve(fileName);
        file.transferTo(filePath.toFile());

        // Derive structure via Gemini
        String structureDescription = null;
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            String base64Data = Base64.getEncoder().encodeToString(fileBytes);
            // Use Gemini to describe the report structure
            var schema = geminiService.deriveSchemaFromTemplate(base64Data, "application/pdf");
            structureDescription = objectMapper.writeValueAsString(schema);
            log.info("Derived report structure for bank {}: {} fields", bankId, schema.size());
        } catch (Exception e) {
            log.warn("Could not derive report structure from sample PDF for bank {}: {}", bankId, e.getMessage());
        }

        // Store on bank entity
        bank.setReportTemplatePath(filePath.toString());
        bank.setReportStructure(structureDescription);
        bankRepository.save(bank);

        return Map.of(
                "bankId", bankId,
                "filePath", filePath.toString(),
                "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "report_format.pdf",
                "structureDerived", structureDescription != null,
                "message", "Report format uploaded" + (structureDescription != null ? " and structure derived" : "")
        );
    }

    /**
     * Get the report format info for a bank.
     */
    public Map<String, Object> getReportFormat(Long bankId) {
        Bank bank = getBankById(bankId);
        return Map.of(
                "bankId", bankId,
                "hasReportFormat", bank.getReportTemplatePath() != null,
                "reportTemplatePath", bank.getReportTemplatePath() != null ? bank.getReportTemplatePath() : "",
                "reportStructure", bank.getReportStructure() != null ? bank.getReportStructure() : ""
        );
    }

    /**
     * Delete the report format for a bank.
     */
    public void deleteReportFormat(Long bankId) throws IOException {
        Bank bank = getBankById(bankId);
        if (bank.getReportTemplatePath() != null) {
            Path filePath = Path.of(bank.getReportTemplatePath());
            Files.deleteIfExists(filePath);
        }
        bank.setReportTemplatePath(null);
        bank.setReportStructure(null);
        bankRepository.save(bank);
        log.info("Deleted report format for bank {}", bankId);
    }
}
