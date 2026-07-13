// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayStaticCatalogTest {
    @Test
    void exposesBoundedFurnitureAndHonestPatternAndTemplateLookups() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initialized(runtime);

            var furniture = call(gateway, "furniture_lookup",
                    "{\"action\":\"search\",\"query\":\"table\"}");
            assertTrue(furniture.get("count").getAsInt() > 0);
            assertTrue(furniture.getAsJsonArray("results").toString().contains("corner_table"));
            var exactFurniture = call(gateway, "furniture_lookup",
                    "{\"action\":\"get\",\"furniture_id\":\"corner_table\"}");
            assertTrue(exactFurniture.get("found").getAsBoolean());
            assertTrue(exactFurniture.getAsJsonObject("furniture").getAsJsonArray("placements").size() > 0);

            var building = call(gateway, "building_pattern_lookup", "{\"action\":\"browse\"}");
            assertEquals(29, building.get("count").getAsInt());
            assertFalse(building.get("placementAvailable").getAsBoolean());
            assertTrue(building.get("limitation").getAsString().contains("metadata"));

            var terrain = call(gateway, "terrain_pattern_lookup", "{\"action\":\"categories\"}");
            assertTrue(terrain.get("count").getAsInt() > 0);

            var templates = call(gateway, "building_template", "{\"action\":\"list\"}");
            assertEquals(5, templates.get("count").getAsInt());
            assertFalse(templates.get("executable").getAsBoolean());
            var customize = call(gateway, "building_template",
                    "{\"action\":\"customize\",\"template_id\":\"medieval_round_tower\"}");
            assertTrue(customize.get("found").getAsBoolean());
            assertTrue(customize.getAsJsonObject("parameters").has("height"));
        }
    }

    @Test
    void missingConditionalLookupIdentifierIsAClientError() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = initialized(runtime);

            var response = rawCall(gateway, "furniture_lookup", "{\"action\":\"get\"}");

            assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    private static McpGateway initialized(LodestoneRuntime runtime) {
        var gateway = new McpGateway(runtime);
        gateway.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
        return gateway;
    }

    private static JsonObject call(McpGateway gateway, String name, String arguments) {
        return rawCall(gateway, name, arguments).getAsJsonObject("result").getAsJsonObject("structuredContent");
    }

    private static JsonObject rawCall(McpGateway gateway, String name, String arguments) {
        var request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 2);
        request.addProperty("method", "tools/call");
        var params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", JsonParser.parseString(arguments));
        request.add("params", params);
        return JsonParser.parseString(gateway.handle(request.toString())).getAsJsonObject();
    }
}
