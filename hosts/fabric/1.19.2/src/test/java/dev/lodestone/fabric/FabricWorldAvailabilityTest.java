// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricWorldAvailabilityTest {
    @Test
    void cachesLevelPresenceAndReportsOnlyTransitions() {
        var availability = new FabricWorldAvailability();
        assertFalse(availability.available());
        assertTrue(availability.update(true));
        assertTrue(availability.available());
        assertFalse(availability.update(true));
        assertTrue(availability.update(false));
        assertFalse(availability.available());
    }

    @Test
    void cachedFlagIsVolatileForOffThreadManifestReads() throws Exception {
        var field = FabricWorldAvailability.class.getDeclaredField("available");
        assertTrue(Modifier.isVolatile(field.getModifiers()));
    }
}
