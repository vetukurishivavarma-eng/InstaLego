package com.instalego.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private final ObjectMapper objectMapper;

    @Value("${app.gemini-api-url}")
    private String geminiApiUrl;

    /**
     * Expose the configured model name for logging purposes.
     */
    public String getModel() {
        // Extract model name from URL: /models/{MODEL_NAME}:generateContent
        if (geminiApiUrl != null && geminiApiUrl.contains("/models/")) {
            String afterModels = geminiApiUrl.substring(geminiApiUrl.indexOf("/models/") + 8);
            return afterModels.contains(":generateContent")
                    ? afterModels.substring(0, afterModels.indexOf(":generateContent"))
                    : afterModels;
        }
        return "unknown";
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Derive field schema from a template PDF by sending it to Gemini.
     */
    public List<Map<String, Object>> deriveSchemaFromTemplate(String base64Pdf, String mimeType) {
        String prompt = """
                You are a document analysis engine. Analyze the provided template/PDF document.
                This document is a bank form template. List all the fields/placeholders in this form
                and their data types. For each field, provide:
                - fieldName: a camelCase identifier
                - description: what this field represents
                - type: one of "text", "date", "number", "boolean"
                - required: true/false based on whether the field appears mandatory

                Return ONLY a JSON array. No markdown, no preamble, no commentary.
                Example:
                [
                  {"fieldName": "applicantName", "description": "Full name of the applicant", "type": "text", "required": true},
                  {"fieldName": "loanAmount", "description": "Requested loan amount", "type": "number", "required": true}
                ]
                """;

        String response = callGemini(prompt, base64Pdf, mimeType);
        return parseSchemaResponse(response);
    }

    /**
     * Extract structured data from a source document using a bank's field schema.
     */
    public Map<String, Object> extractFields(String base64File, String mimeType, String fieldSchemaJson) {
        String prompt = """
                You are a document data-extraction engine. You will be given:
                1. A source legal document (may be scanned, typed, or a Word doc converted to PDF).
                2. A target field schema (JSON) describing what fields a bank requires.

                Read and understand the source document fully. Extract values for every field in the
                schema. If a field cannot be found, return null for it and add it to a "missing_fields"
                list — do not guess or hallucinate values.

                Return ONLY valid JSON in this exact shape:
                {
                  "fields": { "<fieldName>": "<value or null>", ... },
                  "missing_fields": ["<fieldName>", ...],
                  "confidence_notes": "<brief note on anything ambiguous>"
                }
                No markdown fences, no preamble, no extra commentary — JSON only.
                
                Target field schema:
                %s
                """.formatted(fieldSchemaJson);

        String response = callGemini(prompt, base64File, mimeType);
        return parseExtractionResponse(response);
    }

    /**
     * OCR-style fallback: ask Gemini to transcribe all visible text from a document.
     * Used when standard text extraction (PDFBox/Tika) fails — typically because the file is a
     * scanned/image-only PDF or a photo, which have no embedded text layer for those libraries
     * to read. Gemini's multimodal model can read the pixels directly instead.
     *
     * @param base64File Base64-encoded file bytes
     * @param mimeType   e.g. "application/pdf", "image/jpeg", "image/png"
     * @return The transcribed plain text, or an empty string if nothing could be read
     */
    public String extractRawText(String base64File, String mimeType) {
        String prompt = """
                You are a precise OCR/transcription engine. Read the provided document (which may be a
                scanned image, photograph, or image-only PDF) and transcribe ALL visible text exactly as
                it appears, preserving structure (paragraphs, line breaks) as best you can. Do not
                summarize, translate, explain, or add commentary — only transcribe the actual text content.
                If the document contains no readable text at all, return an empty string.

                Return ONLY valid JSON in this exact shape:
                {
                  "extractedText": "<the full transcribed text, or empty string if none found>"
                }
                No markdown fences, no preamble — JSON only.
                """;

        String response = callGemini(prompt, base64File, mimeType);
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("extractedText")) {
                return node.get("extractedText").asText();
            }
            return response;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini OCR response as JSON, returning raw text instead: {}", e.getMessage());
            return response;
        }
    }

    private String callGemini(String prompt, String base64Data, String mimeType) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is not set");
        }

        try {
            // Build the request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode contents = requestBody.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");

            // Add text part
            ObjectNode textPart = parts.addObject();
            textPart.put("text", prompt);

            // Add inline data part
            ObjectNode inlineDataPart = parts.addObject();
            ObjectNode inlineData = inlineDataPart.putObject("inlineData");
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", base64Data);

            // System instruction for safe output
            ObjectNode systemInstruction = requestBody.putObject("systemInstruction");
            ArrayNode sysParts = systemInstruction.putArray("parts");
            ObjectNode sysPart = sysParts.addObject();
            sysPart.put("text", "You are a precise document processing engine. Always return valid JSON only.");

            // Set generation config
            ObjectNode generationConfig = requestBody.putObject("generationConfig");
            generationConfig.put("temperature", 0.1);
            generationConfig.put("maxOutputTokens", 4096);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            log.debug("Gemini API request (excluding base64 data): prompt={}, mimeType={}", prompt, mimeType);

            // Retry logic with exponential backoff
            int maxRetries = 3;
            int baseDelayMs = 1000;
            IOException lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(geminiApiUrl + "?key=" + apiKey))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int statusCode = response.statusCode();
                    String responseBody = response.body();

                    log.debug("Gemini API response status: {}", statusCode);

                    if (statusCode == 200) {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode candidates = root.get("candidates");
                        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                            JsonNode firstCandidate = candidates.get(0);
                            JsonNode contentNode = firstCandidate.get("content");
                            if (contentNode != null) {
                                JsonNode partsNode = contentNode.get("parts");
                                if (partsNode != null && partsNode.isArray() && partsNode.size() > 0) {
                                    String text = partsNode.get(0).get("text").asText();
                                    // Clean markdown fences if present
                                    text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                                    return text;
                                }
                            }
                        }
                        throw new RuntimeException("Unexpected Gemini response structure: " + responseBody);
                    } else if (statusCode == 429 && attempt < maxRetries) {
                        int delay = baseDelayMs * (int) Math.pow(2, attempt - 1);
                        log.warn("Gemini API rate limited (429), retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                        continue;
                    } else {
                        throw new RuntimeException("Gemini API returned status " + statusCode + ": " + responseBody);
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        int delay = baseDelayMs * (int) Math.pow(2, attempt - 1);
                        log.warn("Gemini API request failed, retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                    }
                }
            }

            if (lastException != null) {
                throw new RuntimeException("Gemini API request failed after " + maxRetries + " retries", lastException);
            }

            throw new RuntimeException("Gemini API request failed after " + maxRetries + " retries");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini API request interrupted", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Gemini API call failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSchemaResponse(String response) {
        try {
            // Try to parse as JSON array
            JsonNode node = objectMapper.readTree(response);
            if (node.isArray()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (JsonNode element : node) {
                    Map<String, Object> field = new HashMap<>();
                    field.put("fieldName", element.get("fieldName").asText());
                    field.put("description", element.has("description") ? element.get("description").asText() : "");
                    field.put("type", element.has("type") ? element.get("type").asText() : "text");
                    field.put("required", element.has("required") && element.get("required").asBoolean());
                    result.add(field);
                }
                return result;
            }
            throw new RuntimeException("Expected JSON array but got: " + response);
        } catch (Exception e) {
            log.error("Failed to parse schema response from Gemini: {}", response, e);
            // Return a default minimal schema
            return List.of(
                    Map.of("fieldName", "documentTitle", "description", "Title of the document", "type", "text", "required", true),
                    Map.of("fieldName", "documentDate", "description", "Date of the document", "type", "date", "required", true)
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExtractionResponse(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            Map<String, Object> result = new HashMap<>();

            if (node.has("fields")) {
                JsonNode fieldsNode = node.get("fields");
                Map<String, Object> fields = new HashMap<>();
                fieldsNode.fieldNames().forEachRemaining(fieldName -> {
                    JsonNode value = fieldsNode.get(fieldName);
                    fields.put(fieldName, value.isNull() ? null : value.asText());
                });
                result.put("fields", fields);
            }

            if (node.has("missing_fields")) {
                List<String> missingFields = new ArrayList<>();
                node.get("missing_fields").forEach(n -> missingFields.add(n.asText()));
                result.put("missing_fields", missingFields);
            }

            if (node.has("confidence_notes")) {
                result.put("confidence_notes", node.get("confidence_notes").asText());
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse extraction response from Gemini: {}", response, e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("fields", Map.of("extractedText", response));
            fallback.put("missing_fields", List.of());
            fallback.put("confidence_notes", "Raw response due to parsing failure");
            return fallback;
        }
    }
}
