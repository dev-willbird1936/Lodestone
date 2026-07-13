// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.CoreCatalog;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayScreenshotTest {
    private static final String CAPABILITY = "minecraft.client.screenshot.capture";
    private static final String CURRENT_MCP = "2025-11-25";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG);

    @Test
    void modernCaptureAliasReturnsOneNativeImageAndCallerOwnedResource() {
        try (var fixture = fixture()) {
            initialize(fixture.gateway, "caller-a", CURRENT_MCP);
            initialize(fixture.gateway, "caller-b", CURRENT_MCP);

            var tools = result(call(fixture.gateway, "caller-a", "tools/list", Map.of()))
                    .getAsJsonArray("tools");
            var capture = findTool(tools, "capture_screenshot");
            assertNotNull(capture);
            var properties = capture.getAsJsonObject("inputSchema").getAsJsonObject("properties");
            assertEquals(1920, properties.getAsJsonObject("max_width").get("default").getAsInt());
            assertEquals(1, properties.getAsJsonObject("max_width").get("minimum").getAsInt());
            assertEquals(8192, properties.getAsJsonObject("max_width").get("maximum").getAsInt());
            assertEquals(1080, properties.getAsJsonObject("max_height").get("default").getAsInt());

            var response = result(callTool(fixture.gateway, "caller-a", "capture_screenshot",
                    Map.of("max_width", 1, "max_height", 1)));
            assertFalse(response.get("isError").getAsBoolean());
            assertEquals(Map.of("maxWidth", 1, "maxHeight", 1), fixture.input.get());

            var content = response.getAsJsonArray("content");
            assertEquals(1, content.size());
            var image = content.get(0).getAsJsonObject();
            assertEquals("image", image.get("type").getAsString());
            assertEquals("image/png", image.get("mimeType").getAsString());
            assertEquals(PNG_BASE64, image.get("data").getAsString());

            var structured = response.getAsJsonObject("structuredContent");
            assertEquals(1, structured.get("width").getAsInt());
            assertEquals(1, structured.get("height").getAsInt());
            assertFalse(structured.toString().contains(PNG_BASE64));
            var artifact = structured.getAsJsonObject("artifact");
            var uri = artifact.get("uri").getAsString();
            assertEquals("image/png", artifact.get("mediaType").getAsString());
            assertEquals(PNG.length, artifact.get("sizeBytes").getAsInt());

            var listed = result(call(fixture.gateway, "caller-a", "resources/list", Map.of()))
                    .getAsJsonArray("resources");
            assertTrue(containsResource(listed, uri));
            assertFalse(containsResource(result(call(fixture.gateway, "caller-b", "resources/list", Map.of()))
                    .getAsJsonArray("resources"), uri));

            var read = result(call(fixture.gateway, "caller-a", "resources/read", Map.of("uri", uri)))
                    .getAsJsonArray("contents").get(0).getAsJsonObject();
            assertEquals(uri, read.get("uri").getAsString());
            assertEquals("image/png", read.get("mimeType").getAsString());
            assertEquals(PNG_BASE64, read.get("blob").getAsString());
            assertFalse(read.has("text"));

            var unauthorized = call(fixture.gateway, "caller-b", "resources/read", Map.of("uri", uri))
                    .getAsJsonObject("error");
            var nonexistent = call(fixture.gateway, "caller-a", "resources/read", Map.of(
                    "uri", "lodestone://artifacts/sha256/" + "f".repeat(64))).getAsJsonObject("error");
            assertEquals(-32002, unauthorized.get("code").getAsInt());
            assertEquals(unauthorized, nonexistent);
        }
    }

    @Test
    void captureDefaultsAreTranslatedAndGenericInvocationAlsoReturnsAnImage() {
        try (var fixture = fixture()) {
            initialize(fixture.gateway, "caller", CURRENT_MCP);

            var alias = result(callTool(fixture.gateway, "caller", "capture_screenshot", Map.of()));
            assertFalse(alias.get("isError").getAsBoolean());
            assertEquals(Map.of("maxWidth", 1920, "maxHeight", 1080), fixture.input.get());

            var generic = result(callTool(fixture.gateway, "caller", "lodestone_capability_invoke", Map.of(
                    "capability", CAPABILITY,
                    "capabilityVersion", "1.0",
                    "input", Map.of("maxWidth", 1, "maxHeight", 1))));
            assertFalse(generic.get("isError").getAsBoolean());
            assertEquals(1, generic.getAsJsonArray("content").size());
            assertEquals("image", generic.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("type").getAsString());
        }
    }

    @Test
    void stagedArtifactsAreGatedFromLegacyMcpAndIdempotencyReplay() {
        try (var fixture = fixture()) {
            initialize(fixture.gateway, "legacy", "2025-03-26");
            var legacyTools = result(call(fixture.gateway, "legacy", "tools/list", Map.of()))
                    .getAsJsonArray("tools");
            assertFalse(containsTool(legacyTools, "capture_screenshot"));
            var legacyInvoke = callTool(fixture.gateway, "legacy", "lodestone_capability_invoke", Map.of(
                    "capability", CAPABILITY, "capabilityVersion", "1.0", "input", Map.of()));
            assertEquals(-32602, legacyInvoke.getAsJsonObject("error").get("code").getAsInt());

            initialize(fixture.gateway, "modern", CURRENT_MCP);
            var replayAttempt = result(callTool(fixture.gateway, "modern", "lodestone_capability_invoke", Map.of(
                    "capability", CAPABILITY,
                    "capabilityVersion", "1.0",
                    "input", Map.of("maxWidth", 1, "maxHeight", 1),
                    "idempotencyKey", "must-not-cache")));
            assertTrue(replayAttempt.get("isError").getAsBoolean());
            assertEquals("IDEMPOTENCY_NOT_SUPPORTED", replayAttempt.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
            assertEquals(0, fixture.calls.get());
        }
    }

    private static Fixture fixture() {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("capture-screen"));
        var adapterDescriptor = new AdapterDescriptor("gateway.screenshot.test", "1.0.0",
                "minecraft", "test", "test", Environment.CLIENT);
        var catalogCapability = CoreCatalog.load().stream()
                .filter(candidate -> CAPABILITY.equals(candidate.id())).findFirst().orElseThrow();
        var capability = catalogCapability.forAdapter(adapterDescriptor, Availability.AVAILABLE, null);
        var input = new AtomicReference<Map<String, Object>>();
        var calls = new AtomicInteger();
        runtime.registerAdapter(new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return adapterDescriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, List.of(capability));
            }

            @Override
            public Map<String, dev.lodestone.adapter.CapabilityHandler> handlers() {
                return Map.of(CAPABILITY, context -> {
                    calls.incrementAndGet();
                    input.set(context.request().input());
                    var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                    return CompletableFuture.completedFuture(Map.of(
                            "artifact", reference.toMetadata(),
                            "width", 1, "height", 1,
                            "originalWidth", 1, "originalHeight", 1));
                });
            }
        });
        var gateway = new McpGateway(runtime,
                ignored -> AuthorizationPolicy.fromCsv("capture-screen"));
        return new Fixture(runtime, gateway, input, calls);
    }

    private static void initialize(McpGateway gateway, String session, String protocolVersion) {
        assertNotNull(result(call(gateway, session, "initialize", Map.of("protocolVersion", protocolVersion))));
    }

    private static JsonObject callTool(McpGateway gateway, String session, String name,
                                       Map<String, Object> arguments) {
        return call(gateway, session, "tools/call", Map.of("name", name, "arguments", arguments));
    }

    private static JsonObject call(McpGateway gateway, String session, String method,
                                   Map<String, Object> params) {
        var request = JsonSupport.MAPPER.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        return JsonParser.parseString(gateway.handle(request, session)).getAsJsonObject();
    }

    private static JsonObject result(JsonObject response) {
        return response.getAsJsonObject("result");
    }

    private static JsonObject findTool(JsonArray tools, String name) {
        for (var tool : tools) {
            if (name.equals(tool.getAsJsonObject().get("name").getAsString())) {
                return tool.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean containsTool(JsonArray tools, String name) {
        return findTool(tools, name) != null;
    }

    private static boolean containsResource(JsonArray resources, String uri) {
        for (var resource : resources) {
            if (uri.equals(resource.getAsJsonObject().get("uri").getAsString())) return true;
        }
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        private final LodestoneRuntime runtime;
        private final McpGateway gateway;
        private final AtomicReference<Map<String, Object>> input;
        private final AtomicInteger calls;

        private Fixture(LodestoneRuntime runtime, McpGateway gateway,
                        AtomicReference<Map<String, Object>> input, AtomicInteger calls) {
            this.runtime = runtime;
            this.gateway = gateway;
            this.input = input;
            this.calls = calls;
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
