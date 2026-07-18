// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSpawnGauntletGoalTest {
    @Test
    void reachingTheWaypointBeforeTheMinimumDurationStillContinues() {
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.CONTINUE,
                NeoForgeSpawnGauntletGoal.decide(1, true));
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.CONTINUE,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MIN_ACTIVE_TICKS - 1, true));
    }

    @Test
    void bothTheMinimumDurationAndAReachedWaypointAreRequiredForSuccess() {
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.CONTINUE,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MIN_ACTIVE_TICKS, false));
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.SUCCEEDED,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MIN_ACTIVE_TICKS, true));
    }

    @Test
    void hittingTheHardCapWithoutReachingTheWaypointIsTargetUnreachableNotADeath() {
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.CONTINUE,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MAX_ACTIVE_TICKS - 1, false));
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.TARGET_UNREACHABLE,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MAX_ACTIVE_TICKS, false));
    }

    @Test
    void reachingTheWaypointExactlyAtTheHardCapStillSucceeds() {
        // A death never reaches this decision at all - it is its own unconditional, immediate
        // throw elsewhere in tick() - so the only two outcomes this function ever needs to
        // distinguish here are a genuine success and a target-unreachable timeout.
        assertEquals(NeoForgeSpawnGauntletGoal.Outcome.SUCCEEDED,
                NeoForgeSpawnGauntletGoal.decide(NeoForgeSpawnGauntletGoal.MAX_ACTIVE_TICKS, true));
    }

    @Test
    void withinWaypointAnnulusRejectsAnythingBelowTheMinimumHorizontalDistance() {
        assertFalse(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(0.0));
        assertFalse(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(17.46));
        assertFalse(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(27.99));
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(28.0));
    }

    @Test
    void withinWaypointAnnulusRejectsAnythingBeyondTheMaximumHorizontalDistance() {
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(48.0));
        assertFalse(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(48.01));
        assertFalse(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(96.0));
    }

    @Test
    void withinWaypointAnnulusAcceptsTheWholeMiddleOfTheRangeIncludingTheIdealThirtyTwoBlockOffset() {
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(28.0));
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(32.0));
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(40.0));
        assertTrue(NeoForgeSpawnGauntletGoal.withinWaypointAnnulus(48.0));
    }

    @Test
    void maxHorizontalDistanceFromIsZeroForAnEmptyReachableSet() {
        var origin = new BlockPos(0, 64, 0);
        assertEquals(0.0, NeoForgeSpawnGauntletGoal.maxHorizontalDistanceFrom(origin, List.of()), 1e-9);
    }

    @Test
    void maxHorizontalDistanceFromFindsTheFarthestPositionRegardlessOfListOrder() {
        var origin = new BlockPos(0, 64, 0);
        var near = new BlockPos(3, 64, 4);
        var far = new BlockPos(30, 70, 0);
        assertEquals(30.0, NeoForgeSpawnGauntletGoal.maxHorizontalDistanceFrom(origin, List.of(near, far)), 1e-9);
        assertEquals(30.0, NeoForgeSpawnGauntletGoal.maxHorizontalDistanceFrom(origin, List.of(far, near)), 1e-9);
    }

    @Test
    void anyWithinWaypointAnnulusIsFalseWhenEveryReachablePositionIsTooCloseOrTooFar() {
        var origin = new BlockPos(0, 64, 0);
        var tooClose = new BlockPos(10, 64, 0);
        var tooFar = new BlockPos(60, 64, 0);
        assertFalse(NeoForgeSpawnGauntletGoal.anyWithinWaypointAnnulus(origin, List.of(tooClose, tooFar)));
    }

    @Test
    void anyWithinWaypointAnnulusIsTrueWhenAtLeastOneReachablePositionFallsInTheBand() {
        var origin = new BlockPos(0, 64, 0);
        var tooClose = new BlockPos(10, 64, 0);
        var inBand = new BlockPos(32, 64, 0);
        assertTrue(NeoForgeSpawnGauntletGoal.anyWithinWaypointAnnulus(origin, List.of(tooClose, inBand)));
    }
}
