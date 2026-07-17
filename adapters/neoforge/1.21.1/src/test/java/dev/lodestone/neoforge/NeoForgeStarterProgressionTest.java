// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeStarterProgressionTest {
    @Test
    void advancesToCraftingWhenExhaustedStarterSourceLeftEnoughLogs() {
        assertFalse(NeoForgeNetherGoal.starterWoodSufficientForCrafting(2));
        assertTrue(NeoForgeNetherGoal.starterWoodSufficientForCrafting(3));
        assertTrue(NeoForgeNetherGoal.starterWoodSufficientForCrafting(4));
    }

    @Test
    void onlyTypedSafeNavigationFailuresAreReplanned() {
        assertTrue(NeoForgeNetherGoal.isRecoverableSafeNavigationFailure(
                "safe intelligent navigation could not continue toward vertical route recovery"));
        assertTrue(NeoForgeNetherGoal.isRecoverableSafeNavigationFailure(
                "safe intelligent navigation remained blocked during vertical route recovery"));
        assertFalse(NeoForgeNetherGoal.isRecoverableSafeNavigationFailure(
                "normal-input navigation stuck during vertical route recovery"));
        assertFalse(NeoForgeNetherGoal.isRecoverableSafeNavigationFailure(null));
    }
}
