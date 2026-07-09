package com.instalego.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.dto.TemplateUploadResponse;
import com.instalego.model.Bank;
import com.instalego.model.BankTemplate;
import com.instalego.repository.BankTemplateRepository;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final BankTemplateRepository bankTemplateRepository;
    private final BankService bankService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * Upload a template PDF and derive schema using Gemini.
     */
    public TemplateUploadResponse uploadTemplate(Long bankId, MultipartFile file) throws IOException {
        Bank bank = bankService.getBankById(bankId);

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new IllegalArgumentException("Only PDF files are accepted as templates");
        }

        // Save file to disk
        String fileName = "template_" + bankId + "_" + System.currentTimeMillis() + ".pdf";
        Path uploadPath = Path.of(uploadDir, "templates");
        Files.createDirectories(uploadPath);
        Path filePath = uploadPath.resolve(fileName);
        file.transferTo(filePath.toFile());

        // Get next version
        int nextVersion = 1;
        Optional<BankTemplate> latest = bankTemplateRepository.findTopByBankIdOrderByVersionDesc(bankId);
        if (latest.isPresent()) {
            nextVersion = latest.get().getVersion() + 1;
        }

        // Create template entity (save first with empty schema)
        BankTemplate template = new BankTemplate();
        template.setBankId(bankId);
        template.setTemplatePdfPath(filePath.toString());
        template.setVersion(nextVersion);
        template.setFieldSchema("[]");
        BankTemplate saved = bankTemplateRepository.save(template);

        // Derive schema using Gemini
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            String base64Data = Base64.getEncoder().encodeToString(fileBytes);
            List<Map<String, Object>> derivedSchema = geminiService.deriveSchemaFromTemplate(base64Data, "application/pdf");

            return TemplateUploadResponse.builder()
                    .templateId(saved.getId())
                    .bankId(bankId)
                    .templatePdfPath(filePath.toString())
                    .version(nextVersion)
                    .derivedSchema(derivedSchema.stream()
                            .map(m -> TemplateUploadResponse.FieldSchemaEntry.builder()
                                    .fieldName((String) m.get("fieldName"))
                                    .description((String) m.getOrDefault("description", ""))
                                    .type((String) m.getOrDefault("type", "text"))
                                    .required((boolean) m.getOrDefault("required", false))
                                    .build())
                            .toList())
                    .build();
        } catch (Exception e) {
            log.error("Failed to derive schema from template for bank {}. Saving template without schema.", bankId, e);
            return TemplateUploadResponse.builder()
                    .templateId(saved.getId())
                    .bankId(bankId)
                    .templatePdfPath(filePath.toString())
                    .version(nextVersion)
                    .derivedSchema(List.of())
                    .build();
        }
    }

    /**
     * Save confirmed field schema for a bank's template.
     */
    public BankTemplate saveSchema(Long bankId, List<TemplateUploadResponse.FieldSchemaEntry> fields) throws JsonProcessingException {
        BankTemplate template = bankTemplateRepository.findTopByBankIdOrderByVersionDesc(bankId)
                .orElseThrow(() -> new IllegalArgumentException("No template found for bank id: " + bankId));

        String schemaJson = objectMapper.writeValueAsString(fields);
        template.setFieldSchema(schemaJson);
        BankTemplate saved = bankTemplateRepository.save(template);
        log.info("Saved schema for bank {}: {} fields", bankId, fields.size());
        return saved;
    }

    /**
     * Get the active template for a bank.
     */
    public Optional<BankTemplate> getActiveTemplate(Long bankId) {
        return bankTemplateRepository.findTopByBankIdOrderByVersionDesc(bankId);
    }

    public List<BankTemplate> getTemplatesForBank(Long bankId) {
        return bankTemplateRepository.findByBankIdOrderByVersionDesc(bankId);
    }

    /**
     * Delete the active template for a bank.
     */
    public void deleteTemplate(Long bankId) {
        BankTemplate template = bankTemplateRepository.findTopByBankIdOrderByVersionDesc(bankId)
                .orElseThrow(() -> new IllegalArgumentException("No template found for bank id: " + bankId));

        // Delete the file from disk
        try {
            Path filePath = Path.of(template.getTemplatePdfPath());
            Files.deleteIfExists(filePath);
            log.info("Deleted template file: {}", filePath);
        } catch (IOException e) {
            log.warn("Could not delete template file: {}", template.getTemplatePdfPath(), e);
        }

        bankTemplateRepository.delete(template);
        log.info("Deleted template for bank {}: id={}, version={}", bankId, template.getId(), template.getVersion());
    }
}
