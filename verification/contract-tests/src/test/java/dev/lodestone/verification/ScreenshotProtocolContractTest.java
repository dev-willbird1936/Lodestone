// SPDX-License-Identifier: MIT
package dev.lodestone.verification;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScreenshotProtocolContractTest {
    private final Path root = Path.of(System.getProperty("lodestone.rootDir"));

    @Test
    void capabilitySchemaPublishesDedicatedCaptureScreenPermission() throws Exception {
        var schema = JsonParser.parseReader(Files.newBufferedReader(
                root.resolve("protocol/schemas/capability.schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var permissions = schema.getAsJsonObject("properties").getAsJsonObject("permissions")
                .getAsJsonObject("items").getAsJsonArray("enum");
        var values = new HashSet<String>();
        permissions.forEach(value -> values.add(value.getAsString()));

        assertTrue(values.contains("capture-screen"));
    }
}
