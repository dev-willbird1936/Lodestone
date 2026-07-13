// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonParser;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayAuditResourceTest {
    @Test
    void auditResourcesOnlyExposeTheRequestingMcpSession() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            initialize(gateway, "client-a");
            initialize(gateway, "client-b");

            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                    + "\"name\":\"lodestone_capability_get\",\"arguments\":{\"capability\":\"lodestone.system.health\"}}}",
                    "client-a");
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                    + "\"name\":\"lodestone_capability_search\",\"arguments\":{\"query\":\"health\"}}}",
                    "client-b");

            var traceA = readResource(gateway, "client-a", "lodestone://audit/trace");
            assertTrue(traceA.contains("\"callerSessionId\":\"client-a\""));
            assertFalse(traceA.contains("client-b"));
            assertTrue(traceA.contains("lodestone.system.capabilities.get"));
            assertFalse(traceA.contains("lodestone.system.capabilities.search"));

            var auditB = readResource(gateway, "client-b", "lodestone://audit");
            assertTrue(auditB.contains("lodestone.system.capabilities.search"));
            assertFalse(auditB.contains("lodestone.system.capabilities.get"));
            assertFalse(auditB.contains("client-a"));
        }
    }

    private static void initialize(McpGateway gateway, String session) {
        gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\"}}", session);
    }

    private static String readResource(McpGateway gateway, String session, String uri) {
        var response = JsonParser.parseString(gateway.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\""
                        + uri + "\"}}", session)).getAsJsonObject();
        return response.getAsJsonObject("result").getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("text").getAsString();
    }
}
