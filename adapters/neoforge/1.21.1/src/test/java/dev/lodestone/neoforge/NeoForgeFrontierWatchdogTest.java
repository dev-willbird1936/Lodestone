// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeFrontierWatchdogTest {
    @Test
    void smallOscillationCannotFarmFrontierProgress() {
        var watchdog = new NeoForgeFrontierWatchdog();
        watchdog.begin(7, 6.0);
        NeoForgeFrontierWatchdog.Action action = NeoForgeFrontierWatchdog.Action.NONE;
        for (int tick = 0; tick < NeoForgeFrontierWatchdog.NO_PROGRESS_TICKS; tick++) {
            action = watchdog.tick(7, tick % 2 == 0 ? 5.90 : 6.05);
        }
        assertEquals(NeoForgeFrontierWatchdog.Action.RETRY, action);
        assertFalse(watchdog.active());
    }

    @Test
    void staleFrontierEpochCannotAffectReplacement() {
        var watchdog = new NeoForgeFrontierWatchdog();
        watchdog.begin(7, 6.0);
        watchdog.begin(8, 8.0);
        assertEquals(NeoForgeFrontierWatchdog.Action.NONE, watchdog.tick(7, 0.0));
        assertTrue(watchdog.active());
    }

    @Test
    void threeExplicitFailuresExhaustAndReachResetsBudget() {
        var watchdog = new NeoForgeFrontierWatchdog();
        assertEquals(NeoForgeFrontierWatchdog.Action.RETRY, watchdog.noCandidate());
        assertEquals(NeoForgeFrontierWatchdog.Action.RETRY, watchdog.noCandidate());
        assertEquals(NeoForgeFrontierWatchdog.Action.EXHAUSTED, watchdog.noCandidate());

        var recovered = new NeoForgeFrontierWatchdog();
        recovered.begin(1, 4.0);
        recovered.reached(1);
        assertEquals(0, recovered.failures());
    }
}
