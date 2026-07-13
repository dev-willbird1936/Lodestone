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
            "186733cc81351f8de77ed2f14465f5e5819689239ef7f88b15c16672357d4db6",
            "lodestone://vibecraft/furniture/layouts",
            "0e9e51985e073e9d680613ddccfe87127f2005d95a936533b2c566940a377a68",
            "lodestone://vibecraft/building/patterns",
            "16ac7d73a021d24da93c74847c92becaaa298a4c230507cc2a0f47e963adfed0",
            "lodestone://vibecraft/terrain/patterns",
            "6343c469b26a7326b57aafb370b636169d085ff8128eb7e41060e63b4acdea19",
            "lodestone://vibecraft/building/templates",
            "678ffcf96da391cb44e027405803fe8550d58c5100b52762647c87d0a0d8ff23");

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
