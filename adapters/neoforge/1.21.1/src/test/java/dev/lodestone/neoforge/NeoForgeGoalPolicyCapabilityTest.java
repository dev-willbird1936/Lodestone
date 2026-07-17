// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeGoalPolicyCapabilityTest {
    @Test
    void intelligenceCapabilitiesAreLayeredAndSafetyIsOrthogonal() {
        var rawHigh = policy("raw-v1", "high");
        assertTrue(rawHigh.directObservedActionsOnly());
        assertFalse(rawHigh.prerequisitePlanningEnabled());
        assertFalse(rawHigh.undergroundPrerequisiteAcquisitionEnabled());
        assertTrue(rawHigh.highSafety());
        assertEquals("direct-observed-actions", rawHigh.intelligenceLayer());

        var guardedHigh = policy("guarded-v1", "high");
        assertTrue(guardedHigh.prerequisitePlanningEnabled());
        assertTrue(guardedHigh.safeExposedPrerequisiteSearchEnabled());
        assertFalse(guardedHigh.recursivePrerequisitePlanningEnabled());
        assertFalse(guardedHigh.undergroundPrerequisiteAcquisitionEnabled());
        assertTrue(guardedHigh.highSafety());
        assertEquals("prerequisite-tools+safe-exposed-search", guardedHigh.intelligenceLayer());

        var adaptiveLow = policy("adaptive-v1", "low");
        assertTrue(adaptiveLow.recursivePrerequisitePlanningEnabled());
        assertTrue(adaptiveLow.undergroundPrerequisiteAcquisitionEnabled());
        assertTrue(adaptiveLow.frontierNoveltyRecoveryEnabled());
        assertFalse(adaptiveLow.highSafety());
        assertEquals("recursive-prerequisites+underground-acquisition+novelty-recovery",
                adaptiveLow.intelligenceLayer());

        var adaptiveBreakingDisabled = NeoForgeGoalPolicy.from(Map.of("intelligence", "adaptive-v1",
                "safety", "high", "allowBlockBreaking", false));
        assertTrue(adaptiveBreakingDisabled.recursivePrerequisitePlanningEnabled());
        assertFalse(adaptiveBreakingDisabled.undergroundPrerequisiteAcquisitionEnabled());
        assertTrue(adaptiveBreakingDisabled.highSafety());
    }

    private static NeoForgeGoalPolicy policy(String intelligence, String safety) {
        return NeoForgeGoalPolicy.from(Map.of("intelligence", intelligence, "safety", safety,
                "observation", "loaded-chunks", "combatPolicy", "defensive"));
    }
}
