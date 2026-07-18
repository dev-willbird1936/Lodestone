// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSurvivalTreeGoalTest {
    @Test
    void aFullCubeBoundingBoxIsAcceptedAsTableSupport() {
        assertTrue(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
    }

    @Test
    void aBoundingBoxThatOverfillsTheUnitCubeIsStillAccepted() {
        // A support's shape only needs to cover the unit cube, not match it exactly - a block
        // with a slightly larger collision envelope is at least as reliable to aim at as a
        // genuine full cube.
        assertTrue(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(-0.05, -0.05, -0.05, 1.05, 1.05, 1.05)));
    }

    @Test
    void aBottomSlabHeightBoundingBoxIsRejected() {
        assertFalse(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)));
    }

    @Test
    void aFarmlandOrPathHeightBoundingBoxIsRejected() {
        assertFalse(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(0.0, 0.0, 0.0, 1.0, 0.9375, 1.0)));
    }

    @Test
    void aThinSnowLayerBoundingBoxIsRejected() {
        assertFalse(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(0.0, 0.0, 0.0, 1.0, 0.125, 1.0)));
    }

    @Test
    void aNarrowerHorizontalFootprintIsRejectedEvenAtFullHeight() {
        // A fence post or similar partial-footprint shape must not pass just because its
        // vertical extent happens to reach the full cube height.
        assertFalse(NeoForgeSurvivalTreeGoal.isFullUnitCube(new AABB(0.3, 0.0, 0.3, 0.7, 1.0, 0.7)));
    }

    @Test
    void firstVantageAttemptReproducesTheOriginalUnwidenedBounds() {
        var target = new BlockPos(10, 70, 10);
        var treeBase = new BlockPos(10, 65, 10);
        var bounds = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, 1);
        assertEquals(4, bounds.maxRadius());
        assertEquals(64, bounds.minY());
        assertEquals(71, bounds.maxY());
    }

    @Test
    void laterVantageAttemptsWidenBothTheRingAndTheVerticalBand() {
        var target = new BlockPos(10, 70, 10);
        var treeBase = new BlockPos(10, 65, 10);
        var first = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, 1);
        var second = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, 2);
        var third = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, 3);
        var fourth = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, 4);

        assertTrue(second.maxRadius() > first.maxRadius());
        assertTrue(third.maxRadius() > second.maxRadius());
        assertTrue(fourth.maxRadius() > third.maxRadius());

        assertTrue(second.minY() < first.minY());
        assertTrue(third.minY() < second.minY());
        assertTrue(fourth.minY() < third.minY());

        assertTrue(second.maxY() > first.maxY());
        assertTrue(third.maxY() > second.maxY());
        assertTrue(fourth.maxY() > third.maxY());
    }

    @Test
    void vantageSearchNeverShrinksAcrossTheBoundedAttemptBudget() {
        var target = new BlockPos(-4, 62, 100);
        var treeBase = new BlockPos(-4, 60, 100);
        NeoForgeSurvivalTreeGoal.VantageSearchBounds previous = null;
        for (var attempt = 1; attempt <= NeoForgeSurvivalTreeGoalTest.MAX_MINING_VANTAGE_ATTEMPTS; attempt++) {
            var bounds = NeoForgeSurvivalTreeGoal.vantageSearchBounds(target, treeBase, attempt);
            if (previous != null) {
                assertTrue(bounds.maxRadius() >= previous.maxRadius());
                assertTrue(bounds.minY() <= previous.minY());
                assertTrue(bounds.maxY() >= previous.maxY());
            }
            previous = bounds;
        }
    }

    private static final int MAX_MINING_VANTAGE_ATTEMPTS = 4;

    // Regression coverage for the mineResource()/mineTarget() gating flow itself, not just the
    // pure geometry helpers either fed into. The regression this locks in: a raycast miss against
    // the primary mining target used to fall straight through to vantage relocation - and, at
    // guarded-v1 where obstructionMiningEnabled() is always false, straight to giving up - with no
    // way back, even when the miss landed on something harmless (a leaf, a vine, or in
    // mineTarget()'s case a redirect-eligible log of the same tree) that should just get attacked.

    @Test
    void aimingDirectlyAtThePrimaryTargetIsAlwaysAttackable() {
        assertTrue(NeoForgeSurvivalTreeGoal.isAttackableMiningStance(true, false));
    }

    @Test
    void missingThePrimaryTargetWithNoAlternateIsNotAttackable() {
        // This is exactly the case that must fall through to vantage relocation / the adaptive
        // obstruction gate rather than attack blindly - the pre-regression-fix bug was never
        // getting here in the first place because "aimedAtTarget" was the only signal considered.
        assertFalse(NeoForgeSurvivalTreeGoal.isAttackableMiningStance(false, false));
    }

    @Test
    void missingThePrimaryTargetButHittingFoliageOrARedirectIsStillAttackable() {
        // This is the regression itself: a raycast miss against the log that lands on leaves (or,
        // in mineTarget(), a redirect-eligible log) must still be treated as attackable
        // independent of policy tier, instead of being routed into vantage relocation and, at
        // guarded-v1, target:obstructed.
        assertTrue(NeoForgeSurvivalTreeGoal.isAttackableMiningStance(false, true));
    }

    @Test
    void aimingAtThePrimaryTargetIsAttackableEvenWithoutAnAlternate() {
        // Redundant coverage on purpose: confirms the gate is a genuine OR, not e.g. an AND typo
        // or a condition that only fires when both signals happen to agree.
        assertTrue(NeoForgeSurvivalTreeGoal.isAttackableMiningStance(true, true));
    }
}
