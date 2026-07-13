// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayEventSecurityTest {
    @Test
    void dedicatedSubscribeUsesRuntimeAuthorizationAndAudit() {
        try (var runtime = new LodestoneRuntime(new AuthorizationPolicy(Set.of()))) {
            var gateway = initializedGateway(runtime, "caller-a");

            var result = call(gateway, "caller-a", "lodestone_events_subscribe", Map.of());

            assertTrue(result.get("isError").getAsBoolean());
            assertEquals("AUTHORIZATION_DENIED", result.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
            assertTrue(runtime.audit().stream().anyMatch(record ->
                    "minecraft.event.subscribe".equals(record.capability()) && "error".equals(record.outcome())));
        }
    }

    @Test
    void dedicatedSubscribeUsesCatalogRateLimit() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initializedGateway(runtime, "caller-a");

            for (var request = 0; request < 5; request++) {
                assertFalse(call(gateway, "caller-a", "lodestone_events_subscribe",
                        Map.of("eventPrefix", "minecraft." + request)).get("isError").getAsBoolean());
            }
            var limited = call(gateway, "caller-a", "lodestone_events_subscribe",
                    Map.of("eventPrefix", "minecraft.limited"));

            assertTrue(limited.get("isError").getAsBoolean());
            assertEquals("RATE_LIMIT_EXCEEDED", limited.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    void dedicatedEventLifecycleUsesCanonicalSchemaOwnershipAndAudit() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initializedGateway(runtime, "caller-a");
            initialize(gateway, "caller-b");

            var subscribed = call(gateway, "caller-a", "lodestone_events_subscribe",
                    Map.of("eventPrefix", "minecraft.", "bufferLimit", 10));
            assertFalse(subscribed.get("isError").getAsBoolean());
            var subscriptionId = subscribed.getAsJsonObject("structuredContent").get("id").getAsString();

            var foreign = call(gateway, "caller-b", "lodestone_events_poll",
                    Map.of("subscriptionId", subscriptionId));
            assertTrue(foreign.get("isError").getAsBoolean());

            var invalid = call(gateway, "caller-a", "lodestone_events_poll",
                    Map.of("subscriptionId", subscriptionId, "maxEvents", 1_001));
            assertTrue(invalid.get("isError").getAsBoolean());
            assertEquals("INVALID_INPUT", invalid.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());

            var polled = call(gateway, "caller-a", "lodestone_events_poll",
                    Map.of("subscriptionId", subscriptionId, "maxEvents", 10));
            assertFalse(polled.get("isError").getAsBoolean());
            assertTrue(polled.getAsJsonObject("structuredContent").getAsJsonArray("events").isEmpty());

            var removed = call(gateway, "caller-a", "lodestone_events_unsubscribe",
                    Map.of("subscriptionId", subscriptionId));
            assertFalse(removed.get("isError").getAsBoolean());
            assertTrue(removed.getAsJsonObject("structuredContent").get("removed").getAsBoolean());

            var audited = runtime.audit().stream().map(record -> record.capability()).collect(java.util.stream.Collectors.toSet());
            assertTrue(audited.containsAll(Set.of("minecraft.event.subscribe", "minecraft.event.poll",
                    "minecraft.event.unsubscribe")));
        }
    }

    @Test
    void dedicatedToolSchemasPublishCanonicalBoundsAndRequiredFields() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initializedGateway(runtime, "caller-a");
            var response = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", "caller-a"))
                    .getAsJsonObject().getAsJsonObject("result");
            var tools = response.getAsJsonArray("tools");
            var subscribe = findTool(tools, "lodestone_events_subscribe").getAsJsonObject("inputSchema");
            var poll = findTool(tools, "lodestone_events_poll").getAsJsonObject("inputSchema");
            var unsubscribe = findTool(tools, "lodestone_events_unsubscribe").getAsJsonObject("inputSchema");

            assertEquals(256, subscribe.getAsJsonObject("properties").getAsJsonObject("eventPrefix")
                    .get("maxLength").getAsInt());
            assertEquals(10_000, subscribe.getAsJsonObject("properties").getAsJsonObject("bufferLimit")
                    .get("maximum").getAsInt());
            assertEquals(1_000, poll.getAsJsonObject("properties").getAsJsonObject("maxEvents")
                    .get("maximum").getAsInt());
            assertTrue(unsubscribe.getAsJsonArray("required").toString().contains("subscriptionId"));
        }
    }

    @Test
    void dedicatedToolsPreserveRawArgumentsForCanonicalSchemaValidation() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initializedGateway(runtime, "caller-a");

            var extra = call(gateway, "caller-a", "lodestone_events_subscribe",
                    Map.of("unexpected", true));

            assertTrue(extra.get("isError").getAsBoolean());
            assertEquals("INVALID_INPUT", extra.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
        }
    }

    private static McpGateway initializedGateway(LodestoneRuntime runtime, String session) {
        var gateway = new McpGateway(runtime);
        initialize(gateway, session);
        return gateway;
    }

    private static void initialize(McpGateway gateway, String session) {
        gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\"}}", session);
    }

    private static JsonObject call(McpGateway gateway, String session, String tool, Map<String, Object> arguments) {
        return callResponse(gateway, session, tool, arguments).getAsJsonObject("result");
    }

    private static JsonObject callResponse(McpGateway gateway, String session, String tool,
                                           Map<String, Object> arguments) {
        var request = JsonSupport.MAPPER.toJson(Map.of(
                "jsonrpc", "2.0", "id", UUIDHolder.next(), "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
        return JsonParser.parseString(gateway.handle(request, session)).getAsJsonObject();
    }

    private static JsonObject findTool(com.google.gson.JsonArray tools, String name) {
        for (var tool : tools) {
            if (name.equals(tool.getAsJsonObject().get("name").getAsString())) {
                return tool.getAsJsonObject();
            }
        }
        throw new AssertionError("missing tool " + name);
    }

    private static final class UUIDHolder {
        private static int requestId;

        private static synchronized int next() {
            return ++requestId;
        }
    }
}
