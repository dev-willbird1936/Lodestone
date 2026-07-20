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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minecraft_goal} now delegates to {@link GoalOrchestratorLauncher} (a real, subprocess-
 * spawning realtime orchestrator), which does not yet support {@code mode=script}. Every test here
 * that exercises the call path uses a rejected request specifically so it stays a fast, offline,
 * non-live unit test - a call {@link GoalOrchestratorLauncher} would actually accept spawns a real
 * Python subprocess, which is exactly what these tests must not trigger.
 */
class McpGatewayGoalTest {
    @Test
    void publishesGoalToolsAndDescribesTheRealtimeOnlyOrchestratorHonestly() {
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
            assertTrue(listed.contains("suppressInGameMessages"));
            assertTrue(listed.contains("intelligence"));
            assertTrue(listed.contains("safety"));
            assertTrue(listed.contains("observation"));
            assertTrue(listed.contains("combatPolicy"));
            assertTrue(listed.contains("priority"));
            // Schema/description honesty: the realtime-only orchestrator and its rejected params are named.
            assertTrue(listed.contains("realtime"));
            assertTrue(listed.contains("Rejected"));
        }
    }

    @Test
    void rejectsScriptModeGoalCallsWithAClearJsonRpcError() {
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

            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_goal\",\"arguments\":{\"goal\":\"get a wooden axe and mine an entire tree\",\"mode\":\"script\"}}}"))
                    .getAsJsonObject();
            assertFalse(response.has("result"));
            var error = response.getAsJsonObject("error");
            assertEquals(-32602, error.get("code").getAsInt());
            assertTrue(error.get("message").getAsString().contains("mode=script"), error.get("message").getAsString());
        }
    }

    @Test
    void priorityFlagDoesNotBypassRealtimeOnlyValidation() {
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

            // priority=true must not skip requireOrchestratorSupported: an uncontended queue (nothing
            // else running or queued) still rejects a mode=script call the same way a plain one would.
            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_goal\",\"arguments\":{\"goal\":\"get a wooden axe and mine an entire tree\",\"mode\":\"script\",\"priority\":true}}}"))
                    .getAsJsonObject();
            assertFalse(response.has("result"));
            var error = response.getAsJsonObject("error");
            assertEquals(-32602, error.get("code").getAsInt());
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
