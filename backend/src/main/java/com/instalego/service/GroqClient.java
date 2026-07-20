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

    @Value("${app.groq-vision-model}")
    private String groqVisionModel;

    // Some Groq accounts/tiers cap vision models at a very low tokens-per-minute budget (observed:
    // 8000 TPM on qwen3.6-27b for a free/on-demand tier) — batching multiple page images into one
    // request multiplies image-token cost and blows through that fast, so pages are sent one at a
    // time rather than in batches of 5.
    private static final int MAX_IMAGES_PER_REQUEST = 1;

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

    /**
     * Read a bank's sample verification report and describe its structure as a numbered list of
     * sections — the same plain-text shape as the built-in default structure — so it can be
     * dropped directly into the verification prompt as a "follow this structure" instruction.
     */
    public String deriveReportStructure(String sampleReportText) {
        String systemMessage = "You are a document structure analyst. Read the sample legal/verification " +
                "report below and describe its structure as a numbered list of sections (what each section " +
                "covers, in the order it appears). Do not include the sample's actual data/findings — only " +
                "the structural outline, written so it can be handed to another analyst as instructions for " +
                "producing a new report in the same shape.";

        String userPrompt = "SAMPLE REPORT:\n" + sampleReportText;

        return callGroqInternal(systemMessage, userPrompt, false).trim();
    }

    /**
     * Transcribe visible text from a set of page images using Groq's open-weight vision model
     * (meta-llama/llama-4-scout by default). Used as the OCR fallback for scanned/image-only
     * documents where PDFBox/Tika found no embedded text layer.
     *
     * Groq's vision endpoint accepts at most {@value #MAX_IMAGES_PER_REQUEST} images per request,
     * so multi-page documents are sent in batches and the transcriptions concatenated in order.
     *
     * @param jpegImages JPEG-encoded page images, in reading order
     * @return The full transcribed text across all pages
     */
    public String transcribeImages(List<byte[]> jpegImages) {
        StringBuilder result = new StringBuilder();
        for (int start = 0; start < jpegImages.size(); start += MAX_IMAGES_PER_REQUEST) {
            List<byte[]> batch = jpegImages.subList(start, Math.min(start + MAX_IMAGES_PER_REQUEST, jpegImages.size()));
            String batchText = callGroqVision(batch);
            if (batchText != null && !batchText.isBlank()) {
                if (result.length() > 0) result.append("\n\n");
                result.append(batchText);
            }
        }
        return result.toString();
    }

    /**
     * Reasoning models (e.g. Qwen) may emit hidden chain-of-thought inside a {@code <think>...
     * </think>} block ahead of the actual answer, even when instructed not to. Strip it
     * defensively so it never ends up mixed into transcribed document text.
     */
    private String stripReasoning(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private static final java.util.regex.Pattern RETRY_AFTER_PATTERN =
            java.util.regex.Pattern.compile("try again in ([0-9.]+)s", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Groq's 429 responses include the exact wait time needed (e.g. "Please try again in
     * 1.1775s") — honor that instead of blind exponential backoff, since a fixed 1s/2s schedule
     * can either under-wait (still gets rejected) or over-wait relative to what's actually needed.
     * Falls back to exponential backoff if the hint isn't present.
     */
    private long resolveRetryDelayMs(String responseBody, int attempt, int baseDelayMs) {
        var matcher = RETRY_AFTER_PATTERN.matcher(responseBody);
        if (matcher.find()) {
            double seconds = Double.parseDouble(matcher.group(1));
            return (long) (seconds * 1000) + 300; // small buffer for latency/clock skew
        }
        return baseDelayMs * (long) Math.pow(2, attempt - 1);
    }

    private String callGroqVision(List<byte[]> jpegBatch) {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY environment variable is not set");
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", groqVisionModel);

            ArrayNode messages = requestBody.putArray("messages");

            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are a precise OCR/transcription engine. Transcribe ALL visible text " +
                    "exactly as it appears, preserving paragraph structure. Do not summarize, translate, or " +
                    "add commentary — output only the transcribed text. If a page has no readable text, skip it. " +
                    "Do not show your reasoning or use <think> tags — respond with the transcription only.");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", "Transcribe the visible text from the following page image(s), in order.");

            for (byte[] jpeg : jpegBatch) {
                ObjectNode imagePart = content.addObject();
                imagePart.put("type", "image_url");
                ObjectNode imageUrl = imagePart.putObject("image_url");
                imageUrl.put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
            }

            requestBody.put("temperature", 0.1);
            // Kept modest to fit within restrictive per-account TPM budgets on vision models (see
            // MAX_IMAGES_PER_REQUEST) — still enough for one page's transcription plus some
            // hidden reasoning overhead on models like Qwen.
            requestBody.put("max_tokens", 2000);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            int maxRetries = 5;
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

                    if (statusCode == 200) {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode choices = root.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode message = choices.get(0).get("message");
                            if (message != null && message.has("content")) {
                                return stripReasoning(message.get("content").asText()).trim();
                            }
                        }
                        throw new RuntimeException("Unexpected Groq vision response structure: " + responseBody);
                    } else if (statusCode == 429 && attempt < maxRetries) {
                        long delay = resolveRetryDelayMs(responseBody, attempt, baseDelayMs);
                        log.warn("Groq vision API rate limited (429), retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                        continue;
                    } else {
                        throw new RuntimeException("Groq vision API returned status " + statusCode + ": " + responseBody);
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        long delay = baseDelayMs * (long) Math.pow(2, attempt - 1);
                        log.warn("Groq vision API request failed, retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                    }
                }
            }

            if (lastException != null) {
                throw new RuntimeException("Groq vision API request failed after " + maxRetries + " retries", lastException);
            }
            throw new RuntimeException("Groq vision API request failed after " + maxRetries + " retries");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Groq vision API request interrupted", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Groq vision API call failed", e);
        }
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

            // Retry logic honoring Groq's suggested wait time on 429s (falls back to exponential
            // backoff if the response doesn't include one)
            int maxRetries = 5;
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
                        long delay = resolveRetryDelayMs(responseBody, attempt, baseDelayMs);
                        log.warn("Groq API rate limited (429), retrying in {}ms (attempt {}/{})", delay, attempt, maxRetries);
                        Thread.sleep(delay);
                        continue;
                    } else {
                        throw new RuntimeException("Groq API returned status " + statusCode + ": " + responseBody);
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        long delay = baseDelayMs * (long) Math.pow(2, attempt - 1);
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
