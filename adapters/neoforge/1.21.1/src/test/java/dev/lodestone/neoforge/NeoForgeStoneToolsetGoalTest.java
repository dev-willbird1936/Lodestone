// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeStoneToolsetGoalTest {
    @Test
    void checkpointDefaultsToCompleteWhenAbsentOrBlank() {
        assertEquals("complete", NeoForgeStoneToolsetGoal.checkpoint(Map.of()));
        assertEquals("complete", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "")));
        assertEquals("complete", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "   ")));
    }

    @Test
    void checkpointAcceptsExactlyTheThreePhaseNamesCaseInsensitively() {
        assertEquals("wooden-tools", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "wooden-tools")));
        assertEquals("wooden-tools", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "WOODEN-TOOLS")));
        assertEquals("stone-tools", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "stone-tools")));
        assertEquals("complete", NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "complete")));
    }

    @Test
    void checkpointRejectsAnyOtherGoalsPhaseNames() {
        // Regression guard: this goal must never silently accept the wooden-axe-tree goal's own
        // checkpoint vocabulary ("resource-gather", "craft-axe") or the Nether goal's
        // ("starter-tools", "portal-tools") - each native actor validates its own closed set.
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "resource-gather")));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "craft-axe")));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "starter-tools")));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "portal-tools")));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeStoneToolsetGoal.checkpoint(Map.of("checkpoint", "nonsense")));
    }

    @Test
    void recoverableNavigationFailuresAreRecognizedByMessage() {
        assertTrue(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "TARGET_UNREACHABLE: cause=target:unreachable; safe intelligent route unavailable to resource tree"));
        assertTrue(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "STUCK_NO_PROGRESS: cause=stall:navigation; normal-input route remained obstructed before reaching resource tree"));
        assertTrue(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "STUCK_NO_PROGRESS: cause=stall:navigation; visible navigation timed out before reaching resource tree"));
        assertTrue(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "STUCK_NO_PROGRESS: cause=stall:navigation; normal-input detours remained obstructed before reaching resource tree"));
    }

    @Test
    void unrelatedFailuresAreNotTreatedAsRecoverableNavigation() {
        assertFalse(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(null));
        assertFalse(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "PLAYER_DIED: cause=died:lava; player died during MINE_LOGS"));
        assertFalse(NeoForgeStoneToolsetGoal.isRecoverableNavigationFailure(
                "STUCK_NO_PROGRESS: cause=stall:mining; hand mining failed to break observed log"));
    }
}
