// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class NeoForgeRespawnRecoverGoalTest {
    @Test
    void aliveWithNoDeathScreenIsAlwaysNotDeadRegardlessOfRecoverItemsOrKnownPosition() {
        assertEquals("not-dead", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(false, true, true));
        assertEquals("not-dead", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(false, true, false));
        assertEquals("not-dead", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(false, false, true));
        assertEquals("not-dead", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(false, false, false));
    }

    @Test
    void deadWithRecoveryDisabledNeverAttemptsRecoveryRegardlessOfKnownPosition() {
        assertEquals("nothing-to-recover", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(true, false, true));
        assertEquals("nothing-to-recover", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(true, false, false));
    }

    @Test
    void deadWithRecoveryEnabledButNoKnownDeathPositionHasNothingToRecover() {
        assertEquals("nothing-to-recover", NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(true, true, false));
    }

    @Test
    void deadWithRecoveryEnabledAndAKnownPositionDefersToTheLiveDropsCheck() {
        assertNull(NeoForgeRespawnRecoverGoal.resolveEarlyOutcome(true, true, true));
    }
}
