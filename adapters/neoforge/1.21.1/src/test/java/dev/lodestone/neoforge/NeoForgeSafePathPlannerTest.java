// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSafePathPlannerTest {
    @Test
    void cornerClearRequiresOnlyOneFlankUnderLowOrBalancedSafetyButBothUnderHighSafety() {
        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, false, false));
        assertTrue(NeoForgeSafePathPlanner.cornerClear(false, true, false));
        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, true, false));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, false, false));

        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, true, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(true, false, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, true, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, false, true));
    }

    @Test
    void diagonalEdgesCostSqrtTwoInsteadOfOneHorizontally() {
        var policy = policy("guarded-v1", "balanced");
        var origin = new BlockPos(0, 64, 0);
        var cardinal = origin.relative(Direction.NORTH);
        var diagonal = origin.relative(Direction.NORTH).relative(Direction.EAST);

        assertEquals(1.0, NeoForgeSafePathPlanner.edgeCost(origin, cardinal, policy), 1e-9);
        assertEquals(Math.sqrt(2.0), NeoForgeSafePathPlanner.edgeCost(origin, diagonal, policy), 1e-9);
    }

    @Test
    void descentPenaltiesStackWithSafetyAndIntelligenceExactlyAsBefore() {
        var origin = new BlockPos(0, 64, 0);
        var below = origin.below();

        // balanced + guarded: base 1.0 + 1 vertical * 1.5 (not high-safety) + 1.0 balanced-descent
        assertEquals(3.5, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "balanced")), 1e-9);
        // high safety + guarded: base 1.0 + 1 vertical * 4.0 (high-safety), no balanced/adaptive add-on
        assertEquals(5.0, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "high")), 1e-9);
        // low safety + adaptive: base 1.0 + 1 vertical * 1.5 (not high-safety) + 12.0 adaptive-descent
        assertEquals(14.5, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("adaptive-v1", "low")), 1e-9);
    }

    @Test
    void fallDamageIsZeroWithinTheSafeFallDistanceOrWhenNotDescending() {
        assertEquals(0.0, NeoForgeSafePathPlanner.estimatedFallDamage(3, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(0.0, NeoForgeSafePathPlanner.estimatedFallDamage(1, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(0.0, NeoForgeSafePathPlanner.estimatedFallDamage(0, 3.0, 1.0, 0, false), 1e-9);
    }

    @Test
    void fallDamageBeyondSafeDistanceMatchesTheRealVanillaFormula() {
        assertEquals(2.0, NeoForgeSafePathPlanner.estimatedFallDamage(5, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(4.0, NeoForgeSafePathPlanner.estimatedFallDamage(7, 3.0, 1.0, 0, false), 1e-9);
        // fallDamageMultiplier scales the raw damage before feather falling is applied.
        assertEquals(4.0, NeoForgeSafePathPlanner.estimatedFallDamage(5, 3.0, 2.0, 0, false), 1e-9);
    }

    @Test
    void featherFallingReducesDamageByItsRealProtectionCurveCappedAtTwenty() {
        // level 4: protectionValue = min(12, 20) = 12 -> 48% reduction
        assertEquals(3.64, NeoForgeSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 4, false), 1e-9);
        // level 7 would be protectionValue=21, capped at 20 -> 80% reduction, same as any level >= 7
        assertEquals(1.4, NeoForgeSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 7, false), 1e-9);
        assertEquals(1.4, NeoForgeSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 20, false), 1e-9);
    }

    @Test
    void slowFallingOrLevitationAlwaysFullyNegatesFallDamage() {
        assertEquals(0.0, NeoForgeSafePathPlanner.estimatedFallDamage(50, 3.0, 1.0, 0, true), 1e-9);
    }

    @Test
    void descentAllowedNeverRestrictsLevelOrAscendingSteps() {
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(0, 1));
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(-1, 1));
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(-5, 0));
    }

    @Test
    void descentAllowedDefaultCapMatchesTheOriginalHardcodedOneBlockRuleExactly() {
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(1, NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS));
        assertFalse(NeoForgeSafePathPlanner.descentAllowed(2, NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS));
    }

    @Test
    void descentAllowedRespectsAWidenedCapUpToButNotBeyondIt() {
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(2, 3));
        assertTrue(NeoForgeSafePathPlanner.descentAllowed(3, 3));
        assertFalse(NeoForgeSafePathPlanner.descentAllowed(4, 3));
    }

    @Test
    void mineCandidateRequiresASolidObstructionOverIntactSupportAndNoFluid() {
        // feet solid, support intact, no fluid: obstruction in the feet cell alone.
        assertTrue(NeoForgeSafePathPlanner.mineCandidateEligible(true, false, true, true, true));
        // head solid instead: still an obstruction (2-tall hitbox), still eligible.
        assertTrue(NeoForgeSafePathPlanner.mineCandidateEligible(false, true, true, true, true));
        // both solid: still a single-edge mine, both cells collected as targets by the caller.
        assertTrue(NeoForgeSafePathPlanner.mineCandidateEligible(true, true, true, true, true));
        // neither feet nor head solid: nothing to mine.
        assertFalse(NeoForgeSafePathPlanner.mineCandidateEligible(false, false, true, true, true));
        // missing support: this is the place case, not mine, even with a solid feet cell.
        assertFalse(NeoForgeSafePathPlanner.mineCandidateEligible(true, false, false, true, true));
        // fluid present: mining wouldn't produce a walkable cell, never eligible.
        assertFalse(NeoForgeSafePathPlanner.mineCandidateEligible(true, false, true, false, true));
        assertFalse(NeoForgeSafePathPlanner.mineCandidateEligible(true, false, true, true, false));
    }

    @Test
    void placeCandidateRequiresAnOpenFeetAndHeadOverAMissingSupportAndNoFluid() {
        // clear feet/head, no fluid, missing support: a genuine floor gap.
        assertTrue(NeoForgeSafePathPlanner.placeCandidateEligible(false, false, false, true, true));
        // support already intact: nothing to fill.
        assertFalse(NeoForgeSafePathPlanner.placeCandidateEligible(false, false, true, true, true));
        // feet or head obstructed: this is the mine case, not place.
        assertFalse(NeoForgeSafePathPlanner.placeCandidateEligible(true, false, false, true, true));
        assertFalse(NeoForgeSafePathPlanner.placeCandidateEligible(false, true, false, true, true));
        // fluid present: placement site isn't safe to consider here.
        assertFalse(NeoForgeSafePathPlanner.placeCandidateEligible(false, false, false, false, true));
        assertFalse(NeoForgeSafePathPlanner.placeCandidateEligible(false, false, false, true, false));
    }

    private static NeoForgeGoalPolicy policy(String intelligence, String safety) {
        return NeoForgeGoalPolicy.from(Map.of("intelligence", intelligence, "safety", safety));
    }
}
