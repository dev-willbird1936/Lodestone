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
 * spawning script/realtime orchestrator). Every test here
 * that exercises that call path uses another rejected request so it stays a fast, offline,
 * non-live unit test - a call {@link GoalOrchestratorLauncher} would actually accept spawns a real
 * Python subprocess, which is exactly what these tests must not trigger.
 */
class McpGatewayGoalTest {
    private static LodestoneRuntime neoForgeRuntime() {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly());
        var descriptor = new AdapterDescriptor("goal.test.neoforge", "1.0.0", "minecraft-java", "1.21.1",
                "neoforge", Environment.CLIENT);
        runtime.registerAdapter(new LodestoneAdapter() {
            @Override public AdapterDescriptor descriptor() { return descriptor; }
            @Override public CapabilityManifest manifest() { return new CapabilityManifest(descriptor, java.util.List.of()); }
            @Override public java.util.Map<String, dev.lodestone.adapter.CapabilityHandler> handlers() { return java.util.Map.of(); }
        });
        return runtime;
    }

    @Test
    void publishesGoalToolsAndBothExecutionModes() {
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
            assertTrue(listed.contains("minecraft_subactions_execute"));
            assertTrue(listed.contains("minecraft_hook_create"));
            assertTrue(listed.contains("minecraft_hook_poll"));
            assertTrue(listed.contains("minecraft_hook_remove"));
            assertTrue(listed.contains("suppressInGameMessages"));
            assertTrue(listed.contains("intelligence"));
            assertTrue(listed.contains("safety"));
            assertTrue(listed.contains("observation"));
            assertTrue(listed.contains("combatPolicy"));
            assertTrue(listed.contains("priority"));
            assertTrue(listed.contains("script"));
            assertTrue(listed.contains("realtime"));
            assertTrue(listed.contains("[\"low\",\"medium\",\"high\"]"));
        }
    }

    @Test
    void scriptModeStillRejectsUnsupportedDryRunBeforeLaunching() {
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

            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_goal\",\"arguments\":{\"goal\":\"get a wooden axe and mine an entire tree\",\"mode\":\"script\",\"dryRun\":true}}}"))
                    .getAsJsonObject();
            assertFalse(response.has("result"));
            var error = response.getAsJsonObject("error");
            assertEquals(-32602, error.get("code").getAsInt());
            assertTrue(error.get("message").getAsString().contains("dryRun"), error.get("message").getAsString());
        }
    }

    @Test
    void priorityFlagDoesNotBypassUnsupportedParameterValidation() {
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

            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_goal\",\"arguments\":{\"goal\":\"get a wooden axe and mine an entire tree\",\"mode\":\"script\",\"dryRun\":true,\"priority\":true}}}"))
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

    @Test
    void createsSessionOwnedInventoryHookAndPollsWithFreshEvidence() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}", "client-a");
            var created = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_hook_create\",\"arguments\":{\"type\":\"inventory-item-at-least\",\"item\":\"minecraft:flint\",\"count\":1}}}",
                    "client-a")).getAsJsonObject().getAsJsonObject("result")
                    .getAsJsonObject("structuredContent");
            assertEquals("minecraft:flint", created.get("item").getAsString());
            assertFalse(created.get("fired").getAsBoolean());

            var hookId = created.get("hookId").getAsString();
            var polled = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_hook_poll\",\"arguments\":{\"hookId\":\"" + hookId + "\"}}}",
                    "client-a")).getAsJsonObject().getAsJsonObject("result")
                    .getAsJsonObject("structuredContent");
            assertFalse(polled.get("pollSucceeded").getAsBoolean());
        }
    }

    @Test
    void scriptBatchStopsOnFirstUnavailableSubaction() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}", "client-a");
            var output = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_subactions_execute\",\"arguments\":{\"actions\":[{\"capability\":\"minecraft.player.state.read\"},{\"capability\":\"minecraft.inventory.read\"}]}}}",
                    "client-a")).getAsJsonObject().getAsJsonObject("result")
                    .getAsJsonObject("structuredContent");
            assertEquals(2, output.get("requested").getAsInt());
            assertEquals(1, output.get("executed").getAsInt());
            assertFalse(output.get("complete").getAsBoolean());
        }
    }
}
