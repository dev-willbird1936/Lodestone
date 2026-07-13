// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolModelTest {
    @Test
    void serializesWireEnumsAsStableLowercaseValues() throws Exception {
        var adapter = new AdapterDescriptor("test", "1.0", "minecraft", "test", "test", Environment.REMOTE);
        var descriptor = new CapabilityDescriptor("test.read", CapabilityKind.QUERY, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "test", "1.0", "minecraft", "test", "test", Environment.REMOTE,
                Map.of(), Map.of(), Map.of(), Set.of(PermissionClass.OBSERVE), SideEffect.NONE,
                Idempotency.IDEMPOTENT, new CapabilityPrerequisites(false, false, false, false), "runtime",
                new RateLimit(1, 1000, 1), 1000, true, new DeliveryGuarantees("request-order", "at-most-once", 1),
                "read", Set.of());
        var json = com.google.gson.JsonParser.parseString(
                JsonSupport.MAPPER.toJson(new CapabilityManifest(adapter, java.util.List.of(descriptor)))).getAsJsonObject();
        assertEquals("query", json.getAsJsonArray("capabilities").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("remote", json.getAsJsonObject("adapter").get("environment").getAsString());
    }

    @Test
    void nonAvailableCapabilitiesNeedStructuredReasons() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDescriptor("test.read", CapabilityKind.QUERY,
                "1.0", Stability.STABLE, Availability.UNAVAILABLE, null, "test", "1.0", "minecraft", "test",
                "test", Environment.REMOTE, Map.of(), Map.of(), Map.of(), Set.of(PermissionClass.OBSERVE),
                SideEffect.NONE, Idempotency.IDEMPOTENT, new CapabilityPrerequisites(false, false, false, false),
                "runtime", new RateLimit(1, 1000, 1), 1000, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1), "read", Set.of()));
    }

    @Test
    void capabilitiesRequireExplicitPermissionsMatchingTheirSideEffect() {
        assertThrows(IllegalArgumentException.class, () -> descriptorWith(
                Set.of(), SideEffect.NONE));
        assertThrows(IllegalArgumentException.class, () -> descriptorWith(
                Set.of(PermissionClass.OBSERVE), SideEffect.MODIFY_WORLD));
        assertEquals(SideEffect.MODIFY_WORLD, descriptorWith(
                Set.of(PermissionClass.OBSERVE, PermissionClass.MODIFY_WORLD),
                SideEffect.MODIFY_WORLD).sideEffect());
    }

    @Test
    void schemaValidatorEnforcesAnyOfWithoutSkippingTheParentSchema() {
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("key", Map.of("type", "string"), "button", Map.of("type", "integer")),
                "anyOf", List.of(Map.of("required", List.of("key")), Map.of("required", List.of("button"))),
                "additionalProperties", false);

        assertTrue(SchemaValidator.validate(schema, Map.of("key", "attack")).isEmpty());
        assertTrue(SchemaValidator.validate(schema, Map.of("button", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of("key", "attack", "unknown", true)).isEmpty());
    }

    @Test
    void schemaValidatorRejectsUnsupportedConstraintsAndTypes() {
        assertFalse(SchemaValidator.validateSchema(Map.of("type", "mystery")).isEmpty());
        assertFalse(SchemaValidator.validateSchema(
                Map.of("type", "object", "dependentSchemas", Map.of())).isEmpty());
    }

    @Test
    void schemaValidatorEnforcesOneOfPatternsAndUniqueItems() {
        var selector = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of(
                        "nodeId", Map.of("type", "string", "pattern", "^n[0-9]+$"),
                        "label", Map.of("type", "string")),
                "oneOf", List.of(Map.of("required", List.of("nodeId")), Map.of("required", List.of("label"))),
                "additionalProperties", false);
        assertTrue(SchemaValidator.validate(selector, Map.of("nodeId", "n4")).isEmpty());
        assertFalse(SchemaValidator.validate(selector, Map.of("nodeId", "bad")).isEmpty());
        assertFalse(SchemaValidator.validate(selector, Map.of("nodeId", "n4", "label", "Play")).isEmpty());

        var unique = Map.<String, Object>of("type", "array", "uniqueItems", true,
                "items", Map.of("type", "integer"));
        assertTrue(SchemaValidator.validate(unique, List.of(1, 2)).isEmpty());
        assertFalse(SchemaValidator.validate(unique, List.of(1, 1)).isEmpty());
    }

    private static CapabilityDescriptor descriptorWith(Set<PermissionClass> permissions, SideEffect sideEffect) {
        return new CapabilityDescriptor("test.permission", CapabilityKind.ACTION, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "test", "1.0", "minecraft", "test", "test",
                Environment.REMOTE, Map.of(), Map.of(), Map.of(), permissions, sideEffect,
                Idempotency.NON_IDEMPOTENT, new CapabilityPrerequisites(false, false, false, false),
                "runtime", new RateLimit(1, 1000, 1), 1000, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1), "permission test", Set.of());
    }
}
