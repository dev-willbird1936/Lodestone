// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.protocol.JsonSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Optional OpenAI-compatible provider. It is enabled only when LODESTONE_GOAL_MODEL_URL is set. */
final class HttpJsonGoalModelProvider implements GoalModelProvider {
    private final String id;
    private final URI endpoint;
    private final String apiKey;
    private final long p95Ms;
    private final HttpClient client;
    private final Duration timeout;

    private HttpJsonGoalModelProvider(String id, URI endpoint, String apiKey, long p95Ms, Duration timeout) {
        this.id = id;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.p95Ms = p95Ms;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    static Optional<GoalModelProvider> fromEnvironment() {
        var url = System.getenv("LODESTONE_GOAL_MODEL_URL");
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            var p95 = longEnv("LODESTONE_GOAL_MODEL_P95_MS", 150);
            var timeoutMs = Math.max(100, longEnv("LODESTONE_GOAL_MODEL_TIMEOUT_MS", 1_500));
            return Optional.of(new HttpJsonGoalModelProvider(
                    env("LODESTONE_GOAL_MODEL_ID", GoalModelProviders.EXECUTOR_MODEL_ID), URI.create(url.trim()),
                    System.getenv("LODESTONE_GOAL_MODEL_API_KEY"), p95, Duration.ofMillis(timeoutMs)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long measuredP95LatencyMs() {
        return p95Ms;
    }

    @Override
    public boolean fallback() {
        return false;
    }

    @Override
    public Optional<GoalDecision> choose(GoalDecisionRequest request) {
        if (request.candidates().isEmpty()) return Optional.empty();
        var prompt = JsonSupport.MAPPER.toJson(Map.of(
                "goal", request.spec().goal(),
                "mode", request.spec().mode().toString(),
                "state", request.state(),
                "candidates", request.candidates().stream().map(step -> Map.of(
                        "id", step.id(), "kind", step.kind().toString(),
                        "capability", step.capability() == null ? "" : step.capability(),
                        "input", step.input())).toList(),
                "response", "Return JSON only: {candidateIndex: integer, rationale: string}."));
        var body = JsonSupport.MAPPER.toJson(Map.of(
                "model", GoalModelProviders.EXECUTOR_MODEL_ID,
                "temperature", 0,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", Map.of("type", "json_object")));
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            var root = JsonParser.parseString(response.body());
            var decision = root;
            if (root.isJsonObject() && root.getAsJsonObject().has("choices")) {
                var content = root.getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                decision = JsonParser.parseString(content);
            }
            var object = decision.getAsJsonObject();
            var index = object.has("candidateIndex") ? object.get("candidateIndex").getAsInt()
                    : object.get("candidate_index").getAsInt();
            if (index < 0 || index >= request.candidates().size()) return Optional.empty();
            return Optional.of(new GoalDecision(index,
                    object.has("rationale") ? object.get("rationale").getAsString() : "model selection"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long longEnv(String name, long fallback) {
        try {
            return Long.parseLong(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
