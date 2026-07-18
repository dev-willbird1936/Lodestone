// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

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
}
