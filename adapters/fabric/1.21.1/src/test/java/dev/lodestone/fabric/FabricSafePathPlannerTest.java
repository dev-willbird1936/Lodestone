// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricSafePathPlannerTest {
    @Test
    void cornerClearRequiresOnlyOneFlankUnderLowOrBalancedSafetyButBothUnderHighSafety() {
        assertTrue(FabricSafePathPlanner.cornerClear(true, false, false));
        assertTrue(FabricSafePathPlanner.cornerClear(false, true, false));
        assertTrue(FabricSafePathPlanner.cornerClear(true, true, false));
        assertFalse(FabricSafePathPlanner.cornerClear(false, false, false));

        assertTrue(FabricSafePathPlanner.cornerClear(true, true, true));
        assertFalse(FabricSafePathPlanner.cornerClear(true, false, true));
        assertFalse(FabricSafePathPlanner.cornerClear(false, true, true));
        assertFalse(FabricSafePathPlanner.cornerClear(false, false, true));
    }

    @Test
    void diagonalEdgesCostSqrtTwoInsteadOfOneHorizontally() {
        var policy = policy("guarded-v1", "balanced");
        var origin = new BlockPos(0, 64, 0);
        var cardinal = origin.relative(Direction.NORTH);
        var diagonal = origin.relative(Direction.NORTH).relative(Direction.EAST);

        assertEquals(1.0, FabricSafePathPlanner.edgeCost(origin, cardinal, policy), 1e-9);
        assertEquals(Math.sqrt(2.0), FabricSafePathPlanner.edgeCost(origin, diagonal, policy), 1e-9);
    }

    @Test
    void descentPenaltiesStackWithSafetyAndIntelligenceExactlyAsBefore() {
        var origin = new BlockPos(0, 64, 0);
        var below = origin.below();

        // balanced + guarded: base 1.0 + 1 vertical * 1.5 (not high-safety) + 1.0 balanced-descent
        assertEquals(3.5, FabricSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "balanced")), 1e-9);
        // high safety + guarded: base 1.0 + 1 vertical * 4.0 (high-safety), no balanced/adaptive add-on
        assertEquals(5.0, FabricSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "high")), 1e-9);
        // low safety + adaptive: base 1.0 + 1 vertical * 1.5 (not high-safety) + 12.0 adaptive-descent
        assertEquals(14.5, FabricSafePathPlanner.edgeCost(origin, below, policy("adaptive-v1", "low")), 1e-9);
    }

    @Test
    void fallDamageIsZeroWithinTheSafeFallDistanceOrWhenNotDescending() {
        assertEquals(0.0, FabricSafePathPlanner.estimatedFallDamage(3, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(0.0, FabricSafePathPlanner.estimatedFallDamage(1, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(0.0, FabricSafePathPlanner.estimatedFallDamage(0, 3.0, 1.0, 0, false), 1e-9);
    }

    @Test
    void fallDamageBeyondSafeDistanceMatchesTheRealVanillaFormula() {
        assertEquals(2.0, FabricSafePathPlanner.estimatedFallDamage(5, 3.0, 1.0, 0, false), 1e-9);
        assertEquals(4.0, FabricSafePathPlanner.estimatedFallDamage(7, 3.0, 1.0, 0, false), 1e-9);
        // fallDamageMultiplier scales the raw damage before feather falling is applied.
        assertEquals(4.0, FabricSafePathPlanner.estimatedFallDamage(5, 3.0, 2.0, 0, false), 1e-9);
    }

    @Test
    void featherFallingReducesDamageByItsRealProtectionCurveCappedAtTwenty() {
        // level 4: protectionValue = min(12, 20) = 12 -> 48% reduction
        assertEquals(3.64, FabricSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 4, false), 1e-9);
        // level 7 would be protectionValue=21, capped at 20 -> 80% reduction, same as any level >= 7
        assertEquals(1.4, FabricSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 7, false), 1e-9);
        assertEquals(1.4, FabricSafePathPlanner.estimatedFallDamage(10, 3.0, 1.0, 20, false), 1e-9);
    }

    @Test
    void slowFallingOrLevitationAlwaysFullyNegatesFallDamage() {
        assertEquals(0.0, FabricSafePathPlanner.estimatedFallDamage(50, 3.0, 1.0, 0, true), 1e-9);
    }

    @Test
    void descentAllowedNeverRestrictsLevelOrAscendingSteps() {
        assertTrue(FabricSafePathPlanner.descentAllowed(0, 1));
        assertTrue(FabricSafePathPlanner.descentAllowed(-1, 1));
        assertTrue(FabricSafePathPlanner.descentAllowed(-5, 0));
    }

    @Test
    void descentAllowedDefaultCapMatchesTheOriginalHardcodedOneBlockRuleExactly() {
        assertTrue(FabricSafePathPlanner.descentAllowed(1, FabricGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS));
        assertFalse(FabricSafePathPlanner.descentAllowed(2, FabricGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS));
    }

    @Test
    void descentAllowedRespectsAWidenedCapUpToButNotBeyondIt() {
        assertTrue(FabricSafePathPlanner.descentAllowed(2, 3));
        assertTrue(FabricSafePathPlanner.descentAllowed(3, 3));
        assertFalse(FabricSafePathPlanner.descentAllowed(4, 3));
    }

    @Test
    void mineCandidateRequiresASolidObstructionOverIntactSupportAndNoFluid() {
        // feet solid, support intact, no fluid: obstruction in the feet cell alone.
        assertTrue(FabricSafePathPlanner.mineCandidateEligible(true, false, true, true, true));
        // head solid instead: still an obstruction (2-tall hitbox), still eligible.
        assertTrue(FabricSafePathPlanner.mineCandidateEligible(false, true, true, true, true));
        // both solid: still a single-edge mine, both cells collected as targets by the caller.
        assertTrue(FabricSafePathPlanner.mineCandidateEligible(true, true, true, true, true));
        // neither feet nor head solid: nothing to mine.
        assertFalse(FabricSafePathPlanner.mineCandidateEligible(false, false, true, true, true));
        // missing support: this is the place case, not mine, even with a solid feet cell.
        assertFalse(FabricSafePathPlanner.mineCandidateEligible(true, false, false, true, true));
        // fluid present: mining wouldn't produce a walkable cell, never eligible.
        assertFalse(FabricSafePathPlanner.mineCandidateEligible(true, false, true, false, true));
        assertFalse(FabricSafePathPlanner.mineCandidateEligible(true, false, true, true, false));
    }

    @Test
    void placeCandidateRequiresAnOpenFeetAndHeadOverAMissingSupportAndNoFluid() {
        // clear feet/head, no fluid, missing support: a genuine floor gap.
        assertTrue(FabricSafePathPlanner.placeCandidateEligible(false, false, false, true, true));
        // support already intact: nothing to fill.
        assertFalse(FabricSafePathPlanner.placeCandidateEligible(false, false, true, true, true));
        // feet or head obstructed: this is the mine case, not place.
        assertFalse(FabricSafePathPlanner.placeCandidateEligible(true, false, false, true, true));
        assertFalse(FabricSafePathPlanner.placeCandidateEligible(false, true, false, true, true));
        // fluid present: placement site isn't safe to consider here.
        assertFalse(FabricSafePathPlanner.placeCandidateEligible(false, false, false, false, true));
        assertFalse(FabricSafePathPlanner.placeCandidateEligible(false, false, false, true, false));
    }

    private static FabricGoalPolicy policy(String intelligence, String safety) {
        return FabricGoalPolicy.from(Map.of("intelligence", intelligence, "safety", safety));
    }
}
