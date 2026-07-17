// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.StructuredError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GoalRecoveryCoordinatorTest {
    @Test
    void transientFailureGetsExactlyOneRetry() {
        var coordinator = new GoalRecoveryCoordinator();
        var first = coordinator.decide("move", GoalFailureKind.TRANSIENT, false, false);
        var second = coordinator.decide("move", GoalFailureKind.TRANSIENT, false, false);

        assertEquals(GoalRecoveryCoordinator.RecoveryDecision.RETRY_ONCE, first.decision());
        assertEquals(GoalRecoveryCoordinator.RecoveryDecision.ABORT_STEP, second.decision());
    }

    @Test
    void adaptiveRealtimeReplansPathFailuresAndPlayerOverrideYields() {
        var coordinator = new GoalRecoveryCoordinator();
        var replan = coordinator.decide("navigate", GoalFailureKind.PATH_FAILED, false, true);
        var yield = coordinator.decide("navigate", GoalFailureKind.PLAYER_OVERRIDE, false, true);

        assertEquals(GoalRecoveryCoordinator.RecoveryDecision.REPLAN_LOCAL, replan.decision());
        assertEquals(GoalRecoveryCoordinator.RecoveryDecision.YIELD_TO_PLAYER, yield.decision());
    }

    @Test
    void resultCodesMapToStableFailureKinds() {
        var result = ResultEnvelope.error("move", ResultEnvelope.Status.ERROR,
                StructuredError.of("TARGET_UNREACHABLE", "no route", false));
        assertEquals(GoalFailureKind.TARGET_UNREACHABLE, GoalFailureKind.from(result));
    }
}
