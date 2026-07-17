// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeGoalPolicyCapabilityTest {
    /**
     * Every value the shared engine enum can request must construct a policy here without
     * throwing. This is the regression guard for the deliberate-v1 crash: the adapter keeps its
     * own local {@code Intelligence} tier set (it has no separate behavior for the top engine
     * tier), so a future engine-side addition must fail this loop, not surface as a live
     * IllegalArgumentException at actor construction.
     */
    @Test
    void everyEngineIntelligenceValueConstructsAnAdapterPolicy() {
        for (var engineValue : dev.lodestone.goal.GoalIntelligence.values()) {
            assertDoesNotThrow(() -> policy(engineValue.id(), "balanced"),
                    "adapter policy construction must not throw for engine intelligence: " + engineValue.id());
        }
    }

    @Test
    void deliberateV1RequestsBehaveIdenticallyToAdaptiveV1OnThisAdapter() {
        var deliberate = policy("deliberate-v1", "balanced");
        var adaptive = policy("adaptive-v1", "balanced");
        assertEquals(adaptive.intelligence(), deliberate.intelligence());
        assertEquals(adaptive.intelligenceLayer(), deliberate.intelligenceLayer());
        assertEquals(adaptive.recursivePrerequisitePlanningEnabled(), deliberate.recursivePrerequisitePlanningEnabled());
        assertEquals(adaptive.undergroundPrerequisiteAcquisitionEnabled(), deliberate.undergroundPrerequisiteAcquisitionEnabled());
        assertEquals(adaptive.frontierNoveltyRecoveryEnabled(), deliberate.frontierNoveltyRecoveryEnabled());
        assertEquals(adaptive.actionSegmentReplanningEnabled(), deliberate.actionSegmentReplanningEnabled());

        for (var alias : new String[] {"deliberate", "perfect", "xhigh"}) {
            assertEquals(NeoForgeGoalPolicy.Intelligence.ADAPTIVE_V1, policy(alias, "balanced").intelligence());
        }
    }

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
