// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSurviveNightGoalTest {
    @Test
    void nightWindowMatchesTheVanillaSpawnableRange() {
        assertFalse(NeoForgeSurviveNightGoal.isNightNow(0));
        assertFalse(NeoForgeSurviveNightGoal.isNightNow(11999));
        assertTrue(NeoForgeSurviveNightGoal.isNightNow(12000));
        assertTrue(NeoForgeSurviveNightGoal.isNightNow(20000));
        assertTrue(NeoForgeSurviveNightGoal.isNightNow(23499));
        assertFalse(NeoForgeSurviveNightGoal.isNightNow(23500));
    }

    @Test
    void nightWindowWrapsAcrossMultiDayGameTime() {
        // dayTime accumulates across every day lived, never resetting to 0 - the same time-of-day
        // window must still be recognized on day 4, day 40, and so on.
        assertTrue(NeoForgeSurviveNightGoal.isNightNow(24000 + 13000));
        assertTrue(NeoForgeSurviveNightGoal.isNightNow(24000 * 40 + 20000));
        assertFalse(NeoForgeSurviveNightGoal.isNightNow(24000 * 40 + 500));
    }

    @Test
    void dawnThresholdIsExclusiveOfTheWrapBoundary() {
        assertTrue(NeoForgeSurviveNightGoal.dawnReached(0));
        assertTrue(NeoForgeSurviveNightGoal.dawnReached(799));
        assertFalse(NeoForgeSurviveNightGoal.dawnReached(800));
        assertTrue(NeoForgeSurviveNightGoal.dawnReached(24000 * 12 + 200));
        assertFalse(NeoForgeSurviveNightGoal.dawnReached(23999));
    }

    @Test
    void shaftPlanDigsStraightDownFromTheStartingCell() {
        var start = new BlockPos(0, 64, 0);
        var cells = NeoForgeSurviveNightGoal.planShaftCells(start, 2, pos -> false);

        assertEquals(2, cells.size());
        assertEquals(new BlockPos(0, 63, 0), cells.get(0));
        assertEquals(new BlockPos(0, 62, 0), cells.get(1));
    }

    @Test
    void shaftPlanStopsBeforeAHazardousCellRatherThanDiggingIntoIt() {
        var start = new BlockPos(0, 64, 0);
        var lavaAtDepthTwo = Set.of(new BlockPos(0, 62, 0));
        var cells = NeoForgeSurviveNightGoal.planShaftCells(start, 3, lavaAtDepthTwo::contains);

        assertEquals(1, cells.size(), "only the safe first cell should be planned");
        assertEquals(new BlockPos(0, 63, 0), cells.get(0));
    }

    @Test
    void shaftPlanIsEmptyWhenEvenTheFirstCellIsUnsafe() {
        var start = new BlockPos(0, 64, 0);
        var cells = NeoForgeSurviveNightGoal.planShaftCells(start, 2, pos -> true);

        assertTrue(cells.isEmpty());
    }
}
