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

    @Test
    void obstructionPlacementIsSymmetricToObstructionMiningGatedOnAdaptiveIntelligenceAndItsOwnAllowFlag() {
        var adaptive = policy("adaptive-v1", "balanced");
        assertTrue(adaptive.obstructionMiningEnabled());
        assertTrue(adaptive.obstructionPlacementEnabled());

        for (var intelligence : new String[] {"raw-v1", "guarded-v1"}) {
            var lowerTier = policy(intelligence, "balanced");
            assertFalse(lowerTier.obstructionMiningEnabled());
            assertFalse(lowerTier.obstructionPlacementEnabled());
        }

        var placingDisabled = NeoForgeGoalPolicy.from(Map.of("intelligence", "adaptive-v1",
                "safety", "balanced", "allowBlockPlacing", false));
        assertTrue(placingDisabled.obstructionMiningEnabled());
        assertFalse(placingDisabled.obstructionPlacementEnabled());
    }

    /**
     * Regression guard for the descent-cap widening: every actor that still builds its policy via
     * {@code .from()} directly (i.e. everyone except {@code NeoForgeSpawnGauntletGoal}, which
     * derives its own copy) must keep today's exact 1-block descent cap regardless of intelligence
     * or safety tier - this is deliberately not gated by either dimension, unlike every other
     * capability above.
     */
    @Test
    void maxDescentBlocksDefaultsToOneForEveryIntelligenceAndSafetyCombination() {
        for (var intelligence : new String[] {"raw-v1", "guarded-v1", "adaptive-v1"}) {
            for (var safety : new String[] {"low", "balanced", "high"}) {
                assertEquals(1, policy(intelligence, safety).maxDescentBlocks(),
                        "unexpected default descent cap for " + intelligence + "/" + safety);
            }
        }
        assertEquals(NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS, policy("guarded-v1", "balanced").maxDescentBlocks());
    }

    @Test
    void withMaxDescentBlocksDerivesAWidenedCopyWithoutChangingAnyOtherField() {
        var base = policy("guarded-v1", "balanced");
        var widened = base.withMaxDescentBlocks(3);

        assertEquals(3, widened.maxDescentBlocks());
        assertEquals(1, base.maxDescentBlocks(), "the original policy instance must be unaffected");
        assertEquals(base.intelligence(), widened.intelligence());
        assertEquals(base.safety(), widened.safety());
        assertEquals(base.observation(), widened.observation());
        assertEquals(base.combatPolicy(), widened.combatPolicy());
        assertEquals(base.allowBlockBreaking(), widened.allowBlockBreaking());
        assertEquals(base.allowBlockPlacing(), widened.allowBlockPlacing());
        assertEquals(base.allowCommands(), widened.allowCommands());
    }

    private static NeoForgeGoalPolicy policy(String intelligence, String safety) {
        return NeoForgeGoalPolicy.from(Map.of("intelligence", intelligence, "safety", safety,
                "observation", "loaded-chunks", "combatPolicy", "defensive"));
    }
}
