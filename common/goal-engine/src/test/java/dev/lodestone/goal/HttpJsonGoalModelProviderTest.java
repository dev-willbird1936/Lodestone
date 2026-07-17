// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the LODESTONE_GOAL_MODEL_REASONING_EFFORT=xhigh bug fix and the DELIBERATE_V1 situational
 * deliberation budget (per-request reasoning effort/timeout selection) in HttpJsonGoalModelProvider.
 */
final class HttpJsonGoalModelProviderTest {
    @Test
    void xhighReasoningEffortIsNowAcceptedInsteadOfSilentlyDowngradedToLow() throws Exception {
        var method = HttpJsonGoalModelProvider.class.getDeclaredMethod("isValidReasoningEffort", String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null, "xhigh"));
        assertTrue((boolean) method.invoke(null, "low"));
        assertTrue((boolean) method.invoke(null, "medium"));
        assertTrue((boolean) method.invoke(null, "high"));
        assertFalse((boolean) method.invoke(null, "not-a-real-effort"));
    }

    @Test
    void deliberateTierRequestsWidenedEffortOnlyWhenCurrentlySafe() throws IOException {
        var capturedBody = new AtomicReference<String>();
        var server = startCapturingServer(capturedBody);
        try {
            var provider = new HttpJsonGoalModelProvider("test-model",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                    null, 100, "low", Duration.ofSeconds(2));
            var candidate = GoalStep.observe("probe", "test.observe", Map.of());

            var deliberateSafeSpec = new GoalSpec("test goal", GoalMode.REALTIME, null, 10, 10_000, false, null,
                    false, GoalIntelligence.DELIBERATE_V1, GoalSafety.BALANCED);
            var safeDecision = provider.choose(new GoalDecisionRequest(deliberateSafeSpec, safeState(), List.of(candidate)));
            assertTrue(safeDecision.isPresent());
            assertEquals("xhigh", safeDecision.get().reasoningEffort());
            assertEquals("xhigh", capturedReasoningEffort(capturedBody));

            var hazardousDecision = provider.choose(
                    new GoalDecisionRequest(deliberateSafeSpec, hazardousState(), List.of(candidate)));
            assertTrue(hazardousDecision.isPresent());
            assertEquals("low", hazardousDecision.get().reasoningEffort());
            assertEquals("low", capturedReasoningEffort(capturedBody));

            var adaptiveSpec = new GoalSpec("test goal", GoalMode.REALTIME, null, 10, 10_000, false, null,
                    false, GoalIntelligence.ADAPTIVE_V1, GoalSafety.BALANCED);
            var adaptiveDecision = provider.choose(new GoalDecisionRequest(adaptiveSpec, safeState(), List.of(candidate)));
            assertTrue(adaptiveDecision.isPresent());
            assertEquals("low", adaptiveDecision.get().reasoningEffort());
            assertEquals("low", capturedReasoningEffort(capturedBody));
        } finally {
            server.stop(0);
        }
    }

    private static String capturedReasoningEffort(AtomicReference<String> capturedBody) {
        return JsonParser.parseString(capturedBody.get()).getAsJsonObject().get("reasoning_effort").getAsString();
    }

    private static Map<String, Object> safeState() {
        return Map.of("steps", Map.of("postObserve", Map.of("action", Map.of(
                "player", Map.of("onFire", false, "inLava", false, "inWater", false, "onGround", true,
                        "fallDistance", 0.0, "health", 20.0, "maxHealth", 20.0),
                "nearbyThreats", List.of(),
                "localNavigation", Map.of("forwardDropRisk", false)))));
    }

    private static Map<String, Object> hazardousState() {
        return Map.of("steps", Map.of("postObserve", Map.of("action", Map.of(
                "player", Map.of("onFire", true, "inLava", false, "inWater", false, "onGround", true,
                        "fallDistance", 0.0, "health", 20.0, "maxHealth", 20.0),
                "nearbyThreats", List.of(),
                "localNavigation", Map.of("forwardDropRisk", false)))));
    }

    private static HttpServer startCapturingServer(AtomicReference<String> lastBody) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (var input = exchange.getRequestBody()) {
                lastBody.set(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            var response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"candidateIndex\\\":0,\\\"rationale\\\":\\\"ok\\\"}\"}}]}";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
