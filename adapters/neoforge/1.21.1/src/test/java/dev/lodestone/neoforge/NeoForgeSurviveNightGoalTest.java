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

    /**
     * Regression coverage for the live-caught "no-shelter-material without ever digging" bug:
     * {@code beginSeal} used to check the inventory the instant digging finished, racing vanilla's
     * own drop pickup delay. The fix decides on a genuine before/after delta instead of "is any
     * block item present now", which a pre-existing, unrelated inventory item could satisfy without
     * the dig having contributed anything.
     */
    @Test
    void materialCollectedRequiresAGenuineIncreaseOverThePreDigSnapshot() {
        assertTrue(NeoForgeSurviveNightGoal.materialCollected(2, 0));
        assertTrue(NeoForgeSurviveNightGoal.materialCollected(3, 2));
        assertFalse(NeoForgeSurviveNightGoal.materialCollected(0, 0));
        // Pre-existing inventory contents alone (no increase since before the dig) must not count.
        assertFalse(NeoForgeSurviveNightGoal.materialCollected(5, 5));
    }

    /**
     * Regression coverage for the live-caught "dig stalls to a deadline on a no-drop block" bug:
     * ordinary shelter topsoil always yields a drop bare-handed, but stone/ore/etc. never do,
     * wasting the whole mine attempt before finding out. Checked before spending any mining ticks.
     */
    @Test
    void onlyKnownHandDiggableSoilYieldsAUsableDrop() {
        assertTrue(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("minecraft:dirt"));
        assertTrue(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("minecraft:grass_block"));
        assertTrue(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("minecraft:sand"));
        assertTrue(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("MINECRAFT:DIRT"), "matching must be case-insensitive");
        assertFalse(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("minecraft:stone"));
        assertFalse(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop("minecraft:iron_ore"));
        assertFalse(NeoForgeSurviveNightGoal.yieldsHandDiggableDrop(null));
    }
}
