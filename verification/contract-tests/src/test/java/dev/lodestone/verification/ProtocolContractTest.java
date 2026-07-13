// SPDX-License-Identifier: MIT
package dev.lodestone.verification;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Handshake;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import dev.lodestone.protocol.StructuredError;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolContractTest {
    private final Path root = Path.of(System.getProperty("lodestone.rootDir"));

    @Test
    void releaseProductVersionIsConsistentAcrossBuildsProfilesAndScripts() throws Exception {
        var expected = "1.0.0";
        assertTrue(Files.readString(root.resolve("gradle.properties")).contains("lodestoneVersion=" + expected));
        assertTrue(Files.readString(root.resolve("build.gradle.kts"))
                .contains("getOrElse(\"" + expected + "\")"));

        var versionPropertyFiles = new ArrayList<Path>();
        for (var base : List.of(root.resolve("adapters"), root.resolve("hosts"))) {
            try (var files = Files.find(base, 4, (path, attributes) -> attributes.isRegularFile()
                    && path.getFileName().toString().equals("gradle.properties"))) {
                files.filter(path -> {
                    try {
                        return Files.readString(path).contains("mod_version=");
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }).forEach(versionPropertyFiles::add);
            }
        }
        assertEquals(25, versionPropertyFiles.size());
        for (var properties : versionPropertyFiles) {
            assertTrue(Files.readString(properties).contains("mod_version=" + expected), properties.toString());
        }

        var profileDirectory = root.resolve("verification/curseforge-profiles");
        List<Path> profileManifests;
        try (var files = Files.list(profileDirectory)) {
            profileManifests = files.filter(Files::isDirectory)
                    .map(path -> path.resolve("manifest.json"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
        assertEquals(13, profileManifests.size());
        for (var manifestPath : profileManifests) {
            var manifest = JsonParser.parseReader(Files.newBufferedReader(
                    manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(expected, manifest.get("version").getAsString(), manifestPath.toString());
        }

        try (var scripts = Files.find(root.resolve("verification"), 3,
                (path, attributes) -> attributes.isRegularFile() && path.toString().endsWith(".ps1"))) {
            for (var script : scripts.toList()) {
                assertFalse(Files.readString(script).contains("0.1.0-SNAPSHOT"), script.toString());
            }
        }

        var releaseManifestScript = Files.readString(
                root.resolve("verification/release-artifact-manifest.ps1"));
        assertTrue(releaseManifestScript.contains("evidence/release-artifacts-v1.0.0.json"));
        assertFalse(releaseManifestScript.contains("evidence/release-artifacts-2026-07-12.json"),
                "v1 release tooling must never overwrite the immutable C0 manifest");
    }

    @Test
    void everySchemaIsJsonAndLocalReferencesResolve() throws Exception {
        var schemas = Files.list(root.resolve("protocol/schemas")).filter(path -> path.toString().endsWith(".json"))
                .toList();
        assertEquals(7, schemas.size());
        for (var schemaPath : schemas) {
            var schema = JsonParser.parseReader(Files.newBufferedReader(schemaPath, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").getAsString());
            assertTrue(schema.get("$id").isJsonPrimitive());
            assertEquals("object", schema.get("type").getAsString());
            assertLocalReferencesResolve(schema, schemaPath.getParent());
        }
    }

    @Test
    void catalogIdsAreUniqueAndUnavailableEntriesExplainThemselves() throws Exception {
        var catalog = JsonParser.parseReader(Files.newBufferedReader(root.resolve("protocol/catalog/core-capabilities.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var ids = new HashSet<String>();
        assertEquals("0.3.0", catalog.get("catalogVersion").getAsString());
        assertEquals(48, catalog.getAsJsonArray("capabilities").size());
        for (var capability : catalog.getAsJsonArray("capabilities")) {
            var id = capability.getAsJsonObject().get("id").getAsString();
            assertTrue(ids.add(id), "duplicate capability: " + id);
            for (var field : Set.of("kind", "version", "stability", "availability", "adapterId", "adapterVersion",
                    "gameEdition", "gameVersion", "loader", "environment", "inputSchema", "outputSchema",
                    "eventSchema", "permissions", "sideEffect", "idempotency", "prerequisites", "nativeThread",
                    "rateLimit", "timeoutMs", "cancellable", "delivery", "documentation", "featureFlags")) {
                assertFalse(capability.getAsJsonObject().get(field) == null || capability.getAsJsonObject().get(field).isJsonNull(),
                        () -> id + " missing " + field);
            }
            if (!"available".equals(capability.getAsJsonObject().get("availability").getAsString())) {
                assertTrue(capability.getAsJsonObject().get("reason").isJsonObject(), id + " must explain unavailable state");
                assertTrue(capability.getAsJsonObject().getAsJsonObject("reason").get("code").isJsonPrimitive());
                assertTrue(capability.getAsJsonObject().getAsJsonObject("reason").get("message").isJsonPrimitive());
            }
        }
    }

    @Test
    void uiMovementReleaseAndCraftSchemasAcceptOnlyBoundedTypedValues() throws Exception {
        var catalog = JsonParser.parseReader(Files.newBufferedReader(
                root.resolve("protocol/catalog/core-capabilities.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var revision = "0".repeat(64);

        var uiState = capabilitySchema(catalog, "minecraft.ui.state.read", "outputSchema");
        var widget = Map.<String, Object>of(
                "nodeId", "n1", "path", List.of(0), "depth", 0, "type", "button",
                "focused", false, "actions", List.of("click"));
        var snapshot = Map.<String, Object>ofEntries(
                Map.entry("open", true), Map.entry("inWorld", false), Map.entry("screen", "title"),
                Map.entry("screenClass", "net.minecraft.client.gui.screens.TitleScreen"),
                Map.entry("title", "Minecraft"), Map.entry("screenToken", "screen-1"),
                Map.entry("snapshotRevision", revision), Map.entry("capturedAtTick", 42),
                Map.entry("width", 320), Map.entry("height", 240), Map.entry("guiScale", 2.0),
                Map.entry("coverage", "complete"), Map.entry("truncated", false),
                Map.entry("truncationCauses", List.of()), Map.entry("widgets", List.of(widget)));
        assertTrue(SchemaValidator.validate(uiState, snapshot).isEmpty());
        var missingCoverage = new LinkedHashMap<>(snapshot);
        missingCoverage.remove("coverage");
        assertFalse(SchemaValidator.validate(uiState, missingCoverage).isEmpty());
        var invalidWidget = new LinkedHashMap<>(widget);
        invalidWidget.put("nativeHandle", "must-not-leak");
        var malformedSnapshot = new LinkedHashMap<>(snapshot);
        malformedSnapshot.put("widgets", List.of(invalidWidget));
        assertFalse(SchemaValidator.validate(uiState, malformedSnapshot).isEmpty());

        var click = capabilitySchema(catalog, "minecraft.ui.click", "inputSchema");
        assertTrue(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 0, "nodeId", "n1")).isEmpty());
        assertTrue(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "nodeId", "n1")).isEmpty());
        assertTrue(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 8, "x", 10.5, "y", 20.5)).isEmpty());
        assertFalse(SchemaValidator.validate(click, Map.of("snapshotRevision", revision,
                "button", 0, "nodeId", "n1")).isEmpty());
        assertFalse(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 9, "nodeId", "n1")).isEmpty());
        assertFalse(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 0, "path", List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))).isEmpty());
        assertFalse(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(click, Map.of("screenToken", "screen-1",
                "snapshotRevision", revision, "button", 0, "nodeId", "n1", "label", "Play")).isEmpty());

        var move = capabilitySchema(catalog, "minecraft.player.move", "inputSchema");
        assertTrue(SchemaValidator.validate(move, Map.of("forward", 1, "sneak", true, "durationMs", 100)).isEmpty());
        assertFalse(SchemaValidator.validate(move, Map.of("durationMs", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(move, Map.of("durationMs", 10001)).isEmpty());

        var release = capabilitySchema(catalog, "minecraft.input.release-all", "inputSchema");
        assertTrue(SchemaValidator.validate(release, Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(release, Map.of("force", true)).isEmpty());
        var releaseOutput = capabilitySchema(catalog, "minecraft.input.release-all", "outputSchema");
        assertTrue(SchemaValidator.validate(releaseOutput,
                Map.of("released", List.of("key.forward"), "count", 1, "leaseGeneration", 2)).isEmpty());
        assertFalse(SchemaValidator.validate(releaseOutput,
                Map.of("released", List.of(), "count", 0)).isEmpty());

        var craft = capabilitySchema(catalog, "minecraft.inventory.craft", "inputSchema");
        assertTrue(SchemaValidator.validate(craft, Map.of("item", "minecraft:torch", "count", 4)).isEmpty());
        assertFalse(SchemaValidator.validate(craft, Map.of("item", "minecraft:torch", "count", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(craft,
                Map.of("item", "minecraft:torch", "count", 4, "menuRevision", 1)).isEmpty());
    }

    @Test
    void fixturesHaveExpectedValidityMarkers() throws Exception {
        var valid = JsonParser.parseReader(Files.newBufferedReader(root.resolve("protocol/fixtures/valid/handshake.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var invalid = JsonParser.parseReader(Files.newBufferedReader(root.resolve("protocol/fixtures/invalid/missing-capability-id.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(ProtocolVersion.CURRENT, valid.get("protocolVersion").getAsString());
        assertTrue(valid.get("sessionId").isJsonPrimitive());
        assertTrue(invalid.get("id") == null || invalid.get("id").isJsonNull());
    }

    @Test
    void liveResultSerializationMatchesPublishedStatusAndErrorContract() throws Exception {
        var schema = JsonParser.parseReader(Files.newBufferedReader(
                root.resolve("protocol/schemas/result-envelope.schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var allowed = new HashSet<String>();
        schema.getAsJsonObject("properties").getAsJsonObject("status").getAsJsonArray("enum")
                .forEach(value -> allowed.add(value.getAsString()));

        for (var status : ResultEnvelope.Status.values()) {
            var envelope = status == ResultEnvelope.Status.OK
                    ? ResultEnvelope.ok("wire-result", Map.of("value", true))
                    : ResultEnvelope.error("wire-result", status,
                    StructuredError.of("TEST", "wire contract test", false));
            var serialized = JsonSupport.MAPPER.toJsonTree(envelope).getAsJsonObject();
            assertTrue(allowed.contains(serialized.get("status").getAsString()),
                    () -> "unsupported live status: " + serialized.get("status"));
            assertEquals(status == ResultEnvelope.Status.OK, !serialized.has("error"));
            assertRequiredFields(schema, serialized);
        }
    }

    @Test
    void liveHandshakeAndAvailableCapabilityPreserveRequiredNullableReason() throws Exception {
        var descriptor = new AdapterDescriptor("test.adapter", "1.0.0", "minecraft-java", "test",
                "test-loader", Environment.REMOTE);
        var capability = new CapabilityDescriptor("test.capability", CapabilityKind.QUERY, "1.0",
                Stability.STABLE, Availability.AVAILABLE, null, descriptor.id(), descriptor.version(),
                descriptor.gameEdition(), descriptor.gameVersion(), descriptor.loader(), descriptor.environment(),
                Map.of("type", "object"), Map.of("type", "object"), Map.of(), Set.of(PermissionClass.OBSERVE),
                SideEffect.NONE, Idempotency.IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false), "runtime",
                new RateLimit(1, 1000, 1), 1000, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1), "Wire contract test", Set.of());
        var serializedCapability = JsonSupport.MAPPER.toJsonTree(capability).getAsJsonObject();
        var capabilitySchema = JsonParser.parseReader(Files.newBufferedReader(
                root.resolve("protocol/schemas/capability.schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();

        assertRequiredFields(capabilitySchema, serializedCapability);
        assertTrue(serializedCapability.get("reason").isJsonNull());

        var handshake = JsonSupport.MAPPER.toJsonTree(new Handshake(ProtocolVersion.CURRENT, "wire-session",
                descriptor, List.of(capability), "wire-test")).getAsJsonObject();
        var handshakeSchema = JsonParser.parseReader(Files.newBufferedReader(
                root.resolve("protocol/schemas/handshake.schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertRequiredFields(handshakeSchema, handshake);
        assertTrue(handshake.getAsJsonArray("capabilities").get(0).getAsJsonObject().get("reason").isJsonNull());
    }

    @Test
    void curseForgeProfileSourcesAreCompleteAndCanonical() throws Exception {
        var profileDirectory = root.resolve("verification/curseforge-profiles");
        List<Path> profiles;
        try (var files = Files.list(profileDirectory)) {
            profiles = files.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("manifest.json")))
                    .sorted().toList();
        }
        assertEquals(13, profiles.size(), "expected one tracked source directory for every tested profile");

        for (var profile : profiles) {
            assertTrue(profile.getFileName().toString().matches("^lodestone-[a-z0-9.-]+$"),
                    profile + " has a noncanonical profile directory name");
            var manifest = JsonParser.parseReader(Files.newBufferedReader(
                    profile.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("minecraftModpack", manifest.get("manifestType").getAsString());
            assertEquals(1, manifest.get("manifestVersion").getAsInt());
            assertEquals("overrides", manifest.get("overrides").getAsString());
            assertTrue(manifest.getAsJsonObject("minecraft").get("version").isJsonPrimitive());
            assertEquals(1, manifest.getAsJsonObject("minecraft").getAsJsonArray("modLoaders").size());
        }
    }

    @Test
    void testedLoaderArtifactsAreExactPinnedAndPluginHostsRejectWrongBrands() throws Exception {
        var forgePins = Map.of(
                "1.16.5", List.of("loaderVersion=\"[36,37)\"", "versionRange=\"[36.2.42,36.2.43)\"", "versionRange=\"[1.16.5,1.16.6)\""),
                "1.18.2", List.of("loaderVersion=\"[40,41)\"", "versionRange=\"[40.3.12,40.3.13)\"", "versionRange=\"[1.18.2,1.18.3)\""),
                "1.19.2", List.of("loaderVersion=\"[43,44)\"", "versionRange=\"[43.5.2,43.5.3)\"", "versionRange=\"[1.19.2,1.19.3)\""),
                "1.20.1", List.of("loaderVersion=\"[47,48)\"", "versionRange=\"[47.4.10,47.4.11)\"", "versionRange=\"[1.20.1,1.20.2)\""),
                "1.21.1", List.of("loaderVersion=\"[52,53)\"", "versionRange=\"[52.1.0,52.1.1)\"", "versionRange=\"[1.21.1,1.21.2)\""));
        for (var pin : forgePins.entrySet()) {
            var metadata = Files.readString(root.resolve("hosts/forge/" + pin.getKey()
                    + "/src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);
            for (var expected : pin.getValue()) {
                assertTrue(metadata.contains(expected), pin.getKey() + " Forge metadata missing " + expected);
            }
        }

        var neoProperties = Files.readString(root.resolve("hosts/neoforge/1.21.1/gradle.properties"),
                StandardCharsets.UTF_8);
        for (var expected : List.of("minecraft_version_range=[1.21.1,1.21.2)",
                "neo_version_range=[21.1.211,21.1.212)", "loader_version_range=[4,5)")) {
            assertTrue(neoProperties.contains(expected), "NeoForge metadata missing " + expected);
        }

        var fabricPins = Map.of(
                "1.18.2", "0.14.25", "1.19.2", "0.14.25", "1.20.1", "0.15.11",
                "1.21.1", "0.16.10", "26.2", "0.19.3");
        for (var pin : fabricPins.entrySet()) {
            var metadata = JsonParser.parseReader(Files.newBufferedReader(root.resolve("hosts/fabric/" + pin.getKey()
                    + "/src/main/resources/fabric.mod.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(pin.getKey(), metadata.getAsJsonObject("depends").get("minecraft").getAsString());
            assertEquals(pin.getValue(), metadata.getAsJsonObject("depends").get("fabricloader").getAsString());
        }

        var pluginPins = Map.of(
                "hosts/paper/1.21.1/src/main/java/dev/lodestone/paper/LodestonePaperPlugin.java",
                "requireRuntime(\"1.21.1\", \"paper\")",
                "hosts/spigot/1.21.1/src/main/java/dev/lodestone/spigot/LodestoneSpigotPlugin.java",
                "requireRuntime(\"1.21.1\", \"spigot\")",
                "hosts/folia/1.21.4/src/main/java/dev/lodestone/folia/LodestoneFoliaPlugin.java",
                "requireRuntime(\"1.21.4\", \"folia\")");
        for (var pin : pluginPins.entrySet()) {
            assertTrue(Files.readString(root.resolve(pin.getKey()), StandardCharsets.UTF_8).contains(pin.getValue()),
                    pin.getKey() + " is missing exact host admission");
        }
    }

    private static void assertRequiredFields(com.google.gson.JsonObject schema,
                                             com.google.gson.JsonObject serialized) {
        schema.getAsJsonArray("required").forEach(field ->
                assertTrue(serialized.has(field.getAsString()),
                        "missing live field: " + field.getAsString() + " in " + serialized));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capabilitySchema(com.google.gson.JsonObject catalog, String capabilityId,
                                                        String schemaField) {
        for (var element : catalog.getAsJsonArray("capabilities")) {
            var capability = element.getAsJsonObject();
            if (capabilityId.equals(capability.get("id").getAsString())) {
                return JsonSupport.MAPPER.fromJson(capability.getAsJsonObject(schemaField), Map.class);
            }
        }
        throw new AssertionError("missing capability: " + capabilityId);
    }

    private static void assertLocalReferencesResolve(JsonElement node, Path schemaDirectory) {
        if (node.isJsonObject()) {
            node.getAsJsonObject().entrySet().forEach(entry -> {
                if ("$ref".equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    var reference = entry.getValue().getAsString().split("#", 2)[0];
                    if (!reference.isBlank() && !reference.startsWith("http")) {
                        assertTrue(Files.exists(schemaDirectory.resolve(reference).normalize()),
                                "missing schema reference: " + reference);
                    }
                }
                assertLocalReferencesResolve(entry.getValue(), schemaDirectory);
            });
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(value -> assertLocalReferencesResolve(value, schemaDirectory));
        }
    }
}
