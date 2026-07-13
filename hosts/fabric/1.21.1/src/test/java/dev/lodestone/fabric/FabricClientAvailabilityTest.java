// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricClientAvailabilityTest {
    @Test
    void refreshesNoneToLevelOnlyToNoneWithoutEnablingPlayerCapabilities() {
        var availability = new FabricClientAvailability();

        assertFalse(availability.levelCapabilityAvailable());
        assertFalse(availability.playerCapabilityAvailable());

        assertTrue(availability.update(true, false));
        assertTrue(availability.levelCapabilityAvailable());
        assertFalse(availability.playerCapabilityAvailable());

        assertTrue(availability.update(false, false));
        assertFalse(availability.levelCapabilityAvailable());
        assertFalse(availability.playerCapabilityAvailable());
    }

    @Test
    void refreshesPlayerLossAndLaterLevelLossIndependently() {
        var availability = new FabricClientAvailability();

        assertTrue(availability.update(true, true));
        assertTrue(availability.levelCapabilityAvailable());
        assertTrue(availability.playerCapabilityAvailable());

        assertTrue(availability.update(true, false));
        assertTrue(availability.levelCapabilityAvailable());
        assertFalse(availability.playerCapabilityAvailable());

        assertTrue(availability.update(false, false));
        assertFalse(availability.levelCapabilityAvailable());
        assertFalse(availability.playerCapabilityAvailable());
    }

    @Test
    void unchangedSnapshotsDoNotRequestARefresh() {
        var availability = new FabricClientAvailability();

        assertFalse(availability.update(false, false));
        assertTrue(availability.update(true, false));
        assertFalse(availability.update(true, false));
        assertTrue(availability.update(true, true));
        assertFalse(availability.update(true, true));
    }
}
