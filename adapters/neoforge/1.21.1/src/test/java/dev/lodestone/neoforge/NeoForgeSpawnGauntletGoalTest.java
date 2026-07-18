// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void eastWaypointCandidateOffsetsOnlyTheXAxis() {
        var origin = new BlockPos(10, 64, -5);
        var candidate = NeoForgeSpawnGauntletGoal.eastWaypointCandidate(origin, 32);
        assertEquals(42, candidate.getX());
        assertEquals(64, candidate.getY());
        assertEquals(-5, candidate.getZ());
    }

    @Test
    void eastWaypointCandidateWorksFromNegativeCoordinateOrigins() {
        var origin = new BlockPos(-20, 70, 100);
        var candidate = NeoForgeSpawnGauntletGoal.eastWaypointCandidate(origin, 32);
        assertEquals(12, candidate.getX());
        assertEquals(70, candidate.getY());
        assertEquals(100, candidate.getZ());
    }
}
