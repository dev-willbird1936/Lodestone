// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonNull;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.CoreCatalog;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayWorldEditTest {
    private static final String CAPABILITY = "minecraft.player.command.execute";
    private static final String UUID = "00000000-0000-0000-0000-000000000001";
    private static final Map<String, Object> PLAYER = Map.of("name", "Alex");

    @Test
    void listsFiveBoundedTypedDonorToolsWithoutAWorldEditOrCommandAdapter() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initialized(new McpGateway(runtime));
            var response = callMethod(gateway, "tools/list", Map.of()).getAsJsonObject("result");

            var tools = response.getAsJsonArray("tools");
            assertEquals(List.of("worldedit_selection", "worldedit_region", "worldedit_generation",
                            "worldedit_clipboard", "worldedit_history"),
                    List.of("worldedit_selection", "worldedit_region", "worldedit_generation",
                                    "worldedit_clipboard", "worldedit_history").stream()
                            .filter(name -> findTool(tools, name) != null).toList());

            var selection = findTool(tools, "worldedit_selection").getAsJsonObject("inputSchema");
            assertFalse(selection.getAsJsonObject("properties").has("command"));
            assertEquals(-30_000_000, selection.getAsJsonObject("properties").getAsJsonObject("x")
                    .get("minimum").getAsInt());
            assertEquals(30_000_000, selection.getAsJsonObject("properties").getAsJsonObject("z")
                    .get("maximum").getAsInt());
            assertEquals(2047, selection.getAsJsonObject("properties").getAsJsonObject("y")
                    .get("maximum").getAsInt());
            assertTrue(selection.get("additionalProperties").getAsBoolean() == false);
            assertTrue(selection.getAsJsonObject("properties").getAsJsonObject("player")
                    .get("additionalProperties").getAsBoolean() == false);

            var region = findTool(tools, "worldedit_region").getAsJsonObject("inputSchema");
            assertEquals(List.of("set", "replace", "stack"), strings(region.getAsJsonObject("properties")
                    .getAsJsonObject("action").getAsJsonArray("enum")));
            assertEquals(64, region.getAsJsonObject("properties").getAsJsonObject("count")
                    .get("maximum").getAsInt());
            assertTrue(region.has("oneOf"));

            var generationTool = findTool(tools, "worldedit_generation");
            assertTrue(generationTool.get("description").getAsString().contains("actor placement position"));
            var generation = generationTool.getAsJsonObject("inputSchema");
            assertEquals(64, generation.getAsJsonObject("properties").getAsJsonObject("radius")
                    .get("maximum").getAsInt());
            assertEquals(256, generation.getAsJsonObject("properties").getAsJsonObject("height")
                    .get("maximum").getAsInt());
            assertEquals(64, generation.getAsJsonObject("properties").getAsJsonObject("size")
                    .get("maximum").getAsInt());
            assertEquals(2000, generation.getAsJsonObject("properties").getAsJsonObject("capture")
                    .getAsJsonObject("properties").getAsJsonObject("windowMs").get("maximum").getAsInt());
        }
    }

    @Test
    void advertisedSchemasRejectCrossActionFieldsAndDuplicateBlockProperties() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initialized(new McpGateway(runtime));
            var tools = callMethod(gateway, "tools/list", Map.of()).getAsJsonObject("result")
                    .getAsJsonArray("tools");
            var region = findTool(tools, "worldedit_region").getAsJsonObject("inputSchema");
            var generation = findTool(tools, "worldedit_generation").getAsJsonObject("inputSchema");
            var clipboard = findTool(tools, "worldedit_clipboard").getAsJsonObject("inputSchema");

            assertSchemaAccepts(region,
                    args(PLAYER, "action", "set", "block", "minecraft:stone"));
            assertSchemaAccepts(region,
                    args(PLAYER, "action", "set",
                            "block", "minecraft:oak_log[axis=x,waterlogged=false]"));
            assertSchemaRejects(region,
                    args(PLAYER, "action", "set", "block", "minecraft:stone", "count", 1));
            assertSchemaRejects(region,
                    args(PLAYER, "action", "set", "block", "minecraft:oak_log[axis=x,axis=y]"));

            assertSchemaAccepts(generation,
                    args(PLAYER, "action", "sphere", "block", "minecraft:stone", "radius", 2,
                            "hollow", true));
            assertSchemaRejects(generation,
                    args(PLAYER, "action", "sphere", "block", "minecraft:stone", "radius", 2,
                            "height", 3));

            assertSchemaAccepts(clipboard,
                    args(PLAYER, "action", "copy", "include_entities", true));
            assertSchemaRejects(clipboard,
                    args(PLAYER, "action", "copy", "skip_air", true));
        }
    }

    @Test
    void selectionPreservesExactActorAndCaptureInCanonicalInput() {
        var player = new LinkedHashMap<String, Object>();
        player.put("uuid", UUID);
        player.put("name", "Alex");
        var capture = Map.<String, Object>of(
                "enabled", true, "windowMs", 500, "maxMessages", 12, "maxBytes", 4096);
        var arguments = args(player, "action", "pos1", "x", -30_000_000, "y", -2048,
                "z", 30_000_000, "capture", capture);

        try (var fixture = fixture(true)) {
            var result = callTool(fixture.gateway(), "worldedit_selection", arguments)
                    .getAsJsonObject("result");

            assertFalse(result.get("isError").getAsBoolean());
            assertEquals(player, fixture.input().get().get("player"));
            assertEquals("//pos1 -30000000,-2048,30000000", fixture.input().get().get("command"));
            var captured = object(fixture.input().get().get("capture"));
            assertEquals(true, captured.get("enabled"));
            assertEquals(500, ((Number) captured.get("windowMs")).intValue());
            assertEquals(12, ((Number) captured.get("maxMessages")).intValue());
            assertEquals(4096, ((Number) captured.get("maxBytes")).intValue());
            assertEquals(1, fixture.calls().get());
            assertEquals("//pos1 -30000000,-2048,30000000",
                    result.getAsJsonObject("structuredContent").getAsJsonObject("output")
                            .get("command").getAsString());
        }
    }

    @Test
    void mapsEveryBoundedRegionActionToOneDeterministicCommand() {
        assertCommand("worldedit_region", args(PLAYER, "action", "set", "block", "minecraft:stone"),
                "//set minecraft:stone");
        assertCommand("worldedit_region", args(PLAYER, "action", "replace", "from",
                "minecraft:oak_log[axis=y]", "to", "minecraft:stripped_oak_log[axis=y]"),
                "//replace minecraft:oak_log[axis=y] minecraft:stripped_oak_log[axis=y]");
        assertCommand("worldedit_region", args(PLAYER, "action", "stack", "count", 64,
                "direction", "west"), "//stack 64 west");
    }

    @Test
    void mapsFilledAndHollowGenerationAtActorPlacementPosition() {
        assertCommand("worldedit_generation", args(PLAYER, "action", "sphere", "block",
                "minecraft:stone", "radius", 1, "hollow", false), "//sphere minecraft:stone 1");
        assertCommand("worldedit_generation", args(PLAYER, "action", "sphere", "block",
                "minecraft:stone", "radius", 64, "hollow", true), "//hsphere minecraft:stone 64");
        assertCommand("worldedit_generation", args(PLAYER, "action", "cylinder", "block",
                "minecraft:glass", "radius", 3, "height", 256, "hollow", false),
                "//cyl minecraft:glass 3 256");
        assertCommand("worldedit_generation", args(PLAYER, "action", "cylinder", "block",
                "minecraft:glass", "radius", 3, "height", 4, "hollow", true),
                "//hcyl minecraft:glass 3 4");
        assertCommand("worldedit_generation", args(PLAYER, "action", "pyramid", "block",
                "minecraft:sandstone", "size", 64, "hollow", false),
                "//pyramid minecraft:sandstone 64");
        assertCommand("worldedit_generation", args(PLAYER, "action", "pyramid", "block",
                "minecraft:sandstone", "size", 2, "hollow", true),
                "//hpyramid minecraft:sandstone 2");
    }

    @Test
    void mapsClipboardFlagsAndActorLocalHistoryDeterministically() {
        assertCommand("worldedit_clipboard", args(PLAYER, "action", "copy"), "//copy");
        assertCommand("worldedit_clipboard", args(PLAYER, "action", "copy", "include_biomes", true,
                "include_entities", true), "//copy -be");
        assertCommand("worldedit_clipboard", args(PLAYER, "action", "paste"), "//paste");
        assertCommand("worldedit_clipboard", args(PLAYER, "action", "paste", "skip_air", true,
                "include_biomes", true, "include_entities", true, "original_position", true,
                "select", true), "//paste -abeos");
        assertCommand("worldedit_history", args(PLAYER, "action", "undo", "count", 1), "//undo 1");
        assertCommand("worldedit_history", args(PLAYER, "action", "redo", "count", 64), "//redo 64");
    }

    @Test
    void rejectsCommandInjectionAndUnsupportedWorldEditGrammarBeforeCanonicalDispatch() {
        var invalidBlocks = List.of(
                "stone",
                "50%minecraft:stone,50%minecraft:dirt",
                "#existing",
                "minecraft:chest{Items:[]}",
                "minecraft:stone //op Alex",
                "minecraft:stone\n//op Alex",
                "minecraft:stone\u0000",
                "minecraft:oak_log[axis=x,axis=y]");
        for (var block : invalidBlocks) {
            assertRejected("worldedit_region", args(PLAYER, "action", "set", "block", block));
        }
        assertRejected("worldedit_region", args(Map.of("name", "Alex\n//op"), "action", "set",
                "block", "minecraft:stone"));
        assertRejected("worldedit_region", args(Map.of("uuid", "1-1-1-1-1"), "action", "set",
                "block", "minecraft:stone"));
        assertRejected("worldedit_region", args(PLAYER, "action", "set", "block", "minecraft:stone",
                "command", "//op Alex"));
    }

    @Test
    void rejectsUnknownNestedFieldsWrongTypesBoundsAndActionLeakageBeforeDispatch() {
        assertRejected("worldedit_selection", args(Map.of("name", "Alex", "selector", "@a"),
                "action", "pos1", "x", 0, "y", 64, "z", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", 1.5,
                "y", 64, "z", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", "1",
                "y", 64, "z", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", new BigInteger("2147483648"),
                "y", 64, "z", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", 30_000_001,
                "y", 64, "z", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", 0,
                "y", 2048, "z", 0));
        assertRejected("worldedit_region", args(PLAYER, "action", "stack", "count", 65,
                "direction", "up"));
        assertRejected("worldedit_region", args(PLAYER, "action", "stack", "count", 1,
                "direction", "forward"));
        assertRejected("worldedit_region", args(PLAYER, "action", "set", "block", "minecraft:stone",
                "count", 1));
        assertRejected("worldedit_generation", args(PLAYER, "action", "sphere", "block", "minecraft:stone",
                "radius", 65));
        assertRejected("worldedit_generation", args(PLAYER, "action", "cylinder", "block", "minecraft:stone",
                "radius", 1, "height", 257));
        assertRejected("worldedit_generation", args(PLAYER, "action", "pyramid", "block", "minecraft:stone",
                "size", 65));
        assertRejected("worldedit_generation", args(PLAYER, "action", "sphere", "block", "minecraft:stone",
                "radius", 1, "hollow", "true"));
        assertRejected("worldedit_history", args(PLAYER, "action", "undo", "count", 0));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", 0, "y", 64,
                "z", 0, "capture", Map.of("enabled", true, "extra", 1)));
        assertRejected("worldedit_selection", args(PLAYER, "action", "pos1", "x", 0, "y", 64,
                "z", 0, "capture", Map.of("windowMs", 2001)));

        var nullBoolean = JsonSupport.MAPPER.toJsonTree(args(PLAYER, "action", "sphere", "block",
                "minecraft:stone", "radius", 1)).getAsJsonObject();
        nullBoolean.add("hollow", JsonNull.INSTANCE);
        assertRejected("worldedit_generation", nullBoolean);
    }

    @Test
    void absentAdapterAndDeniedCallerRetainCanonicalAvailabilityAuthorizationAndAudit() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initialized(new McpGateway(runtime));
            var result = callTool(gateway, "worldedit_selection",
                    args(PLAYER, "action", "pos2", "x", 0, "y", 64, "z", 0)).getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            assertEquals("CAPABILITY_UNAVAILABLE", result.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
        }

        try (var fixture = fixture(false)) {
            var result = callTool(fixture.gateway(), "worldedit_selection",
                    args(PLAYER, "action", "pos2", "x", 0, "y", 64, "z", 0)).getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            assertEquals("AUTHORIZATION_DENIED", result.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
            assertEquals(0, fixture.calls().get());
            assertTrue(fixture.runtime().audit().stream().anyMatch(record -> CAPABILITY.equals(record.capability())));
        }
    }

    private static void assertCommand(String tool, Map<String, Object> arguments, String expected) {
        try (var fixture = fixture(true)) {
            var result = callTool(fixture.gateway(), tool, arguments).getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean(), result.toString());
            assertEquals(expected, fixture.input().get().get("command"));
            assertEquals(CAPABILITY, fixture.runtime().audit().get(fixture.runtime().audit().size() - 1).capability());
            assertEquals(1, fixture.calls().get());
        }
    }

    private static void assertRejected(String tool, Map<String, Object> arguments) {
        assertRejected(tool, JsonSupport.MAPPER.toJsonTree(arguments).getAsJsonObject());
    }

    private static void assertRejected(String tool, JsonObject arguments) {
        try (var fixture = fixture(true)) {
            var response = callTool(fixture.gateway(), tool, arguments);
            assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt(), response.toString());
            assertEquals(0, fixture.calls().get());
            assertNull(fixture.input().get());
        }
    }

    private static Fixture fixture(boolean callerAuthorized) {
        var input = new AtomicReference<Map<String, Object>>();
        var calls = new AtomicInteger();
        var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("administer-server"));
        var descriptor = new AdapterDescriptor("lodestone.gateway.worldedit-test", "1.0.0",
                "minecraft", "test", "test", Environment.REMOTE);
        var catalogCapability = CoreCatalog.load().stream()
                .filter(candidate -> CAPABILITY.equals(candidate.id())).findFirst().orElseThrow();
        var capability = catalogCapability.forAdapter(descriptor, Availability.RESTRICTED,
                catalogCapability.reason());
        CapabilityHandler handler = context -> {
            calls.incrementAndGet();
            input.set(context.request().input());
            context.cancellation().commitMutation();
            return CompletableFuture.completedFuture(successOutput(context.request().input()));
        };
        runtime.registerAdapter(new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(descriptor, List.of(capability));
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return Map.of(CAPABILITY, handler);
            }
        });
        var gateway = initialized(new McpGateway(runtime, ignored -> callerAuthorized
                ? AuthorizationPolicy.fromCsv("administer-server") : AuthorizationPolicy.observeOnly()));
        return new Fixture(runtime, gateway, input, calls);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> successOutput(Map<String, Object> input) {
        var player = (Map<String, Object>) input.get("player");
        var actor = new LinkedHashMap<String, Object>();
        actor.put("uuid", player.getOrDefault("uuid", UUID));
        actor.put("name", player.getOrDefault("name", "Alex"));
        var output = new LinkedHashMap<String, Object>();
        output.put("actor", Map.copyOf(actor));
        output.put("command", input.get("command"));
        output.put("dispatched", true);
        output.put("certainty", "dispatch-confirmed");
        output.put("result", 1);
        output.put("messages", List.of());
        output.put("capture", Map.of("complete", false, "truncated", false,
                "windowMs", 0, "maxMessages", 0, "maxBytes", 0));
        return Map.copyOf(output);
    }

    private static McpGateway initialized(McpGateway gateway) {
        var response = callMethod(gateway, "initialize", Map.of("protocolVersion", "2025-11-25"));
        assertNotNull(response.getAsJsonObject("result"));
        return gateway;
    }

    private static JsonObject callTool(McpGateway gateway, String name, Map<String, Object> arguments) {
        return callTool(gateway, name, JsonSupport.MAPPER.toJsonTree(arguments).getAsJsonObject());
    }

    private static JsonObject callTool(McpGateway gateway, String name, JsonObject arguments) {
        var params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return callMethod(gateway, "tools/call", params);
    }

    private static JsonObject callMethod(McpGateway gateway, String method, Map<String, Object> params) {
        return callMethod(gateway, method, JsonSupport.MAPPER.toJsonTree(params).getAsJsonObject());
    }

    private static JsonObject callMethod(McpGateway gateway, String method, JsonObject params) {
        var request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        return JsonParser.parseString(gateway.handle(request.toString())).getAsJsonObject();
    }

    private static JsonObject findTool(com.google.gson.JsonArray tools, String name) {
        for (var tool : tools) {
            if (name.equals(tool.getAsJsonObject().get("name").getAsString())) {
                return tool.getAsJsonObject();
            }
        }
        throw new AssertionError("missing tool " + name);
    }

    private static List<String> strings(com.google.gson.JsonArray values) {
        var result = new ArrayList<String>();
        values.forEach(value -> result.add(value.getAsString()));
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaMap(JsonObject schema) {
        var validationSchema = schema.deepCopy();
        removeSchemaAnnotations(validationSchema);
        return JsonSupport.MAPPER.fromJson(validationSchema, Map.class);
    }

    private static void removeSchemaAnnotations(JsonElement schema) {
        if (schema.isJsonObject()) {
            var object = schema.getAsJsonObject();
            object.remove("description");
            object.remove("default");
            object.entrySet().forEach(entry -> removeSchemaAnnotations(entry.getValue()));
        } else if (schema.isJsonArray()) {
            schema.getAsJsonArray().forEach(McpGatewayWorldEditTest::removeSchemaAnnotations);
        }
    }

    private static void assertSchemaAccepts(JsonObject schema, Map<String, Object> input) {
        var errors = SchemaValidator.validate(schemaMap(schema), input);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private static void assertSchemaRejects(JsonObject schema, Map<String, Object> input) {
        var errors = SchemaValidator.validate(schemaMap(schema), input);
        assertFalse(errors.isEmpty(), "advertised schema accepted runtime-invalid input: " + input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> args(Map<String, Object> player, Object... values) {
        var result = new LinkedHashMap<String, Object>();
        result.put("player", player);
        for (var index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record Fixture(LodestoneRuntime runtime, McpGateway gateway,
                           AtomicReference<Map<String, Object>> input, AtomicInteger calls)
            implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
