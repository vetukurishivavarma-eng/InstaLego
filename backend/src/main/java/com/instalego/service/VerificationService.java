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
     * Run verification on all documents in a job.
     * Uses a SINGLE Groq API call with all document texts in one optimized prompt.
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

            // Parse documents JSON
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

            // Get report format structure from bank (if admin uploaded one)
            String reportStructure = getReportStructure(job.getBankId());

            // Phase: VERIFYING
            job.setStatus(VerificationJob.Status.VERIFYING);
            job.setCurrentPhase("Analyzing documents with AI...");
            job.setThinkingSteps(toJson(thinkingSteps));
            // Keep the combined extracted text around so follow-up chat questions can be
            // answered later without re-extracting or re-uploading the documents.
            job.setExtractedText(allDocsText.toString());
            jobRepository.save(job);

            // Build optimized single prompt with ALL documents
            Map<String, Object> verificationResult = callGroqForVerification(
                    allDocsText.toString(), documents.size(), reportStructure);

            // Extract thinking steps from the AI response
            @SuppressWarnings("unchecked")
            List<String> aiThinkingSteps = (List<String>) verificationResult.getOrDefault("reasoningSteps", List.of());

            // Merge AI thinking steps into our existing steps
            int orderOffset = thinkingSteps.size();
            for (int i = 0; i < aiThinkingSteps.size(); i++) {
                thinkingSteps.add("🤖 " + aiThinkingSteps.get(i));
            }

            // Extract the report
            Object report = verificationResult.get("report");

            // Save final result
            job.setThinkingSteps(toJson(thinkingSteps));
            job.setReportJson(toJson(report));
            job.setCurrentPhase("✅ Verification complete");
            job.setStatus(VerificationJob.Status.DONE);
            jobRepository.save(job);

            log.info("Verification job {} complete: {} documents, {} thinking steps",
                    jobId, documents.size(), thinkingSteps.size());

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

    /**
     * Answer a follow-up, chat-style question about the documents already analyzed in this
     * session — e.g. "What is link document?" — grounding the answer in the actual uploaded
     * text and any prior Q&A, and formatting the reply the same conversational, Markdown way
     * as the initial report's conversationalSummary.
     */
    @SuppressWarnings("unchecked")
    public String askQuestion(Long jobId, String question) {
        VerificationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getStatus() != VerificationJob.Status.DONE) {
            throw new IllegalStateException("Verification must complete before you can ask questions about it");
        }
        if (job.getExtractedText() == null || job.getExtractedText().isBlank()) {
            throw new IllegalStateException("No extracted document text available for this session");
        }

        List<Map<String, String>> chatHistory = parseChatHistory(job.getChatHistoryJson());

        StringBuilder historyText = new StringBuilder();
        for (Map<String, String> turn : chatHistory) {
            String role = "user".equals(turn.get("role")) ? "User" : "Assistant";
            historyText.append(role).append(": ").append(turn.get("content")).append("\n\n");
        }

        String systemMessage = "You are a knowledgeable legal document analysis assistant embedded in a chat " +
                "interface. You already analyzed the document(s) below for the user. Answer their follow-up " +
                "question the way an expert paralegal would explain it in a chat: clear, well-organized Markdown " +
                "with bold section labels and \"* \" bullet points where helpful. Ground every specific fact " +
                "strictly in the provided document text — never invent names, numbers, or dates. If the " +
                "question asks about a general legal term or concept (e.g. \"link document\", \"encumbrance " +
                "certificate\"), briefly explain the concept AND then relate it back to what actually appears " +
                "in these specific documents. Keep the tone conversational and direct — no boilerplate " +
                "disclaimers. Do not return JSON; return plain Markdown text only.";

        String userPrompt = String.format("""
                DOCUMENT TEXT:
                %s

                PRIOR CONVERSATION:
                %s

                NEW QUESTION:
                %s
                """, job.getExtractedText(), historyText.length() > 0 ? historyText.toString() : "(none yet)", question);

        String answer = groqClient.sendChatPrompt(systemMessage, userPrompt);

        chatHistory.add(Map.of("role", "user", "content", question));
        chatHistory.add(Map.of("role", "assistant", "content", answer));
        job.setChatHistoryJson(toJson(chatHistory));
        jobRepository.save(job);

        return answer;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseChatHistory(String chatHistoryJson) {
        try {
            if (chatHistoryJson == null || chatHistoryJson.isBlank()) {
                return new ArrayList<>();
            }
            List<Map<String, String>> history = objectMapper.readValue(chatHistoryJson, List.class);
            return new ArrayList<>(history);
        } catch (Exception e) {
            log.warn("Failed to parse chat history, starting fresh: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> callGroqForVerification(String allDocsText, int docCount, String reportStructure)
            throws JsonProcessingException {

        String structureInstructions;
        if (reportStructure != null && !reportStructure.isBlank()) {
            structureInstructions = "Follow this report structure exactly:\n" + reportStructure;
        } else {
            structureInstructions = "Use a standard legal verification report format with: " +
                    "Executive Summary, Document-by-Document Analysis, Cross-Reference Check, and Overall Verdict.";
        }

        String systemMessage = "You are a legal document verification expert. " +
                "Analyze the documents, cross-reference them, and generate a verification report. " +
                "Return ONLY valid JSON. No markdown fences, no preamble.";

        String userPrompt = String.format("""
                You have been given %d legal documents to analyze and cross-reference.

                %s

                DOCUMENTS:
                %s

                TASK:
                1. For EACH document, extract and list: date, parties involved, reference numbers/IDs, amounts,
                   property details, and key clauses.
                2. CROSS-REFERENCE between all documents:
                   - Do reference numbers in one document appear and match in others?
                   - Are names, dates, amounts consistent across documents?
                   - Are there any references to documents that are NOT in the provided set?
                3. For each cross-reference, note whether it MATCHES or has a DISCREPANCY.
                4. Identify any gaps, missing information, or inconsistencies.
                5. Generate a verification report following the specified format.
                6. Check for legal validity indicators: proper signatures, notary stamps, witness attestations,
                   registration details, stamp duty, etc.
                7. Also write a "conversationalSummary" — a friendly, chat-style write-up of the document(s), as if
                   a knowledgeable assistant were explaining them to the person who uploaded them. Format it in
                   Markdown, in this style:
                   - Open with one sentence like "I've reviewed the <document type>. Here's a summary of what it
                     contains:"
                   - Then use short bold section headers followed by concise bullet points, e.g. "**Document
                     Overview**", "**Parties**", "**Property/Subject Details**", "**Consideration / Amounts**",
                     "**Chain of Title / History**" (only if the document references prior/link documents),
                     "**Registration / Charges**" — adapt the section names to whatever the document actually is
                     (sale deed, lease, mortgage, will, agreement, etc.); omit sections that don't apply.
                   - Use "* " bullet points for lists, and bold the field label before each value, e.g.
                     "* **Vendor (Seller)**: Smt. X, residing at Y".
                   - Keep it factual and grounded only in what is present in the documents — never invent details.
                   - Close with one short, natural sentence inviting further questions, e.g. "What would you like
                     help with regarding this document — a specific clause, the chain of title, or something
                     else?"

                Return JSON in this exact format:
                {
                  "reasoningSteps": [
                    "Detailed step 1 of your reasoning as it happens...",
                    "Step 2: checking cross-references...",
                    ...
                  ],
                  "report": {
                    "title": "Legal Document Verification Report",
                    "conversationalSummary": "Markdown-formatted chat-style summary as described above",
                    "dateOfAnalysis": "current date",
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
                    "verdict": "PASS / PASS_WITH_CAVEATS / FAIL"
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

            // Ensure the expected structure exists
            if (!parsed.containsKey("reasoningSteps")) {
                parsed.put("reasoningSteps", List.of("Analysis complete"));
            }
            if (!parsed.containsKey("report")) {
                // Wrap the whole response as the report
                Map<String, Object> report = new HashMap<>();
                report.put("title", "Verification Report");
                report.put("conversationalSummary", parsed.getOrDefault("conversationalSummary", null));
                report.put("documentsAnalyzed", parsed.getOrDefault("documentsAnalyzed", List.of()));
                report.put("crossReferenceCheck", parsed.getOrDefault("crossReferenceCheck", List.of()));
                report.put("overallVerdict", parsed.getOrDefault("overallVerdict", "Analysis complete"));
                report.put("recommendations", parsed.getOrDefault("recommendations", List.of()));
                report.put("verdict", parsed.getOrDefault("verdict", "INFO"));
                parsed.put("report", report);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> report = (Map<String, Object>) parsed.get("report");
                if (report.get("conversationalSummary") == null) {
                    report.put("conversationalSummary", buildFallbackSummary(report));
                }
            }

            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse verification response", e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("reasoningSteps", List.of("Analysis could not be parsed"));
            Map<String, Object> report = new HashMap<>();
            report.put("title", "Verification Report");
            report.put("overallVerdict", "Analysis failed: " + e.getMessage());
            report.put("conversationalSummary", "I ran into a problem analyzing this document: " + e.getMessage() +
                    "\n\nYou can try running verification again, or ask me a specific question about the document.");
            report.put("verdict", "ERROR");
            fallback.put("report", report);
            return fallback;
        }
    }

    /**
     * Builds a basic Markdown summary from the structured report fields, used only when the
     * model's JSON response is otherwise valid but happened to omit "conversationalSummary".
     */
    @SuppressWarnings("unchecked")
    private String buildFallbackSummary(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("I've reviewed the uploaded document(s). Here's a summary:\n\n");
        Object verdict = report.get("overallVerdict");
        if (verdict != null) {
            sb.append(verdict).append("\n\n");
        }
        Object docs = report.get("documentsAnalyzed");
        if (docs instanceof List) {
            for (Object d : (List<Object>) docs) {
                if (d instanceof Map) {
                    Map<String, Object> doc = (Map<String, Object>) d;
                    sb.append("**").append(doc.getOrDefault("name", doc.getOrDefault("type", "Document"))).append("**\n");
                    Object findings = doc.get("findings");
                    if (findings instanceof List) {
                        for (Object f : (List<Object>) findings) {
                            sb.append("* ").append(f).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        sb.append("What would you like help with regarding this document — a specific clause, the chain of title, or something else?");
        return sb.toString();
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
