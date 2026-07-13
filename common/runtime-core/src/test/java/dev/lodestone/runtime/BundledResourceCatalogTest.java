// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BundledResourceCatalogTest {
    private static final Map<String, String> SOURCE_HASHES = Map.of(
            "lodestone://vibecraft/furniture/catalog",
            "3932cae42802b4dc6cb49bc9f9b011fdfaa2ee5b435805427968087fa2eeece1",
            "lodestone://vibecraft/furniture/layouts",
            "4ec8976e0f001f85636d1b74a5eb76b5cddb1c16799f4168b6ac7fb034732b63",
            "lodestone://vibecraft/building/patterns",
            "28f8e0c39caf3cb2f493c97eabb9d507052dae52fffd26359353c77cff4284ba",
            "lodestone://vibecraft/terrain/patterns",
            "b6fbba6e116433c447fc46769a56dc770724658ac8bfe72c7b3951f675e04e40",
            "lodestone://vibecraft/building/templates",
            "3e77a7d0b7d6c7933ffeb1332c416876eff54e45219f4309bb6028c7eac39406");

    @Test
    void exposesVerbatimVibecraftCatalogsWithPinnedSourceHashesAndHonestMetadata() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            assertEquals(10, runtime.resources().size());
            for (var entry : SOURCE_HASHES.entrySet()) {
                var content = runtime.readResource(entry.getKey(), "caller-a");
                assertEquals(entry.getValue(), sha256(content));
                assertEquals("application/json", runtime.resources().stream()
                        .filter(resource -> resource.uri().equals(entry.getKey()))
                        .findFirst().orElseThrow().mimeType());
            }

            assertEquals(63, JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/furniture/catalog")).getAsJsonArray().size());
            assertEquals(66, JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/furniture/layouts")).getAsJsonArray().size());
            assertEquals(29, JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/building/patterns")).getAsJsonObject()
                    .getAsJsonObject("patterns").size());
            assertEquals(41, JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/terrain/patterns")).getAsJsonObject()
                    .getAsJsonObject("patterns").size());
            assertEquals(5, JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/building/templates")).getAsJsonObject()
                    .getAsJsonObject("templates").size());

            var provenance = JsonParser.parseString(runtime.readResource(
                    "lodestone://vibecraft/provenance")).getAsJsonObject();
            assertEquals("MIT", provenance.get("license").getAsString());
            assertEquals("be5045f20027b60dd1d1a8604379c51ebf84e2f4",
                    provenance.get("upstreamRevision").getAsString());
            assertTrue(provenance.getAsJsonObject("semantics").get("buildingPatterns")
                    .getAsString().contains("absent"));
        }
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
