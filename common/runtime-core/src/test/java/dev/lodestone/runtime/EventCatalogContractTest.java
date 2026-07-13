// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.SchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventCatalogContractTest {
    @Test
    void subscribeBoundsMatchTheAdvertisedEventBuffer() {
        var subscribe = capability("minecraft.event.subscribe");

        assertEquals(10_000, subscribe.delivery().bufferLimit());
        assertTrue(SchemaValidator.validate(subscribe.inputSchema(),
                Map.of("eventPrefix", "x".repeat(256), "bufferLimit", 10_000)).isEmpty());
        assertFalse(SchemaValidator.validate(subscribe.inputSchema(),
                Map.of("eventPrefix", "x".repeat(257), "bufferLimit", 10_000)).isEmpty());
        assertFalse(SchemaValidator.validate(subscribe.inputSchema(),
                Map.of("bufferLimit", 10_001)).isEmpty());
        assertTrue(subscribe.featureFlags().contains("sensitive-input-redacted"));
    }

    @Test
    void subscribeOutputPublishesBoundedSubscriptionFields() {
        var schema = capability("minecraft.event.subscribe").outputSchema();
        var valid = Map.of("subscription", Map.of(
                "id", "subscription-1",
                "sessionId", "caller-1",
                "eventPrefix", "minecraft.",
                "bufferLimit", 10_000));

        assertTrue(SchemaValidator.validate(schema, valid).isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of("subscription", Map.of(
                "id", "", "sessionId", "caller-1", "eventPrefix", "minecraft.", "bufferLimit", 10))).isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of("subscription", Map.of(
                "id", "subscription-1", "sessionId", "caller-1",
                "eventPrefix", "x".repeat(257), "bufferLimit", 10))).isEmpty());
    }

    @Test
    void pollOutputValidatesCompleteBoundedEventEnvelopes() {
        var poll = capability("minecraft.event.poll");
        var event = Map.<String, Object>ofEntries(
                Map.entry("protocolVersion", "1.0"),
                Map.entry("sessionId", "runtime-session"),
                Map.entry("event", "minecraft.lifecycle.server.started"),
                Map.entry("sequence", 1),
                Map.entry("payload", Map.of()),
                Map.entry("gameTick", -1),
                Map.entry("occurredAt", "2026-07-13T00:00:00Z"));

        assertEquals(10_000, poll.delivery().bufferLimit());
        assertTrue(SchemaValidator.validate(poll.outputSchema(), Map.of("events", List.of(event))).isEmpty());
        assertFalse(SchemaValidator.validate(poll.outputSchema(), Map.of(
                "events", List.of(Map.of("event", "minecraft.partial")))).isEmpty());
        assertFalse(SchemaValidator.validate(poll.outputSchema(), Map.of(
                "events", java.util.Collections.nCopies(1_001, event))).isEmpty());
    }

    private static CapabilityDescriptor capability(String id) {
        return CoreCatalog.load().stream()
                .filter(capability -> id.equals(capability.id()))
                .findFirst()
                .orElseThrow();
    }
}
