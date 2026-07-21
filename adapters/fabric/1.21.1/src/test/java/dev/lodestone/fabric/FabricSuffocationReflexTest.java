// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricSuffocationReflexTest {
    @Test
    void transientInWallBlipsNeverTriggerEscape() {
        var reflex = new FabricSuffocationReflex();
        for (var blip = 0; blip < 5; blip++) {
            for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
                assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
            }
            assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(false));
        }
        assertFalse(reflex.escaping());
    }

    @Test
    void sustainedInWallTriggersEscapeAfterDebounce() {
        var reflex = new FabricSuffocationReflex();
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(FabricSuffocationReflex.Action.ESCAPE, reflex.tick(true));
        assertTrue(reflex.escaping());
        assertEquals(FabricSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }

    @Test
    void escapeEndsWithOneShotEscapedSignalWhenFreed() {
        var reflex = new FabricSuffocationReflex();
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        assertTrue(reflex.escaping());
        assertEquals(FabricSuffocationReflex.Action.ESCAPED, reflex.tick(false));
        assertFalse(reflex.escaping());
        assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(false));
    }

    @Test
    void escapeBudgetExpiryReturnsControlAndEntersCooldown() {
        var reflex = new FabricSuffocationReflex();
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        var expired = false;
        for (var tick = 0; tick < FabricSuffocationReflex.MAX_ESCAPE_TICKS + 1 && !expired; tick++) {
            expired = reflex.tick(true) == FabricSuffocationReflex.Action.BUDGET_EXPIRED;
        }
        assertTrue(expired);
        assertFalse(reflex.escaping());
        // Still embedded during cooldown: control stays with the plan, no immediate re-fire.
        assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
    }

    @Test
    void reflexRearmsAfterCooldownWhileStillEmbedded() {
        var reflex = new FabricSuffocationReflex();
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        while (reflex.tick(true) != FabricSuffocationReflex.Action.BUDGET_EXPIRED) {
            // drain the escape budget
        }
        for (var tick = 0; tick < FabricSuffocationReflex.REARM_COOLDOWN_TICKS; tick++) {
            assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(FabricSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }

    @Test
    void leavingTheWallDuringCooldownClearsItImmediately() {
        var reflex = new FabricSuffocationReflex();
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS; tick++) reflex.tick(true);
        while (reflex.tick(true) != FabricSuffocationReflex.Action.BUDGET_EXPIRED) {
            // drain the escape budget
        }
        assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(false));
        for (var tick = 0; tick < FabricSuffocationReflex.CONFIRM_TICKS - 1; tick++) {
            assertEquals(FabricSuffocationReflex.Action.NONE, reflex.tick(true));
        }
        assertEquals(FabricSuffocationReflex.Action.ESCAPE, reflex.tick(true));
    }
}
