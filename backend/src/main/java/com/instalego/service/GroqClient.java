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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroqClient {

    private final ObjectMapper objectMapper;

    @Value("${app.groq-api-url}")
    private String groqApiUrl;

    @Value("${app.groq-model}")
    private String groqModel;

    /**
     * Expose the configured model name for logging purposes.
     */
    public String getModel() {
        return groqModel;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Extract structured fields from extracted document text using Groq.
     *
     * @param extractedText   Plain text extracted from the source document (via PDFBox/Tika)
     * @param fieldSchemaJson JSON array string describing the target field schema
     * @return Map with "fields", "missing_fields", "confidence_notes"
     */
    public Map<String, Object> extractFieldsFromText(String extractedText, String fieldSchemaJson) {
        String prompt = """
                You are a document data-extraction engine. You will be given:
                1. The full text content of a legal document.
                2. A target field schema (JSON) describing what fields are required.

                Read and understand the document text fully. Extract values for every field in the
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
                
                --- Document text below ---
                %s
                """.formatted(fieldSchemaJson, extractedText);

        String response = callGroq(prompt);
        return parseExtractionResponse(response);
    }

    /**
     * Send a custom prompt to Groq with a custom system message.
     * Used by VerificationService and other non-extraction flows.
     * Forces JSON-object response mode — use this when the caller will parse the reply as JSON.
     *
     * @param systemMessage The system-level instruction for the model
     * @param userPrompt    The user message content
     * @return Raw response text from the model
     */
    public String sendPrompt(String systemMessage, String userPrompt) {
        return callGroqInternal(systemMessage, userPrompt, true);
    }

    private String callGroq(String prompt) {
        return callGroqInternal("You are a precise document processing engine. Always return valid JSON only.", prompt, true);
    }

    private String callGroqInternal(String systemMessage, String userMessage, boolean jsonMode) {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY environment variable is not set");
        }

        try {
            // Build the request body (OpenAI-compatible chat completions format)
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", groqModel);

            ArrayNode messages = requestBody.putArray("messages");

            // System message
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemMessage);

            // User message
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            // Response format (JSON object mode) — only when the caller wants structured JSON back
            if (jsonMode) {
                ObjectNode responseFormat = requestBody.putObject("response_format");
                responseFormat.put("type", "json_object");
            }

            // Generation config
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 4096);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            log.debug("Groq API request (truncated): system={}, prompt={}...",
                    systemMessage.substring(0, Math.min(100, systemMessage.length())),
                    userMessage.substring(0, Math.min(200, userMessage.length())));

            // Retry logic with exponential backoff (handles 429 rate limits)
            int maxRetries = 3;
            int baseDelayMs = 1000;
            IOException lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(groqApiUrl))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int statusCode = response.statusCode();
                    String responseBody = response.body();

                    log.debug("Groq API response status: {}", statusCode);

                    if (statusCode == 200) {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode choices = root.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode firstChoice = choices.get(0);
                            JsonNode message = firstChoice.get("message");
                            if (message != null) {
                                String text = message.get("content").asText();
                                // Clean markdown fences if present
                                text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                                return text;
                            }
                        }
                        throw new RuntimeException("Unexpected Groq response structure: " + responseBody);
                    } else if (statusCode == 429 && attempt < maxRetries) {
                        int delay = baseDelayMs * (int) Math.pow(2, attempt - 1);
                        log.warn("Groq API rate limited (429), retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                        continue;
                    } else {
                        throw new RuntimeException("Groq API returned status " + statusCode + ": " + responseBody);
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        int delay = baseDelayMs * (int) Math.pow(2, attempt - 1);
                        log.warn("Groq API request failed, retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                    }
                }
            }

            if (lastException != null) {
                throw new RuntimeException("Groq API request failed after " + maxRetries + " retries", lastException);
            }

            throw new RuntimeException("Groq API request failed after " + maxRetries + " retries");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Groq API request interrupted", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Groq API call failed", e);
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
            log.error("Failed to parse extraction response from Groq: {}", response, e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("fields", Map.of("extractedText", response));
            fallback.put("missing_fields", List.of());
            fallback.put("confidence_notes", "Raw response due to parsing failure");
            return fallback;
        }
    }
}
