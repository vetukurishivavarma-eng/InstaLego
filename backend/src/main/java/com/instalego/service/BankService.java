package com.instalego.service;

import com.instalego.dto.BankRequest;
import com.instalego.model.Bank;
import com.instalego.repository.BankRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankService {

    private final BankRepository bankRepository;
    private final TextExtractionService textExtractionService;
    private final GroqClient groqClient;

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

    /**
     * Banks available for users to submit documents against. A bank-specific report format is
     * optional — verification falls back to the default report structure when one isn't set —
     * so every created bank is usable, not just ones with a custom format uploaded.
     */
    public List<Bank> getBanksWithActiveTemplate() {
        return bankRepository.findAll();
    }

    public Bank getBankById(Long id) {
        return bankRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank not found with id: " + id));
    }

    /**
     * Upload a sample verification report PDF for a bank. Its structure is derived using the
     * open-source Groq model so future verifications for this bank follow the same shape.
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
        // A relative File resolves against the servlet container's temp dir, not the app's
        // working directory — always transfer to an absolute path.
        file.transferTo(filePath.toAbsolutePath().toFile());

        // Derive the report structure from the sample PDF's text using the open-source Groq model
        String structureDescription = null;
        try {
            String sampleText = textExtractionService.extractText(filePath, "PDF");
            if (sampleText != null && !sampleText.isBlank()) {
                structureDescription = groqClient.deriveReportStructure(sampleText);
                log.info("Derived report structure for bank {} ({} chars)", bankId, structureDescription.length());
            } else {
                log.warn("Sample report PDF for bank {} had no extractable text; using default structure", bankId);
            }
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
