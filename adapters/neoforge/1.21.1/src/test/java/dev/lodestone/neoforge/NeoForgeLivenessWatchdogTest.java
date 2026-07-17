// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeLivenessWatchdogTest {
    @Test
    void yawOnlyCannotPreventBoundedLivenessFailure() {
        var watchdog = new NeoForgeLivenessWatchdog();
        NeoForgeLivenessWatchdog.TickResult result = null;
        var recoveries = 0;
        for (int tick = 0; tick <= NeoForgeLivenessWatchdog.STALL_TICKS * 4; tick++) {
            result = watchdog.tick(sample(0, 0, 0, 1, 7));
            if (result.action() == NeoForgeLivenessWatchdog.Action.RECOVER) recoveries++;
            if (result.action() == NeoForgeLivenessWatchdog.Action.LIVENESS_EXHAUSTED) break;
        }
        assertEquals(NeoForgeLivenessWatchdog.MAX_RECOVERIES, recoveries);
        assertEquals(NeoForgeLivenessWatchdog.Action.LIVENESS_EXHAUSTED, result.action());
    }

    @Test
    void actionStageOscillationCannotResetActivityEpoch() {
        var watchdog = new NeoForgeLivenessWatchdog();
        NeoForgeLivenessWatchdog.TickResult result = null;
        for (int tick = 0; tick <= NeoForgeLivenessWatchdog.STALL_TICKS; tick++) {
            var rawStage = tick % 2 == 0 ? "FIND_TREE" : "NAVIGATE_TREE";
            result = watchdog.tick(new NeoForgeLivenessWatchdog.Sample(
                    NeoForgeLivenessWatchdog.activityKey(rawStage), 0, 0, 0, 1, 7, true));
        }
        assertEquals("TREE", NeoForgeLivenessWatchdog.activityKey("COLLECT_TREE"));
        assertEquals(NeoForgeLivenessWatchdog.Action.RECOVER, result.action());
    }

    @Test
    void smallLocalOscillationDoesNotCountAsSustainedDisplacement() {
        var watchdog = new NeoForgeLivenessWatchdog();
        NeoForgeLivenessWatchdog.TickResult result = null;
        var positions = new double[][]{{-1, 65, 9}, {-2, 63, 9}, {-1, 64, 9}};
        for (int tick = 0; tick < NeoForgeLivenessWatchdog.STALL_TICKS + 6; tick++) {
            var position = positions[tick % positions.length];
            result = watchdog.tick(new NeoForgeLivenessWatchdog.Sample("TREE",
                    position[0], position[1], position[2], 4, 100 + tick % positions.length, true));
            if (result.action() == NeoForgeLivenessWatchdog.Action.RECOVER) break;
        }
        assertEquals(NeoForgeLivenessWatchdog.Action.RECOVER, result.action());
    }

    @Test
    void novelWaypointInventoryAndWorldNoveltyAreMeaningfulProgress() {
        var watchdog = new NeoForgeLivenessWatchdog();
        watchdog.tick(sample(0, 0, 0, 1, 7));
        for (int tick = 0; tick < 80; tick++) watchdog.tick(sample(0, 0, 0, 1, 7));
        assertEquals(0, watchdog.tick(sample(4, 0, 0, 1, 7)).ticksSinceMeaningfulProgress());
        for (int tick = 0; tick < 80; tick++) watchdog.tick(sample(4, 0, 0, 1, 7));
        assertEquals(0, watchdog.tick(sample(4, 0, 0, 2, 7)).ticksSinceMeaningfulProgress());
        for (int tick = 0; tick < 80; tick++) watchdog.tick(sample(4, 0, 0, 2, 7));
        assertEquals(0, watchdog.tick(sample(4, 0, 0, 2, 8)).ticksSinceMeaningfulProgress());
    }

    @Test
    void boundedPrerequisiteTransactionOwnsLivenessWithoutDisablingNormalWatchdog() {
        assertFalse(NeoForgeNetherGoal.genericLivenessEnabled("PLAN_PREREQUISITE_ACQUISITION"));
        assertFalse(NeoForgeNetherGoal.genericLivenessEnabled("EXCAVATE_PREREQUISITE_ROUTE"));
        assertFalse(NeoForgeNetherGoal.genericLivenessEnabled("COLLECT_STARTER_RESOURCE"));
        assertFalse(NeoForgeNetherGoal.genericLivenessEnabled("EXCAVATE_PREREQUISITE_ROUTE", 1));
        assertFalse(NeoForgeNetherGoal.genericLivenessEnabled("EXCAVATE_PREREQUISITE_ROUTE", 2));
        assertTrue(NeoForgeNetherGoal.genericLivenessEnabled("FIND_STONE"));
        assertTrue(NeoForgeNetherGoal.genericLivenessEnabled("NAVIGATE_STARTER_RESOURCE"));

        var watchdog = new NeoForgeLivenessWatchdog();
        NeoForgeLivenessWatchdog.TickResult result = null;
        for (int tick = 0; tick <= NeoForgeLivenessWatchdog.STALL_TICKS * 4; tick++) {
            result = watchdog.tick(new NeoForgeLivenessWatchdog.Sample("EXCAVATE_PREREQUISITE_ROUTE",
                    0, 0, 0, 1, 7,
                    NeoForgeNetherGoal.genericLivenessEnabled("EXCAVATE_PREREQUISITE_ROUTE")));
        }
        assertEquals(NeoForgeLivenessWatchdog.Action.NONE, result.action());
        assertEquals(0, result.recoveryCount());

        for (int tick = 0; tick <= NeoForgeLivenessWatchdog.STALL_TICKS; tick++) {
            result = watchdog.tick(new NeoForgeLivenessWatchdog.Sample("FIND_STONE",
                    0, 0, 0, 1, 7, NeoForgeNetherGoal.genericLivenessEnabled("FIND_STONE")));
        }
        assertEquals(NeoForgeLivenessWatchdog.Action.RECOVER, result.action());
    }

    private static NeoForgeLivenessWatchdog.Sample sample(double x, double y, double z,
                                                           int inventory, long world) {
        return new NeoForgeLivenessWatchdog.Sample("FIND_STONE", x, y, z, inventory, world, true);
    }
}
