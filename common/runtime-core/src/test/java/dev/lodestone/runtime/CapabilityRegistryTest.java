// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityRegistryTest {
    @Test
    void rejectsDuplicateAvailableCapabilities() {
        assertDuplicateInvocableRejected(Availability.AVAILABLE);
    }

    @Test
    void rejectsDuplicateRestrictedCapabilities() {
        assertDuplicateInvocableRejected(Availability.RESTRICTED);
    }

    @Test
    void unavailableCatalogRowsCannotHideAnInvocableCapability() {
        var registry = new CapabilityRegistry();
        registry.register(adapter("live.adapter", Availability.AVAILABLE));

        registry.register(adapter("placeholder.adapter", Availability.UNAVAILABLE));

        assertEquals(Availability.AVAILABLE, registry.get("test.duplicate").descriptor().availability());
        assertEquals("live.adapter", registry.get("test.duplicate").adapter().descriptor().id());
    }

    @Test
    void invocableCapabilityReplacesAnUnavailableCatalogRow() {
        var registry = new CapabilityRegistry();
        registry.register(adapter("placeholder.adapter", Availability.UNAVAILABLE));

        registry.register(adapter("live.adapter", Availability.AVAILABLE));

        assertEquals(Availability.AVAILABLE, registry.get("test.duplicate").descriptor().availability());
        assertEquals("live.adapter", registry.get("test.duplicate").adapter().descriptor().id());
    }

    private static void assertDuplicateInvocableRejected(Availability availability) {
        var registry = new CapabilityRegistry();
        registry.register(adapter("first.adapter", availability));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("second.adapter", availability)));

        assertEquals("duplicate invocable capability: test.duplicate", failure.getMessage());
        assertEquals("first.adapter", registry.get("test.duplicate").adapter().descriptor().id());
    }

    private static LodestoneAdapter adapter(String adapterId, Availability availability) {
        var adapterDescriptor = new AdapterDescriptor(adapterId, "1.0.0", "minecraft", "test", "test",
                Environment.REMOTE);
        var reason = availability == Availability.AVAILABLE ? null
                : new AvailabilityReason("permission-required", "permission is required", Map.of());
        var capability = new CapabilityDescriptor("test.duplicate", CapabilityKind.ACTION, "1.0",
                Stability.STABLE, availability, reason, adapterId, "1.0.0", "minecraft", "test", "test",
                Environment.REMOTE, Map.of("type", "object"), Map.of("type", "object"), Map.of(),
                Set.of(PermissionClass.OBSERVE), SideEffect.NONE, Idempotency.IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false), "runtime", new RateLimit(10, 1000, 2),
                1000, true, new DeliveryGuarantees("request-order", "at-most-once", 1), "Test capability", Set.of());
        CapabilityHandler handler = context -> CompletableFuture.completedFuture(Map.of());

        return new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return adapterDescriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, java.util.List.of(capability));
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return Map.of(capability.id(), handler);
            }
        };
    }
}
