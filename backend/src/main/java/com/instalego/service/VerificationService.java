package com.instalego.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.LegalReference;
import com.instalego.repository.LegalReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationService {

    private final LegalReferenceRepository legalReferenceRepository;
    private final GroqClient groqClient;
    private final ObjectMapper objectMapper;
    private static final Tika TIKA = new Tika();

    /**
     * Verify extracted document data against a bank's legal reference documents.
     *
     * @param extractedJson The JSON string of extracted fields from the user's document
     * @param bankId        The bank whose legal references to check against
     * @return Map containing summaryVerdict, detailedFindings, and confidence
     */
    public Map<String, Object> verifyDocument(String extractedJson, Long bankId) {
        List<LegalReference> references = legalReferenceRepository.findByBankId(bankId);

        if (references.isEmpty()) {
            log.info("No legal references found for bank {}, skipping verification", bankId);
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("summaryVerdict", "No verification performed — bank has no legal reference documents uploaded.");
            emptyResult.put("detailedFindings", List.of());
            emptyResult.put("confidence", "N/A");
            return emptyResult;
        }

        // Extract text from all reference documents
        StringBuilder referenceText = new StringBuilder();
        for (LegalReference ref : references) {
            try {
                String text = extractTextFromReference(Path.of(ref.getFilePath()));
                if (text != null && !text.isBlank()) {
                    referenceText.append("--- Document: ").append(ref.getFileName()).append(" ---\n");
                    referenceText.append(text).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to extract text from legal reference {}: {}", ref.getId(), e.getMessage());
            }
        }

        if (referenceText.isEmpty()) {
            log.warn("Could not extract any text from legal references for bank {}", bankId);
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("summaryVerdict", "Verification inconclusive — could not read reference documents.");
            emptyResult.put("detailedFindings", List.of());
            emptyResult.put("confidence", "Low");
            return emptyResult;
        }

        log.info("Verifying extracted data against {} legal reference docs for bank {}", references.size(), bankId);
        return callGroqForVerification(extractedJson, referenceText.toString());
    }

    private Map<String, Object> callGroqForVerification(String extractedJson, String referenceText) {
        String systemMessage = "You are a legal document verification engine. Always return valid JSON in the exact shape requested.";

        String userPrompt = """
                Check whether the submitted document's extracted data complies with the bank's legal
                requirements and policies provided below.

                EXTRACTED DATA (from the user's submitted document):
                %s

                BANK LEGAL REFERENCES AND POLICIES:
                %s

                Analyze the extracted data against each relevant policy/requirement. For each
                finding, determine if the document complies.

                Return ONLY valid JSON in this exact shape — no markdown fences, no preamble:
                {
                  "summaryVerdict": "A single-sentence overall verdict (e.g., 'Document appears compliant ✅' or 'Document has 2 issues that need review ⚠️' or 'Document is not applicable for this bank ❌')",
                  "detailedFindings": [
                    {
                      "area": "Name of the policy area or requirement being checked",
                      "finding": "What the extracted data says",
                      "requirement": "What the bank policy requires",
                      "status": "COMPLIANT or NON_COMPLIANT or INFO",
                      "detail": "Explanation of why it passes or fails"
                    }
                  ],
                  "confidence": "High / Medium / Low"
                }
                """.formatted(extractedJson, referenceText);

        try {
            String response = groqClient.sendPrompt(systemMessage, userPrompt);
            return parseVerificationResponse(response);
        } catch (Exception e) {
            log.error("Verification AI call failed", e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("summaryVerdict", "Verification failed due to an error: " + e.getMessage());
            fallback.put("detailedFindings", List.of());
            fallback.put("confidence", "Low");
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseVerificationResponse(String response) {
        try {
            Map<String, Object> result = objectMapper.readValue(response, Map.class);
            // Ensure all expected keys exist
            if (!result.containsKey("summaryVerdict")) {
                result.put("summaryVerdict", "Could not parse verification result");
            }
            if (!result.containsKey("detailedFindings")) {
                result.put("detailedFindings", List.of());
            }
            if (!result.containsKey("confidence")) {
                result.put("confidence", "Medium");
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse verification response", e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("summaryVerdict", "Verification response could not be parsed");
            fallback.put("detailedFindings", List.of());
            fallback.put("confidence", "Low");
            return fallback;
        }
    }

    private String extractTextFromReference(Path filePath) throws IOException {
        try (InputStream is = new FileInputStream(filePath.toFile())) {
            try {
                String text = TIKA.parseToString(is);
                return text != null ? text.trim() : null;
            } catch (Exception e) {
                throw new IOException("Failed to extract text from reference: " + filePath, e);
            }
        }
    }
}
