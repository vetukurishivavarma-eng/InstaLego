package com.instalego.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.Bank;
import com.instalego.model.VerificationJob;
import com.instalego.repository.VerificationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationService {

    private final VerificationJobRepository jobRepository;
    private final BankService bankService;
    private final GroqClient groqClient;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    private static final Tika TIKA = new Tika();

    /**
     * Default legal verification report structure used when a bank hasn't uploaded its own
     * report template. Covers what any bank/lender would typically need to sign off on a
     * property's legal standing.
     */
    private static final String DEFAULT_REPORT_STRUCTURE = """
            1. Document Overview — type, date of execution, registration number/date, registering office.
            2. Parties Involved — full names and roles (e.g. Vendor/Seller, Vendee/Buyer, executant).
            3. Property / Subject Matter Details — description, extent, survey/plot numbers, boundaries.
            4. Chain of Title — the sequence of prior documents that establish how the current holder
               acquired the property (link documents / prior deeds).
            5. Document-by-Document Legal Validity — signatures, witness attestations, notarization,
               registration compliance, stamp duty payment.
            6. Cross-Reference & Consistency Check — do names, dates, amounts, and reference numbers
               match consistently across all provided documents?
            7. Missing Documents — any document referenced but not provided, with why it's needed.
            8. Legal Verdict — PASS (legally valid, chain complete), FAIL (defects found), or
               INCOMPLETE (cannot conclude until missing documents are provided).
            9. Recommendations — concrete next steps.
            """;

    /**
     * Run verification on all documents currently attached to a job. Called both for the first
     * run and for every re-run after the user uploads additional (previously missing) documents —
     * each run re-extracts and re-analyzes the FULL current document set from scratch.
     * Transactional boundary is managed by the caller (VerificationController.runAsyncVerification).
     */
    public VerificationJob runVerification(Long jobId) {
        VerificationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        try {
            // Phase: EXTRACTING
            job.setStatus(VerificationJob.Status.EXTRACTING);
            job.setCurrentPhase("Extracting text from documents...");
            jobRepository.save(job);

            // Parse documents JSON (the full current set — original + any added since)
            List<Map<String, Object>> documents = parseDocumentsJson(job.getDocumentsJson());

            // Extract text from each document (sequential to minimize peak memory)
            List<String> thinkingSteps = new ArrayList<>();
            StringBuilder allDocsText = new StringBuilder();

            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                String filePath = (String) doc.get("filePath");
                String fileName = (String) doc.get("fileName");
                String fileType = (String) doc.get("fileType");

                job.setCurrentPhase("Extracting: " + fileName + "...");
                jobRepository.save(job);

                String extractedText = extractText(Path.of(filePath), fileType);
                String label = (String) doc.getOrDefault("label", fileName);

                // If normal text extraction found nothing (typical for scanned/image-only PDFs
                // or photos, which have no embedded text layer), fall back to AI OCR via Gemini.
                if ((extractedText == null || extractedText.isBlank()) && isOcrEligible(fileType)) {
                    String ocrText = tryGeminiOcr(Path.of(filePath), fileType);
                    if (ocrText != null && !ocrText.isBlank()) {
                        extractedText = ocrText;
                        thinkingSteps.add("🔎 Used AI vision (Gemini) to read scanned/image content from \"" + label + "\"");
                    }
                }

                if (extractedText != null && !extractedText.isBlank()) {
                    allDocsText.append("--- Document ").append(i + 1).append(": \"").append(label).append("\" ---\n");
                    allDocsText.append(extractedText).append("\n\n");

                    thinkingSteps.add("📄 Extracted text from \"" + label + "\" (" + extractedText.length() + " chars)");
                } else {
                    thinkingSteps.add("⚠️ Could not extract text from \"" + label + "\" — may be scanned/image-only");
                }
            }

            if (allDocsText.isEmpty()) {
                throw new IllegalStateException("Could not extract text from any uploaded document");
            }

            // Get report format structure from bank (if admin uploaded one), else use the default
            String reportStructure = getReportStructure(job.getBankId());

            // Phase: VERIFYING
            job.setStatus(VerificationJob.Status.VERIFYING);
            job.setCurrentPhase("Verifying legality and checking for missing referenced documents...");
            job.setThinkingSteps(toJson(thinkingSteps));
            jobRepository.save(job);

            // Build optimized single prompt with ALL documents
            Map<String, Object> verificationResult = callGroqForVerification(
                    allDocsText.toString(), documents.size(), reportStructure);

            // Extract thinking steps from the AI response
            @SuppressWarnings("unchecked")
            List<String> aiThinkingSteps = (List<String>) verificationResult.getOrDefault("reasoningSteps", List.of());
            for (String step : aiThinkingSteps) {
                thinkingSteps.add("🤖 " + step);
            }

            // Extract the report and figure out whether any referenced documents are missing
            Object reportObj = verificationResult.get("report");
            List<Object> missingDocuments = extractMissingDocuments(reportObj);
            String verdict = extractVerdict(reportObj);

            job.setThinkingSteps(toJson(thinkingSteps));
            job.setReportJson(toJson(reportObj));
            job.setMissingDocumentsJson(toJson(missingDocuments));

            if (!missingDocuments.isEmpty()) {
                job.setStatus(VerificationJob.Status.NEEDS_MORE_DOCUMENTS);
                job.setCurrentPhase("📎 " + missingDocuments.size() + " referenced document(s) still needed to complete verification");
            } else {
                job.setStatus(VerificationJob.Status.DONE);
                job.setCurrentPhase("✅ Verification complete — verdict: " + verdict);
            }
            jobRepository.save(job);

            log.info("Verification job {} finished: {} documents, verdict={}, missing={}",
                    jobId, documents.size(), verdict, missingDocuments.size());

            return job;

        } catch (Exception e) {
            log.error("Verification job {} failed", jobId, e);
            job.setStatus(VerificationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCurrentPhase("❌ Verification failed");
            jobRepository.save(job);
            return job;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> extractMissingDocuments(Object reportObj) {
        if (reportObj instanceof Map) {
            Object md = ((Map<String, Object>) reportObj).get("missingDocuments");
            if (md instanceof List) {
                return (List<Object>) md;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private String extractVerdict(Object reportObj) {
        if (reportObj instanceof Map) {
            Object v = ((Map<String, Object>) reportObj).get("verdict");
            if (v != null) {
                return v.toString();
            }
        }
        return "INFO";
    }

    private Map<String, Object> callGroqForVerification(String allDocsText, int docCount, String reportStructure)
            throws JsonProcessingException {

        String structureInstructions = (reportStructure != null && !reportStructure.isBlank())
                ? "Follow this bank's report structure exactly:\n" + reportStructure
                : "No bank-specific template was provided — use this default legal verification report structure:\n"
                + DEFAULT_REPORT_STRUCTURE;

        String systemMessage = "You are a legal document verification expert working on behalf of a bank/lender. " +
                "Your job is to determine whether the submitted document(s) are legally valid and whether the " +
                "chain of title / supporting documentation is complete. Return ONLY valid JSON. No markdown " +
                "fences, no preamble.";

        String userPrompt = String.format("""
                You have been given %d document(s) to verify for legal validity and completeness.

                %s

                DOCUMENTS PROVIDED:
                %s

                TASK:
                1. For EACH document provided, extract and record: document type, date, parties, reference or
                   registration numbers, amounts, property/subject details, and key clauses.
                2. Check legal validity indicators for each: proper execution, signatures, witness attestations,
                   notarization, registration compliance, stamp duty payment, etc. Note any issues found.
                3. Identify every OTHER document referenced INSIDE the provided documents that would be needed
                   to establish a complete, verifiable chain — e.g. a prior sale deed, gift deed, partition deed,
                   release deed, power of attorney, encumbrance certificate, NOC, mortgage release, court order,
                   or any document cited as the source of title, as a schedule reference, or as a supporting
                   annexure.
                4. For each such referenced document, check whether it IS already included among the documents
                   provided (matching by document number, date, or description). If a referenced document is
                   NOT included, add it to "missingDocuments" with a clear, plain-language description of what
                   to upload and why it's needed to complete verification.
                5. Cross-reference the documents that ARE provided against each other: do names, dates, amounts,
                   and reference numbers match consistently? Flag any MISMATCH.
                6. Decide the verdict:
                   - "INCOMPLETE" if missingDocuments is non-empty — verification cannot conclude legality until
                     they are provided.
                   - "FAIL" if all referenced documents are present but there are legal defects, missing
                     signatures/registration, or unresolved mismatches.
                   - "PASS" if all referenced documents are present, cross-references are consistent, and no
                     legal defects were found.
                7. Write a clear "overallVerdict" explaining the reasoning in plain language, and concrete
                   "recommendations" for next steps.

                Return JSON in this exact format:
                {
                  "reasoningSteps": [
                    "Detailed step 1 of your reasoning as it happens...",
                    "Step 2: checking cross-references...",
                    ...
                  ],
                  "report": {
                    "title": "Legal Verification Report",
                    "documentsAnalyzed": [
                      {
                        "name": "Document name",
                        "type": "Sale Deed / Prior Sale Deed / etc.",
                        "keyDetails": {
                          "date": "...",
                          "parties": ["...", "..."],
                          "referenceNumbers": ["..."],
                          "amounts": ["..."],
                          "keyClauses": ["..."]
                        },
                        "findings": ["Finding 1...", "Finding 2..."],
                        "issues": ["Issue 1..."],
                        "status": "VALID / MINOR_ISSUES / INVALID"
                      }
                    ],
                    "missingDocuments": [
                      {
                        "description": "e.g. Sale Deed dated 07-09-2016, Doc No. 5185/2016",
                        "reason": "Why this document is needed to complete the chain of title",
                        "referencedIn": "Name of the document that references it"
                      }
                    ],
                    "crossReferenceCheck": [
                      {
                        "documents": ["Doc A", "Doc B"],
                        "field": "Reference number / Date / Party name",
                        "valueInDocA": "...",
                        "valueInDocB": "...",
                        "status": "MATCH / MISMATCH / INFO",
                        "detail": "Explanation..."
                      }
                    ],
                    "overallVerdict": "Detailed overall assessment...",
                    "recommendations": ["Recommendation 1...", "Recommendation 2..."],
                    "verdict": "PASS / FAIL / INCOMPLETE"
                  }
                }
                """, docCount, structureInstructions, allDocsText);

        String response = groqClient.sendPrompt(systemMessage, userPrompt);
        return parseVerificationResponse(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseVerificationResponse(String response) {
        try {
            // The response might include the reasoningSteps and report at top level
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);

            if (!parsed.containsKey("reasoningSteps")) {
                parsed.put("reasoningSteps", List.of("Analysis complete"));
            }
            if (!parsed.containsKey("report")) {
                // Wrap the whole response as the report
                Map<String, Object> report = new HashMap<>();
                report.put("title", "Legal Verification Report");
                report.put("documentsAnalyzed", parsed.getOrDefault("documentsAnalyzed", List.of()));
                report.put("missingDocuments", parsed.getOrDefault("missingDocuments", List.of()));
                report.put("crossReferenceCheck", parsed.getOrDefault("crossReferenceCheck", List.of()));
                report.put("overallVerdict", parsed.getOrDefault("overallVerdict", "Analysis complete"));
                report.put("recommendations", parsed.getOrDefault("recommendations", List.of()));
                report.put("verdict", parsed.getOrDefault("verdict", "INCOMPLETE"));
                parsed.put("report", report);
            } else {
                Map<String, Object> report = (Map<String, Object>) parsed.get("report");
                report.putIfAbsent("missingDocuments", List.of());
                report.putIfAbsent("verdict", "INCOMPLETE");
            }

            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse verification response", e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("reasoningSteps", List.of("Analysis could not be parsed"));
            Map<String, Object> report = new HashMap<>();
            report.put("title", "Legal Verification Report");
            report.put("overallVerdict", "Analysis failed: " + e.getMessage());
            report.put("missingDocuments", List.of());
            report.put("verdict", "ERROR");
            fallback.put("report", report);
            return fallback;
        }
    }

    private String getReportStructure(Long bankId) {
        try {
            Bank bank = bankService.getBankById(bankId);
            if (bank.getReportStructure() != null && !bank.getReportStructure().isBlank()) {
                return bank.getReportStructure();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isOcrEligible(String fileType) {
        String ext = fileType != null ? fileType.toUpperCase() : "";
        return "PDF".equals(ext) || "IMAGE".equals(ext);
    }

    /**
     * Sends the raw file to Gemini's multimodal model to transcribe visible text, for documents
     * where PDFBox/Tika found no embedded text layer (e.g. scans or photos of documents).
     */
    private String tryGeminiOcr(Path filePath, String fileType) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = "PDF".equalsIgnoreCase(fileType) ? "application/pdf" : guessImageMimeType(filePath);
            return geminiService.extractRawText(base64, mimeType);
        } catch (Exception e) {
            log.warn("Gemini OCR fallback failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String guessImageMimeType(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String extractText(Path filePath, String fileType) {
        try {
            String ext = fileType != null ? fileType.toUpperCase() : "";
            if ("PDF".equals(ext)) {
                return extractTextFromPdf(filePath);
            }
            return extractTextWithTika(filePath);
        } catch (Exception e) {
            log.warn("Text extraction failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String extractTextFromPdf(Path pdfPath) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile())) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(document);
            return text != null ? text.trim() : null;
        }
    }

    private String extractTextWithTika(Path filePath) throws Exception {
        try (InputStream is = new FileInputStream(filePath.toFile())) {
            String text = TIKA.parseToString(is);
            return text != null ? text.trim() : null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDocumentsJson(String documentsJson) {
        try {
            return objectMapper.readValue(documentsJson, List.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse documents JSON", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize to JSON", e);
            return "[]";
        }
    }

}
