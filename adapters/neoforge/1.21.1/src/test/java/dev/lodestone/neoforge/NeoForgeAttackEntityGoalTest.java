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
}
