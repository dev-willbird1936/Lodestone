// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded recovery ladder for action-level failures.
 *
 * <p>It never invents a new capability or widens the goal. A retry is allowed once for a
 * transient result; replanning can only select a step already present in the declared realtime
 * candidate set. Native NeoForge actors still own their finer-grained movement/mining recovery.</p>
 */
public final class GoalRecoveryCoordinator {
    private static final int MAX_RETRIES_PER_STEP = 1;
    private static final int MAX_REPLANS_PER_STEP = 2;

    private final Map<String, Integer> retries = new HashMap<>();
    private final Map<String, Integer> replans = new HashMap<>();

    public Decision decide(String stepId, GoalFailureKind failure, boolean declaredAlternative,
                           boolean adaptiveRealtime) {
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId is required");
        if (failure == null || failure == GoalFailureKind.NONE) {
            return new Decision(RecoveryDecision.ABORT_STEP, failure == null ? GoalFailureKind.UNKNOWN : failure,
                    0, "no failure to recover");
        }
        if (failure == GoalFailureKind.PLAYER_OVERRIDE) {
            return new Decision(RecoveryDecision.YIELD_TO_PLAYER, failure, 0,
                    "player input owns the control boundary");
        }
        if (failure == GoalFailureKind.SAFETY_REJECTED || failure == GoalFailureKind.PRECONDITION_FAILED) {
            return new Decision(RecoveryDecision.ABORT_STEP, failure, 0,
                    "safety or declared precondition must be fixed before retry");
        }
        if (failure == GoalFailureKind.PLAYER_DIED) {
            return new Decision(RecoveryDecision.ABORT_STEP, failure, 0,
                    "a death is terminal for this run; retry policy belongs to the caller");
        }
        if (failure == GoalFailureKind.TRANSIENT
                && retries.getOrDefault(stepId, 0) < MAX_RETRIES_PER_STEP) {
            var attempt = retries.merge(stepId, 1, Integer::sum);
            return new Decision(RecoveryDecision.RETRY_ONCE, failure, attempt,
                    "bounded retry for transient action failure");
        }
        if (declaredAlternative && adaptiveRealtime) {
            return new Decision(RecoveryDecision.TRY_DECLARED_ALTERNATIVE, failure,
                    replans.getOrDefault(stepId, 0), "choose another eligible declared action");
        }
        if (adaptiveRealtime && replans.getOrDefault(stepId, 0) < MAX_REPLANS_PER_STEP
                && (failure == GoalFailureKind.PATH_FAILED
                || failure == GoalFailureKind.TARGET_UNREACHABLE
                || failure == GoalFailureKind.OBSTRUCTED
                || failure == GoalFailureKind.NO_PROGRESS
                || failure == GoalFailureKind.WORLD_CHANGED
                || failure == GoalFailureKind.POSTCONDITION_FAILED)) {
            var attempt = replans.merge(stepId, 1, Integer::sum);
            return new Decision(RecoveryDecision.REPLAN_LOCAL, failure, attempt,
                    "bounded local replan from a fresh observation");
        }
        return new Decision(RecoveryDecision.ABORT_STEP, failure,
                Math.max(retries.getOrDefault(stepId, 0), replans.getOrDefault(stepId, 0)),
                "recovery budget exhausted or no declared alternative exists");
    }

    public static GoalFailureKind classify(ResultEnvelope result) {
        return GoalFailureKind.from(result);
    }

    public enum RecoveryDecision {
        RETRY_ONCE,
        REPLAN_LOCAL,
        BLACKLIST_TARGET_AND_REPLAN,
        TRY_DECLARED_ALTERNATIVE,
        ABORT_STEP,
        YIELD_TO_PLAYER
    }

    public record Decision(RecoveryDecision decision, GoalFailureKind failure,
                           int attempt, String rationale) {
        public Decision {
            decision = decision == null ? RecoveryDecision.ABORT_STEP : decision;
            failure = failure == null ? GoalFailureKind.UNKNOWN : failure;
            rationale = rationale == null ? "" : rationale;
        }
    }
}
