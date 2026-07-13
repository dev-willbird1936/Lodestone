// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonParser;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.protocol.JsonSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayTest {
    @Test
    void negotiatesMcpAndExposesTypedLodestoneTools() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            var initialize = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"));
            assertEquals("2025-11-25", initialize.getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString());

            var tools = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("lodestone_capability_invoke"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("lodestone_capability_get"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("capabilityVersion"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("ui_wait"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("ui_navigate"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("search_minecraft_item"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("furniture_lookup"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("place_furniture"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("get_heightmap"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("analyze_lighting"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("calculate_shape"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("validate_mask"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("worldedit_selection"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("worldedit_region"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("worldedit_generation"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("worldedit_clipboard"));
            assertTrue(tools.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").toString().contains("worldedit_history"));

            var status = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_status\",\"arguments\":{}}}"));
            var structured = status.getAsJsonObject().getAsJsonObject("result").get("structuredContent");
            assertNotNull(structured);
            assertTrue(structured.getAsJsonObject().get("state").isJsonPrimitive());
        }
    }

    @Test
    void calculateShapeAliasPublishesAndRoutesTheCanonicalGeometryContract() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");

            var listed = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                    .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
            com.google.gson.JsonObject calculate = null;
            for (var tool : listed) {
                if ("calculate_shape".equals(tool.getAsJsonObject().get("name").getAsString())) {
                    calculate = tool.getAsJsonObject();
                    break;
                }
            }
            assertNotNull(calculate);
            var schema = calculate.getAsJsonObject("inputSchema");
            assertEquals(false, schema.get("additionalProperties").getAsBoolean());
            assertEquals(5, schema.getAsJsonArray("oneOf").size());
            assertEquals(25, schema.getAsJsonArray("oneOf").get(1).getAsJsonObject()
                    .getAsJsonObject("properties").getAsJsonObject("radius").get("maximum").getAsInt());

            var valid = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"calculate_shape\",\"arguments\":{\"shape\":\"circle\",\"radius\":2}}}"))
                    .getAsJsonObject().getAsJsonObject("result");
            assertFalse(valid.get("isError").getAsBoolean());
            var geometry = valid.getAsJsonObject("structuredContent").getAsJsonObject("output");
            assertEquals("circle", geometry.get("shape").getAsString());
            assertEquals(true, geometry.get("bounded").getAsBoolean());

            var invalid = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"calculate_shape\",\"arguments\":{\"shape\":\"sphere\",\"radius\":26}}}"))
                    .getAsJsonObject().getAsJsonObject("result");
            assertTrue(invalid.get("isError").getAsBoolean());
            assertEquals("INVALID_INPUT", invalid.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());
            assertTrue(runtime.audit().stream().anyMatch(record ->
                    "lodestone.geometry.calculate".equals(record.capability())));
        }
    }

    @Test
    void validateMaskAliasPublishesAndRoutesTheCanonicalRuntimeContract() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");

            var listed = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                    .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
            com.google.gson.JsonObject validate = null;
            for (var tool : listed) {
                if ("validate_mask".equals(tool.getAsJsonObject().get("name").getAsString())) {
                    validate = tool.getAsJsonObject();
                    break;
                }
            }
            assertNotNull(validate);
            var schema = validate.getAsJsonObject("inputSchema");
            assertEquals(JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"mask\":"
                    + "{\"type\":\"string\",\"minLength\":1,\"maxLength\":4096}},"
                    + "\"required\":[\"mask\"],\"additionalProperties\":false}"), schema);

            var valid = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"validate_mask\",\"arguments\":{\"mask\":\"#existing\"}}}"))
                    .getAsJsonObject().getAsJsonObject("result");
            assertFalse(valid.get("isError").getAsBoolean());
            var validOutput = valid.getAsJsonObject("structuredContent").getAsJsonObject("output");
            assertEquals(true, validOutput.get("valid").getAsBoolean());
            assertEquals(true, validOutput.get("serverValidationRequired").getAsBoolean());

            var structurallyInvalid = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"validate_mask\",\"arguments\":{\"mask\":\"//replace stone dirt\"}}}"))
                    .getAsJsonObject().getAsJsonObject("result");
            assertFalse(structurallyInvalid.get("isError").getAsBoolean());
            assertEquals(false, structurallyInvalid.getAsJsonObject("structuredContent")
                    .getAsJsonObject("output").get("valid").getAsBoolean());
            assertTrue(runtime.audit().stream().anyMatch(record ->
                    "lodestone.worldedit.mask.validate".equals(record.capability())));
        }
    }

    @Test
    void compatibilityAliasUsesTheCanonicalCapabilityAndNegotiatedAvailability() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");

            var response = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ui_wait\",\"arguments\":{\"until\":\"screen_open\"}}}"));
            var result = response.getAsJsonObject().getAsJsonObject("result");

            assertTrue(result.get("isError").getAsBoolean());
            assertEquals("CAPABILITY_UNAVAILABLE", result.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());

            var heightmap = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_heightmap\",\"arguments\":{\"x1\":0,\"z1\":0,\"x2\":15,\"z2\":15}}}"));
            var heightmapResult = heightmap.getAsJsonObject().getAsJsonObject("result");
            assertTrue(heightmapResult.get("isError").getAsBoolean());
            assertEquals("CAPABILITY_UNAVAILABLE", heightmapResult.getAsJsonObject("structuredContent")
                    .getAsJsonObject("error").get("code").getAsString());

            var oversized = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_heightmap\",\"arguments\":{\"x1\":0,\"z1\":0,\"x2\":256,\"z2\":0}}}"));
            assertEquals(-32602, oversized.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        }
    }

    @Test
    void rejectsRequestsBeforeInitialization() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            var response = JsonParser.parseString(gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
            assertEquals(-32002, response.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        }
    }

    @Test
    void isolatesMcpInitializationBetweenClientSessions() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}", "client-a");

            var uninitializedB = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "client-b"));
            assertEquals(-32002, uninitializedB.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());

            var initializedA = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", "client-a"));
            assertNotNull(initializedA.getAsJsonObject().getAsJsonObject("result"));
        }
    }

    @Test
    void failedHttpInitializationDoesNotConsumeSessionCapacity() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            for (var i = 0; i < 128; i++) {
                var response = JsonParser.parseString(gateway.handleHttp(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"unsupported\"}}",
                        null));
                assertEquals(-32603, response.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
                assertEquals(null, gateway.responseSessionId());
            }

            var successful = JsonParser.parseString(gateway.handleHttp(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                    null));
            assertEquals("2025-11-25", successful.getAsJsonObject().getAsJsonObject("result")
                    .get("protocolVersion").getAsString());
            assertNotNull(gateway.responseSessionId());
        }
    }

    @Test
    void genericInvokeCannotBypassSessionOwnedEventTools() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            var response = JsonParser.parseString(gateway.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_invoke\",\"arguments\":{\"capability\":\"minecraft.event.subscribe\",\"input\":{}}}}"));
            assertEquals(-32602, response.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        }
    }
}
