// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSuffocationReflexTest {
    @Test
    void transientInWallBlipsNeverTriggerEscape() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var blip = 0; blip < 5; blip++) {
            for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
                assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
            }
            assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(false));
        }
        assertFalse(reflex.escaping());
    }

    @Test
    void sustainedInWallTriggersEscapeAfterDebounce() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(NeoForgeSuffocationReflex.Action.ESCAPE, reflex.tick(true));
        assertTrue(reflex.escaping());
        assertEquals(NeoForgeSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }

    @Test
    void escapeEndsWithOneShotEscapedSignalWhenFreed() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        assertTrue(reflex.escaping());
        assertEquals(NeoForgeSuffocationReflex.Action.ESCAPED, reflex.tick(false));
        assertFalse(reflex.escaping());
        assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(false));
    }

    @Test
    void escapeBudgetExpiryReturnsControlAndEntersCooldown() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        var expired = false;
        for (var tick = 0; tick < NeoForgeSuffocationReflex.MAX_ESCAPE_TICKS + 1 && !expired; tick++) {
            expired = reflex.tick(true) == NeoForgeSuffocationReflex.Action.BUDGET_EXPIRED;
        }
        assertTrue(expired);
        assertFalse(reflex.escaping());
        // Still embedded during cooldown: control stays with the plan, no immediate re-fire.
        assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
    }

    @Test
    void reflexRearmsAfterCooldownWhileStillEmbedded() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        while (reflex.tick(true) != NeoForgeSuffocationReflex.Action.BUDGET_EXPIRED) {
            // drain the escape budget
        }
        for (var tick = 0; tick < NeoForgeSuffocationReflex.REARM_COOLDOWN_TICKS; tick++) {
            assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(NeoForgeSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }

    @Test
    void leavingTheWallDuringCooldownClearsItImmediately() {
        var reflex = new NeoForgeSuffocationReflex();
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        while (reflex.tick(true) != NeoForgeSuffocationReflex.Action.BUDGET_EXPIRED) {
            // drain the escape budget
        }
        assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(false));
        for (var tick = 0; tick < NeoForgeSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(NeoForgeSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(NeoForgeSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }
}
