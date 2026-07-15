// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonParser;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayGoalTest {
    @Test
    void publishesGoalToolsAndReturnsStructuredUnsupportedResult() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = new AdapterDescriptor("goal.test.neoforge", "1.0.0", "minecraft-java", "1.21.1",
                    "neoforge", Environment.CLIENT);
            runtime.registerAdapter(new LodestoneAdapter() {
                @Override public AdapterDescriptor descriptor() { return descriptor; }
                @Override public CapabilityManifest manifest() { return new CapabilityManifest(descriptor, java.util.List.of()); }
                @Override public java.util.Map<String, dev.lodestone.adapter.CapabilityHandler> handlers() { return java.util.Map.of(); }
            });
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var listed = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            assertTrue(listed.contains("minecraft_goal"));
            assertTrue(listed.contains("minecraft_goal_benchmark"));

            var result = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_goal\",\"arguments\":{\"goal\":\"get a wooden axe and mine an entire tree\",\"mode\":\"script\"}}}"))
                    .getAsJsonObject().getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean());
            assertTrue(result.getAsJsonObject("structuredContent").get("status").getAsString().equals("UNSUPPORTED"));
        }
    }

    @Test
    void doesNotPublishGoalToolsOutsideNeoForge1211() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var listed = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            assertFalse(listed.contains("minecraft_goal"));
        }
    }
}
