package com.instalego.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instalego.model.Bank;
import com.instalego.model.VerificationJob;
import com.instalego.repository.VerificationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private final TextExtractionService textExtractionService;
    private final ObjectMapper objectMapper;

    private static final int OCR_RENDER_DPI = 150;

    // A scanned PDF is rendered page-by-page and OCR'd one page at a time to bound peak memory
    // (see extractPdfText below) — but an unreasonably long scan could still take a very long
    // time / hit rate limits, so it's capped with a clear user-facing error rather than letting
    // the container run indefinitely or exhaust memory on a pathological input.
    private static final int MAX_OCR_PAGES = 60;

    // --- Context-window safety (map-reduce for large files) ---
    // gpt-oss-120b's context window is 131,072 tokens. These budgets stay well under that so a
    // 30MB file's extracted text can never overflow the window regardless of how large it is —
    // it just does more (cheap, fast) round-trips instead of risking a single oversized call.
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;
    private static final int SINGLE_CALL_TOKEN_BUDGET = 90_000;
    private static final int BATCH_TOKEN_BUDGET = 25_000;
    private static final int DOC_SLICE_CHARS = 90_000; // ~22.5k tokens, for one oversized document

    private record DocText(String label, String text) {}

    /** Builds the single-prompt document block on demand — only needed for the (small) single-call path. */
    private String joinDocTexts(List<DocText> docTexts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docTexts.size(); i++) {
            DocText doc = docTexts.get(i);
            sb.append("--- Document ").append(i + 1).append(": \"").append(doc.label()).append("\" ---\n");
            sb.append(doc.text()).append("\n\n");
        }
        return sb.toString();
    }

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

            // Extract text from each document (sequential to minimize peak memory). Only docTexts
            // is kept — the single-call path below joins it into one string on demand, rather than
            // this loop separately accumulating a second full copy of every document's text that
            // would otherwise sit in memory unused whenever the (large-file) chunked path is taken.
            List<String> thinkingSteps = new ArrayList<>();
            List<DocText> docTexts = new ArrayList<>();
            long totalChars = 0;

            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                String filePath = (String) doc.get("filePath");
                String fileName = (String) doc.get("fileName");
                String fileType = (String) doc.get("fileType");

                job.setCurrentPhase("Extracting: " + fileName + "...");
                jobRepository.save(job);

                String label = (String) doc.getOrDefault("label", fileName);
                String extractedText;

                if ("PDF".equalsIgnoreCase(fileType)) {
                    // Handles both the embedded-text case and the scanned/OCR fallback in one pass
                    // (see extractPdfText) — the PDF is loaded into memory only once either way.
                    extractedText = extractPdfText(Path.of(filePath), label, thinkingSteps);
                } else {
                    extractedText = textExtractionService.extractText(Path.of(filePath), fileType);

                    // If normal text extraction found nothing (typical for image-only uploads, e.g.
                    // a photo of a document), fall back to Groq's vision model for OCR.
                    if ((extractedText == null || extractedText.isBlank()) && isOcrEligible(fileType)) {
                        String ocrText = tryGroqVisionOcr(Path.of(filePath));
                        if (ocrText != null && !ocrText.isBlank()) {
                            extractedText = ocrText;
                            thinkingSteps.add("🔎 Used open-source vision AI to read scanned/image content from \"" + label + "\"");
                        }
                    }
                }

                if (extractedText != null && !extractedText.isBlank()) {
                    docTexts.add(new DocText(label, extractedText));
                    totalChars += extractedText.length();

                    thinkingSteps.add("📄 Extracted text from \"" + label + "\" (" + extractedText.length() + " chars)");
                } else {
                    thinkingSteps.add("⚠️ Could not extract text from \"" + label + "\" — may be scanned/image-only");
                }
            }

            if (docTexts.isEmpty()) {
                throw new IllegalStateException("Could not extract text from any uploaded document");
            }

            // Get report format structure from bank (if admin uploaded one), else use the default
            String reportStructure = getReportStructure(job.getBankId());

            // Phase: VERIFYING
            job.setStatus(VerificationJob.Status.VERIFYING);
            job.setCurrentPhase("Verifying legality and checking for missing referenced documents...");
            job.setThinkingSteps(toJson(thinkingSteps));
            jobRepository.save(job);

            // Under budget: one prompt with everything. Over budget: map-reduce so a large file
            // can never overflow the model's context window (see constants above).
            int estimatedTokens = (int) (totalChars / CHARS_PER_TOKEN_ESTIMATE);
            Map<String, Object> verificationResult;
            if (estimatedTokens <= SINGLE_CALL_TOKEN_BUDGET) {
                verificationResult = callGroqForVerification(joinDocTexts(docTexts), documents.size(), reportStructure);
            } else {
                thinkingSteps.add("📚 Large document set (~" + estimatedTokens + " tokens) — analyzing in batches to stay within model limits");
                job.setThinkingSteps(toJson(thinkingSteps));
                jobRepository.save(job);
                verificationResult = callGroqForVerificationChunked(docTexts, documents.size(), reportStructure, job, thinkingSteps);
            }

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
                // The report (already saved above) is all that's needed from here on — the
                // session can't accept more uploads or re-runs once DONE, so the uploaded source
                // files are no longer needed and are removed to keep disk usage from growing
                // unbounded as more submissions accumulate over time.
                deleteSessionFiles(documents);
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

    private String buildStructureInstructions(String reportStructure) {
        return (reportStructure != null && !reportStructure.isBlank())
                ? "Follow this bank's report structure exactly:\n" + reportStructure
                : "No bank-specific template was provided — use this default legal verification report structure:\n"
                + DEFAULT_REPORT_STRUCTURE;
    }

    private Map<String, Object> callGroqForVerification(String allDocsText, int docCount, String reportStructure)
            throws JsonProcessingException {

        String structureInstructions = buildStructureInstructions(reportStructure);

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
                   annexure. Only count something as "referenced" if you can quote the exact sentence or clause
                   that names it — do NOT infer a document might exist just because it would be typical or
                   prudent for this kind of transaction.
                4. For each such referenced document, check whether it IS already included among the documents
                   provided (matching by document number, date, or description). If a referenced document is
                   NOT included, add it to "missingDocuments" with a clear, plain-language description of what
                   to upload, why it's needed to complete verification, and the exact quoted text from the
                   source document that references it (field "evidenceQuote"). Be conservative: if you are not
                   certain a specific document is referenced by name/number/date, do NOT add it to
                   missingDocuments — an empty list is the correct output when nothing is clearly referenced
                   but absent. Never add general/boilerplate suggestions (e.g. "an encumbrance certificate is
                   usually recommended") as a missing document unless it is actually named in the text.
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
                        "referencedIn": "Name of the document that references it",
                        "evidenceQuote": "Exact quoted sentence/clause from the source text naming this document"
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

    /**
     * Map-reduce path for document sets too large to safely fit in a single prompt. Map phase
     * extracts structured per-document facts in small batches (bounded prompt size regardless of
     * how large the source file is); reduce phase does the cross-referencing, missing-document
     * detection, and verdict using only those compact facts — so its payload stays small even
     * when the original file was huge.
     */
    private Map<String, Object> callGroqForVerificationChunked(
            List<DocText> docTexts, int docCount, String reportStructure,
            VerificationJob job, List<String> thinkingSteps) throws JsonProcessingException {

        List<DocText> slices = sliceOversizedDocuments(docTexts);
        List<List<DocText>> batches = packIntoBatches(slices);

        List<Object> allDocumentsAnalyzed = new ArrayList<>();
        List<String> allReferencedNotes = new ArrayList<>();

        for (int b = 0; b < batches.size(); b++) {
            List<DocText> batch = batches.get(b);
            job.setCurrentPhase("Analyzing batch " + (b + 1) + " of " + batches.size() + "...");
            jobRepository.save(job);

            Map<String, Object> batchResult = callGroqForBatchExtraction(batch);

            Object docsAnalyzed = batchResult.get("documentsAnalyzed");
            if (docsAnalyzed instanceof List<?> list) allDocumentsAnalyzed.addAll(list);

            Object refNotes = batchResult.get("referencedDocuments");
            if (refNotes instanceof List<?> list) {
                for (Object o : list) allReferencedNotes.add(String.valueOf(o));
            }

            thinkingSteps.add("🤖 Analyzed batch " + (b + 1) + "/" + batches.size() + " (" + batch.size() + " document part(s))");
            job.setThinkingSteps(toJson(thinkingSteps));
            jobRepository.save(job);
        }

        job.setCurrentPhase("Cross-referencing findings across all documents and finalizing verdict...");
        jobRepository.save(job);

        return callGroqForReduce(allDocumentsAnalyzed, allReferencedNotes, docCount, reportStructure);
    }

    /** Splits any single document whose text alone exceeds the per-batch budget into ordered parts. */
    private List<DocText> sliceOversizedDocuments(List<DocText> docTexts) {
        List<DocText> result = new ArrayList<>();
        for (DocText doc : docTexts) {
            if (doc.text().length() <= DOC_SLICE_CHARS) {
                result.add(doc);
                continue;
            }
            int totalParts = (int) Math.ceil(doc.text().length() / (double) DOC_SLICE_CHARS);
            for (int p = 0; p < totalParts; p++) {
                int start = p * DOC_SLICE_CHARS;
                int end = Math.min(start + DOC_SLICE_CHARS, doc.text().length());
                result.add(new DocText(doc.label() + " (part " + (p + 1) + "/" + totalParts + ")", doc.text().substring(start, end)));
            }
        }
        return result;
    }

    /** Greedily packs document (or document-slice) texts into batches under the per-batch token budget. */
    private List<List<DocText>> packIntoBatches(List<DocText> docTexts) {
        List<List<DocText>> batches = new ArrayList<>();
        List<DocText> current = new ArrayList<>();
        int currentChars = 0;
        int batchCharBudget = BATCH_TOKEN_BUDGET * CHARS_PER_TOKEN_ESTIMATE;

        for (DocText doc : docTexts) {
            if (!current.isEmpty() && currentChars + doc.text().length() > batchCharBudget) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(doc);
            currentChars += doc.text().length();
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callGroqForBatchExtraction(List<DocText> batch) {
        String docsBlock = batch.stream()
                .map(d -> "--- \"" + d.label() + "\" ---\n" + d.text())
                .reduce((a, c) -> a + "\n\n" + c)
                .orElse("");

        String systemMessage = "You are a legal document analyst extracting structured facts from document " +
                "excerpts (part of a larger multi-document legal verification). Return ONLY valid JSON. " +
                "No markdown fences, no preamble.";

        String userPrompt = """
                Analyze the following document excerpt(s):

                %s

                TASK:
                1. For EACH document/part provided, extract and record: document type, date, parties,
                   reference or registration numbers, amounts, property/subject details, and key clauses.
                2. Check legal validity indicators for each: proper execution, signatures, witness
                   attestations, notarization, registration compliance, stamp duty payment, etc. Note any
                   issues found.
                3. List any OTHER document referenced INSIDE this excerpt that would be needed to establish
                   a complete chain of title (e.g. a prior sale deed, gift deed, partition deed, release
                   deed, power of attorney, encumbrance certificate, NOC, mortgage release, court order) —
                   as plain-language notes, one per reference, with enough detail (doc number/date/
                   description) to match it later against the full document set. Only list something here
                   if you can quote the exact sentence/clause that names it. Do NOT list a document type
                   just because it would typically accompany this kind of transaction — if this excerpt
                   doesn't explicitly name a specific other document, leave "referencedDocuments" empty.

                Return JSON in this exact format:
                {
                  "documentsAnalyzed": [
                    {
                      "name": "Document name",
                      "type": "Sale Deed / Prior Sale Deed / etc.",
                      "keyDetails": {
                        "date": "...", "parties": ["..."], "referenceNumbers": ["..."],
                        "amounts": ["..."], "keyClauses": ["..."]
                      },
                      "findings": ["Finding 1..."],
                      "issues": ["Issue 1..."],
                      "status": "VALID / MINOR_ISSUES / INVALID"
                    }
                  ],
                  "referencedDocuments": [
                    "e.g. Sale Deed dated 07-09-2016, Doc No. 5185/2016, referenced as the source of title"
                  ]
                }
                """.formatted(docsBlock);

        try {
            String response = groqClient.sendPrompt(systemMessage, userPrompt);
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            parsed.putIfAbsent("documentsAnalyzed", List.of());
            parsed.putIfAbsent("referencedDocuments", List.of());
            return parsed;
        } catch (Exception e) {
            log.warn("Batch extraction failed for {} document part(s), skipping this batch: {}", batch.size(), e.getMessage());
            return Map.of("documentsAnalyzed", List.of(), "referencedDocuments", List.of());
        }
    }

    private Map<String, Object> callGroqForReduce(
            List<Object> documentsAnalyzed, List<String> referencedNotes, int docCount, String reportStructure)
            throws JsonProcessingException {

        String structureInstructions = buildStructureInstructions(reportStructure);
        String documentsAnalyzedJson = objectMapper.writeValueAsString(documentsAnalyzed);
        String referencedNotesBlock = referencedNotes.isEmpty()
                ? "(none noted)"
                : referencedNotes.stream().map(n -> "- " + n).reduce((a, c) -> a + "\n" + c).orElse("(none noted)");

        String systemMessage = "You are a legal document verification expert working on behalf of a bank/lender. " +
                "You will be given the already-extracted structured analysis of a document set (not the raw " +
                "text) and must finalize the verification. Return ONLY valid JSON. No markdown fences, no preamble.";

        String userPrompt = String.format("""
                You have the extracted analysis of %d document(s) submitted for legal verification. The raw
                documents were too large to analyze in one pass, so they were pre-processed into the
                structured facts below.

                %s

                DOCUMENTS ANALYZED (structured facts, not raw text):
                %s

                DOCUMENTS REFERENCED INSIDE THE ABOVE (candidates to check against what's already analyzed):
                %s

                TASK:
                1. For each candidate referenced document above, check whether it matches (by document
                   number, date, or description) one of the documents already analyzed. If NOT matched, add
                   it to "missingDocuments" with a clear, plain-language description of what to upload, why
                   it's needed, and the "evidenceQuote" text carried over from the candidate note. Be
                   conservative: only include a candidate if it clearly names a specific, identifiable
                   document — if the candidate list is empty or vague, "missingDocuments" should be empty.
                2. Cross-reference the analyzed documents against each other: do names, dates, amounts, and
                   reference numbers match consistently? Flag any MISMATCH.
                3. Decide the verdict:
                   - "INCOMPLETE" if missingDocuments is non-empty.
                   - "FAIL" if all referenced documents are present but there are legal defects, missing
                     signatures/registration, or unresolved mismatches.
                   - "PASS" if all referenced documents are present, cross-references are consistent, and no
                     legal defects were found.
                4. Write a clear "overallVerdict" explaining the reasoning in plain language, and concrete
                   "recommendations" for next steps.

                Do NOT re-list "documentsAnalyzed" in your response — it will be attached separately from
                the structured facts already provided above. Only return the fields below.

                Return JSON in this exact format:
                {
                  "reasoningSteps": ["Step 1...", "Step 2...", ...],
                  "report": {
                    "title": "Legal Verification Report",
                    "missingDocuments": [
                      {"description": "...", "reason": "...", "referencedIn": "...", "evidenceQuote": "..."}
                    ],
                    "crossReferenceCheck": [
                      {"documents": ["Doc A", "Doc B"], "field": "...", "valueInDocA": "...", "valueInDocB": "...", "status": "MATCH / MISMATCH / INFO", "detail": "..."}
                    ],
                    "overallVerdict": "Detailed overall assessment...",
                    "recommendations": ["Recommendation 1..."],
                    "verdict": "PASS / FAIL / INCOMPLETE"
                  }
                }
                """, docCount, structureInstructions, documentsAnalyzedJson, referencedNotesBlock);

        String response = groqClient.sendPrompt(systemMessage, userPrompt);
        Map<String, Object> parsed = parseVerificationResponse(response);

        // Attach the map-phase facts ourselves rather than trusting the model to echo back a
        // potentially large array verbatim — guarantees nothing gets dropped or altered.
        Object reportObj = parsed.get("report");
        if (reportObj instanceof Map<?, ?> reportMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> report = (Map<String, Object>) reportMap;
            report.put("documentsAnalyzed", documentsAnalyzed);
        }

        return parsed;
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

    /** Best-effort cleanup of a completed session's uploaded files — never fails the job over it. */
    private void deleteSessionFiles(List<Map<String, Object>> documents) {
        for (Map<String, Object> doc : documents) {
            String filePath = (String) doc.get("filePath");
            if (filePath == null) continue;
            try {
                Files.deleteIfExists(Path.of(filePath));
            } catch (Exception e) {
                log.warn("Failed to delete uploaded file {} after job completion: {}", filePath, e.getMessage());
            }
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
        return "IMAGE".equals(fileType != null ? fileType.toUpperCase() : "");
    }

    /**
     * Reads visible text from a plain image upload (e.g. a photo of a document) using Groq's
     * open-weight vision model. PDFs go through extractPdfText instead, which streams pages
     * one at a time rather than loading a whole image list into memory up front.
     */
    private String tryGroqVisionOcr(Path filePath) {
        try {
            byte[] jpeg = reencodeAsJpeg(Files.readAllBytes(filePath));
            return groqClient.transcribeImages(List.of(jpeg));
        } catch (Exception e) {
            log.warn("Groq vision OCR fallback failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Loads a PDF once and either returns its embedded text layer, or — if there isn't one (a
     * scan) — OCRs it page by page via Groq's vision model, one page at a time: each page's
     * rendered image is encoded, sent, and discarded before the next page is rendered, so peak
     * memory stays roughly constant regardless of page count instead of scaling with it (the
     * previous approach rasterized every page into a list before OCR-ing any of them, and
     * separately re-loaded the whole PDF a second time after the text-extraction attempt above
     * had already loaded it once).
     */
    private String extractPdfText(Path pdfPath, String label, List<String> thinkingSteps) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile())) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(document);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }

            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return null;
            }
            if (pageCount > MAX_OCR_PAGES) {
                throw new IllegalStateException("\"" + label + "\" is a scanned document with " + pageCount +
                        " pages, which exceeds the " + MAX_OCR_PAGES + "-page limit for OCR. Please split it " +
                        "into smaller files and upload them separately.");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder ocrText = new StringBuilder();
            for (int i = 0; i < pageCount; i++) {
                byte[] pageJpeg = encodeAsJpeg(renderer.renderImageWithDPI(i, OCR_RENDER_DPI));
                String pageText = groqClient.transcribeImages(List.of(pageJpeg));
                if (pageText != null && !pageText.isBlank()) {
                    if (ocrText.length() > 0) ocrText.append("\n\n");
                    ocrText.append(pageText);
                }
            }

            if (ocrText.length() == 0) {
                return null;
            }
            thinkingSteps.add("🔎 Used open-source vision AI to read scanned/image content from \"" + label + "\"");
            return ocrText.toString();
        } catch (IllegalStateException e) {
            throw e; // page-limit — surface as a clear job failure rather than swallowing it
        } catch (Exception e) {
            log.warn("PDF processing failed for {}: {}", pdfPath, e.getMessage());
            return null;
        }
    }

    private byte[] encodeAsJpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] reencodeAsJpeg(byte[] originalBytes) throws Exception {
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(originalBytes));
        if (image == null) return originalBytes; // already a supported format Groq can read directly
        return encodeAsJpeg(image);
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
