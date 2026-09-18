package energy.optimizer.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import energy.optimizer.DTOs.OptimizationDtos.DirectiveInterpretation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class LlmInterpreterService {
    @Value("${LLM_API_KEY}")
    private String apiKey;

    @Value("${LLM_API_URL}")
    private String apiUrl;

    @Value("${LLM_MODEL}")
    private String modelName;

    // Increased timeout to 45 seconds to accommodate LLM generation time
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DirectiveInterpretation> interpretNotes(List<String> notes) {
        if (notes == null || notes.isEmpty()) return new ArrayList<>();

        // Pass the entire list of notes to a single LLM call
        return callLlmForAllNotes(notes);
    }

    private List<DirectiveInterpretation> callLlmForAllNotes(List<String> notes) {
        StringBuilder notesBuilder = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            notesBuilder.append(i).append(". ").append(notes.get(i)).append("\n");
        }

        String systemPrompt = """
        You are a power grid operator assistant. Analyze the given numbered notes and convert each into a structured JSON directive.
        Allowed directive_type values:
        1. "solar_reduction": {"hours": [int], "factor": double (0.0 to 1.0)}
        2. "minimum_battery_reserve": {"hours": [int], "minimum_energy_kwh": double}
        3. "no_charge_window": {"hours": [int]}
        4. "no_discharge_window": {"hours": [int]}
        5. "max_grid_window": {"hours": [int], "max_grid_kwh": double}
        6. "no_op": null

        Time Windows: 1 PM to 3 PM means hours [13, 14]. Whole-hour convention (start-inclusive, end-exclusive).
        For irrelevant notes, set applies=false, directive_type="no_op", and structured_adjustment=null.
        For relevant notes, set applies=true.

        Return a JSON object with a single key "interpretations" containing an array of objects, one for each note in order.
        Example format:
        {
          "interpretations": [
            { "note_index": 0, "applies": true, "directive_type": "solar_reduction", "structured_adjustment": {"hours": [13, 14], "factor": 0.2}, "explanation": "..." },
            { "note_index": 1, "applies": false, "directive_type": "no_op", "structured_adjustment": null, "explanation": "..." }
          ]
        }
        """;

        // Retry logic: up to 3 attempts with exponential backoff
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> reqBody = Map.of(
                        "model", modelName,
                        "response_format", Map.of("type", "json_object"),
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", "Operator Notes:\n" + notesBuilder.toString())
                        ),
                        "temperature", 0.0
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reqBody)))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                log.info("Attempt {}/{}: Sending LLM request with model {}", attempt, maxAttempts, modelName);
                long start = System.currentTimeMillis();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                long duration = System.currentTimeMillis() - start;
                log.info("Attempt {}/{}: LLM responded in {} ms with status {}",
                        attempt, maxAttempts, duration, response.statusCode());

                if (response.statusCode() == 200) {
                    // Parse the successful response (same logic as before)
                    JsonNode root = objectMapper.readTree(response.body());
                    String content = root.path("choices").get(0).path("message").path("content").asText();

                    content = content.trim();
                    if (content.startsWith("```json")) content = content.substring(7);
                    if (content.endsWith("```")) content = content.substring(0, content.length() - 3);

                    JsonNode parsedJson = objectMapper.readTree(content.trim());
                    JsonNode interpretationsNode = parsedJson.path("interpretations");

                    List<DirectiveInterpretation> results = new ArrayList<>();
                    if (interpretationsNode.isArray()) {
                        for (JsonNode node : interpretationsNode) {
                            int index = node.path("note_index").asInt(0);
                            boolean applies = node.path("applies").asBoolean(false);
                            String type = node.path("directive_type").asText("no_op");
                            JsonNode adjNode = node.get("structured_adjustment");
                            Map<String, Object> adjMap = (adjNode != null && !adjNode.isNull())
                                    ? objectMapper.convertValue(adjNode, Map.class) : null;
                            String explanation = node.path("explanation").asText("Interpreted operator note.");

                            results.add(applyGuardrails(index,
                                    new DirectiveInterpretation(index, applies, type, adjMap, explanation)));
                        }
                    }
                    while (results.size() < notes.size()) {
                        results.add(new DirectiveInterpretation(results.size(), false, "no_op", null,
                                "Fallback due to incomplete LLM response."));
                    }
                    return results;
                }
                else if (response.statusCode() == 503 || response.statusCode() == 429) {
                    // Temporary errors — retry
                    log.warn("Attempt {}/{}: Temporary error {} — will retry",
                            attempt, maxAttempts, response.statusCode());
                    if (attempt < maxAttempts) {
                        long backoffMs = (long) Math.pow(2, attempt) * 1000L; // 2s, 4s
                        log.info("Waiting {} ms before retry...", backoffMs);
                        Thread.sleep(backoffMs);
                        continue;
                    }
                }
                else {
                    // Non-retryable error (400, 401, 404)
                    log.error("Attempt {}/{}: Non-retryable error {}: {}",
                            attempt, maxAttempts, response.statusCode(), response.body());
                    break;
                }

            } catch (Exception e) {
                log.error("Attempt {}/{}: Exception during LLM call", attempt, maxAttempts, e);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        // All attempts failed — return safe fallback
        log.warn("All {} attempts failed. Returning safe fallback.", maxAttempts);
        List<DirectiveInterpretation> fallback = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            fallback.add(new DirectiveInterpretation(i, false, "no_op", null,
                    "Default fallback due to LLM provider unavailability."));
        }
        return fallback;
    }

    private DirectiveInterpretation applyGuardrails(int index, DirectiveInterpretation raw) {
        Set<String> validTypes = Set.of("solar_reduction", "minimum_battery_reserve",
                "no_charge_window", "no_discharge_window", "max_grid_window", "no_op");

        if (!validTypes.contains(raw.directiveType()) || "no_op".equals(raw.directiveType()) || !raw.applies()) {
            return new DirectiveInterpretation(index, false, "no_op", null,
                    raw.explanation() != null ? raw.explanation() : "Marked as no_op.");
        }

        Map<String, Object> adj = raw.structuredAdjustment();
        if (adj == null) {
            return new DirectiveInterpretation(index, false, "no_op", null, "Invalid adjustment structure.");
        }

        // Safely parse hours (Fix for Double -> Integer cast issue)
        List<?> rawHours = (List<?>) adj.get("hours");
        if (rawHours == null || rawHours.isEmpty()) {
            return new DirectiveInterpretation(index, false, "no_op", null, "Missing or empty hours array.");
        }

        List<Integer> cleanHours = rawHours.stream()
                .map(obj -> ((Number) obj).intValue()) // Safely convert Double/Integer to int
                .filter(h -> h >= 0 && h <= 23)
                .distinct()
                .sorted()
                .toList();

        Map<String, Object> cleanAdj = new HashMap<>(adj);
        cleanAdj.put("hours", cleanHours);

        // Directive-specific numeric validation
        switch (raw.directiveType()) {
            case "solar_reduction" -> {
                double factor = ((Number) adj.getOrDefault("factor", 1.0)).doubleValue();
                cleanAdj.put("factor", Math.max(0.0, Math.min(1.0, factor)));
            }
            case "minimum_battery_reserve" -> {
                double reserve = ((Number) adj.getOrDefault("minimum_energy_kwh", 0.0)).doubleValue();
                cleanAdj.put("minimum_energy_kwh", Math.max(0.0, reserve));
            }
            case "max_grid_window" -> {
                double maxGrid = ((Number) adj.getOrDefault("max_grid_kwh", 0.0)).doubleValue();
                cleanAdj.put("max_grid_kwh", Math.max(0.0, maxGrid));
            }
        }

        return new DirectiveInterpretation(index, true, raw.directiveType(), cleanAdj, raw.explanation());
    }
}