// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonParser;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.CoreCatalog;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that Minecraft goals stay model-owned at the public MCP boundary. */
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
    void publishesOnlyModelComposedGoalHelpers() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            var initialized = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var listed = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            assertFalse(listed.contains("\"name\":\"minecraft_goal\""));
            assertFalse(listed.contains("\"name\":\"minecraft_goal_tasks\""));
            assertFalse(listed.contains("\"name\":\"minecraft_goal_benchmark\""));
            assertTrue(listed.contains("minecraft_subactions_execute"));
            assertTrue(listed.contains("minecraft_hook_create"));
            assertTrue(listed.contains("minecraft_hook_poll"));
            assertTrue(listed.contains("minecraft_hook_remove"));
            assertTrue(initialized.contains("current model"));
            assertTrue(initialized.contains("Native task routines are not exposed"));
        }
    }

    @Test
    void publishesEveryHardScriptAsAFirstClassAgentToolUsingCanonicalSchemas() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        var catalogHardScripts = capabilities.values().stream()
                .filter(capability -> capability.featureFlags().contains("hard-script"))
                .map(dev.lodestone.protocol.CapabilityDescriptor::id)
                .collect(Collectors.toSet());
        assertEquals(catalogHardScripts, McpGateway.hardScriptToolCapabilities());

        var toolCapabilities = Map.ofEntries(
                Map.entry("query_crosshair", "minecraft.player.crosshair.read"),
                Map.entry("find_block", "minecraft.world.block.find"),
                Map.entry("look_at_block", "minecraft.player.block.look-at"),
                Map.entry("mine_block", "minecraft.player.block.mine"),
                Map.entry("mine_target_block", "minecraft.player.target-block.mine"),
                Map.entry("select_item", "minecraft.inventory.hotbar.select-item"),
                Map.entry("place_block", "minecraft.player.block.place"),
                Map.entry("place_target_block", "minecraft.player.target-block.place"),
                Map.entry("cancel_current_script", "minecraft.script.current.cancel"),
                Map.entry("open_inventory", "minecraft.ui.inventory.open"),
                Map.entry("close_screen", "minecraft.ui.screen.close"));

        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var tools = JsonParser.parseString(gateway.handle(
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                    .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
            var schemas = new LinkedHashMap<String, com.google.gson.JsonElement>();
            tools.forEach(tool -> schemas.put(tool.getAsJsonObject().get("name").getAsString(),
                    tool.getAsJsonObject().get("inputSchema")));

            toolCapabilities.forEach((toolName, capabilityId) -> {
                assertTrue(schemas.containsKey(toolName), "missing first-class hard-script tool " + toolName);
                assertEquals(JsonSupport.MAPPER.toJsonTree(capabilities.get(capabilityId).inputSchema()),
                        schemas.get(toolName), "tool schema must come from canonical capability " + capabilityId);
            });
            assertTrue(schemas.containsKey("navigate_safe_waypoint"));
            assertTrue(schemas.containsKey("goto_position"));
            assertTrue(schemas.containsKey("collect_drops"));
            assertTrue(schemas.containsKey("chop_tree"));
            assertTrue(schemas.containsKey("attack_entity"));
        }
    }

    @Test
    void hidesNativeGoalRoutinesFromDiscovery() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var listed = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capabilities_list\",\"arguments\":{\"query\":\"minecraft.goal\"}}}");
            var searched = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_search\",\"arguments\":{\"query\":\"wooden axe\"}}}");
            assertFalse(listed.contains("minecraft.goal.survival.wooden-axe-tree"));
            assertFalse(searched.contains("minecraft.goal.survival.wooden-axe-tree"));
            assertTrue(listed.contains(GoalCapabilityPolicy.SAFE_WAYPOINT));
        }
    }

    @Test
    void hidesNativeGoalRoutinesFromCapabilityManifestResource() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var manifest = gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"lodestone://capabilities/manifest\"}}");
            assertFalse(manifest.contains("minecraft.goal.survival.wooden-axe-tree"));
            assertTrue(manifest.contains(GoalCapabilityPolicy.SAFE_WAYPOINT));
        }
    }

    @Test
    void rejectsNativeGoalRoutineGetAndInvoke() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var get = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_get\",\"arguments\":{\"capability\":\"minecraft.goal.survival.wooden-axe-tree\"}}}")).getAsJsonObject();
            var invoke = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_invoke\",\"arguments\":{\"capability\":\"minecraft.goal.survival.wooden-axe-tree\",\"input\":{}}}}"))
                    .getAsJsonObject();
            assertEquals(-32602, get.getAsJsonObject("error").get("code").getAsInt());
            assertEquals(-32602, invoke.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(invoke.getAsJsonObject("error").get("message").getAsString().contains("current agent"));
        }
    }

    @Test
    void rejectsUnfilteredSystemDiscoveryThroughGenericInvoke() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var capabilities = java.util.List.of(
                    "lodestone.system.handshake",
                    "lodestone.system.capabilities.list",
                    "lodestone.system.capabilities.get",
                    "lodestone.system.capabilities.search");

            for (var capability : capabilities) {
                var response = JsonParser.parseString(gateway.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_invoke\",\"arguments\":{\"capability\":\""
                                + capability + "\",\"input\":{}}}}"))
                        .getAsJsonObject();
                assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt(), capability);
                assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("filtered MCP"),
                        capability);
                assertFalse(response.toString().contains("minecraft.goal.survival.wooden-axe-tree"), capability);
            }
        }
    }

    @Test
    void rejectsNativeGoalRoutineInsideScriptBatch() {
        try (var runtime = neoForgeRuntime()) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_subactions_execute\",\"arguments\":{\"actions\":[{\"capability\":\"minecraft.goal.survival.wooden-axe-tree\"}]}}}"))
                    .getAsJsonObject();
            assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
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
