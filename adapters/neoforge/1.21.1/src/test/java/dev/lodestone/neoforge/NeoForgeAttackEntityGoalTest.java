// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeAttackEntityGoalTest {
    @Test
    void swingRequiresBothMeleeRangeAndAFullAttackStrengthMeter() {
        assertTrue(NeoForgeAttackEntityGoal.shouldSwing(4.0, 0.9, 2.5));
        assertTrue(NeoForgeAttackEntityGoal.shouldSwing(6.25, 1.0, 2.5));
        assertFalse(NeoForgeAttackEntityGoal.shouldSwing(4.0, 0.89, 2.5));
        assertFalse(NeoForgeAttackEntityGoal.shouldSwing(6.26, 1.0, 2.5));
    }

    @Test
    void chaseBudgetIsMeasuredFromTheStartPositionNotTheCurrentPlayerPosition() {
        var start = new BlockPos(0, 64, 0);
        assertTrue(NeoForgeAttackEntityGoal.withinChaseBudget(start, new BlockPos(20, 64, 0), 24));
        assertTrue(NeoForgeAttackEntityGoal.withinChaseBudget(start, new BlockPos(24, 64, 0), 24));
        assertFalse(NeoForgeAttackEntityGoal.withinChaseBudget(start, new BlockPos(25, 64, 0), 24));
        assertFalse(NeoForgeAttackEntityGoal.withinChaseBudget(start, new BlockPos(0, 64, 40), 24));
    }

    @Test
    void playerEndangeredThresholdIsExclusiveOfEightHearts() {
        assertFalse(NeoForgeAttackEntityGoal.isPlayerEndangered(8.0));
        assertTrue(NeoForgeAttackEntityGoal.isPlayerEndangered(7.99));
        assertTrue(NeoForgeAttackEntityGoal.isPlayerEndangered(0.0));
    }

    @Test
    void targetStateDistinguishesKillFromOutrightLoss() {
        assertEquals(NeoForgeAttackEntityGoal.ResolutionOutcome.CONTINUE,
                NeoForgeAttackEntityGoal.resolveTargetState(true, true));
        assertEquals(NeoForgeAttackEntityGoal.ResolutionOutcome.KILLED,
                NeoForgeAttackEntityGoal.resolveTargetState(true, false));
        assertEquals(NeoForgeAttackEntityGoal.ResolutionOutcome.TARGET_LOST,
                NeoForgeAttackEntityGoal.resolveTargetState(false, false));
        // A vanished entity is reported as absent (present=false) regardless of its last-known
        // alive flag - the caller never has an "alive" reading for a target it can no longer see.
        assertEquals(NeoForgeAttackEntityGoal.ResolutionOutcome.TARGET_LOST,
                NeoForgeAttackEntityGoal.resolveTargetState(false, true));
    }

    @Test
    void replanProgressRequiresMoreThanTheToleranceCloserThanTheBestDistanceSoFar() {
        assertTrue(NeoForgeAttackEntityGoal.replanMadeProgress(10.0, 9.0));
        // Within the noise tolerance: not real progress.
        assertFalse(NeoForgeAttackEntityGoal.replanMadeProgress(10.0, 9.7));
        // No change or getting farther away: never progress.
        assertFalse(NeoForgeAttackEntityGoal.replanMadeProgress(10.0, 10.0));
        assertFalse(NeoForgeAttackEntityGoal.replanMadeProgress(10.0, 11.0));
    }

    @Test
    void stagnantReplanCountResetsOnProgressAndAccumulatesOtherwise() {
        assertEquals(0, NeoForgeAttackEntityGoal.nextStagnantReplanCount(true, 2));
        assertEquals(1, NeoForgeAttackEntityGoal.nextStagnantReplanCount(false, 0));
        assertEquals(3, NeoForgeAttackEntityGoal.nextStagnantReplanCount(false, 2));
    }

    /**
     * Regression coverage for the live-caught bug where {@code attack_entity} burned its entire
     * timeout budget (1201 ticks, hits:0) chasing a target walled off by real terrain (a spider in
     * a cave below the player) instead of failing fast: the movement engine kept reporting MOVING
     * - never an outright NO_ROUTE, e.g. because its own lateral-detour retry kept finding SOME
     * nearby reachable point - so the pre-existing NO_ROUTE check alone never fired. This is the
     * decision core of the N=3-consecutive-stagnant-replans fallback that catches that case: it
     * must abort well within a small, bounded number of replans (never anywhere close to the full
     * timeout budget), while a target the chase is actually gaining on must never be flagged.
     */
    @Test
    void unreachableApproachAbortsWithinABoundedReplanCountButNeverForGenuineProgress() {
        var stagnant = 0;
        for (var replan = 1; replan <= 2; replan++) {
            stagnant = NeoForgeAttackEntityGoal.nextStagnantReplanCount(false, stagnant);
            assertFalse(NeoForgeAttackEntityGoal.unreachableApproach(stagnant),
                    "must not abort before the stagnation limit is reached, replan " + replan);
        }
        stagnant = NeoForgeAttackEntityGoal.nextStagnantReplanCount(false, stagnant);
        assertTrue(NeoForgeAttackEntityGoal.unreachableApproach(stagnant),
                "must abort promptly once three consecutive replans in a row made no progress");

        // A chase that keeps closing the distance every replan must never trip the fallback, no
        // matter how many replans it takes.
        var closingDistance = 20.0;
        var progressingStagnant = 0;
        for (var replan = 1; replan <= 20; replan++) {
            closingDistance -= 1.0;
            var madeProgress = NeoForgeAttackEntityGoal.replanMadeProgress(closingDistance + 1.0, closingDistance);
            progressingStagnant = NeoForgeAttackEntityGoal.nextStagnantReplanCount(madeProgress, progressingStagnant);
            assertFalse(NeoForgeAttackEntityGoal.unreachableApproach(progressingStagnant),
                    "a normal, progressing approach must never be treated as unreachable, replan " + replan);
        }
    }
}
