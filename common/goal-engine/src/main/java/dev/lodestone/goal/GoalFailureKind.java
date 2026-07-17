// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;

/** Stable failure vocabulary shared by script and realtime recovery. */
public enum GoalFailureKind {
    NONE,
    PLAYER_OVERRIDE,
    PLAYER_DIED,
    PRECONDITION_FAILED,
    NO_PROGRESS,
    PATH_FAILED,
    TARGET_UNREACHABLE,
    OBSTRUCTED,
    POSTCONDITION_FAILED,
    SAFETY_REJECTED,
    WORLD_CHANGED,
    TRANSIENT,
    UNKNOWN;

    public static GoalFailureKind from(ResultEnvelope result) {
        if (result == null || result.status() == ResultEnvelope.Status.OK) return NONE;
        var code = result.error() == null || result.error().code() == null
                ? "" : result.error().code().toUpperCase(java.util.Locale.ROOT);
        if (code.contains("PLAYER_OVERRIDE")) return PLAYER_OVERRIDE;
        if (code.contains("DIED") || code.contains("DEATH")) return PLAYER_DIED;
        if (code.contains("PRECONDITION")) return PRECONDITION_FAILED;
        if (code.contains("POSTCONDITION")) return POSTCONDITION_FAILED;
        if (code.contains("SAFETY") || code.contains("SURVIVAL_POLICY") || code.contains("HAZARD")) {
            return SAFETY_REJECTED;
        }
        if (code.contains("NO_PROGRESS") || code.contains("STUCK")
                || code.contains("TIMEOUT_BUDGET")) return NO_PROGRESS;
        if (code.contains("PATH") || code.contains("NAVIGATION")) return PATH_FAILED;
        if (code.contains("UNREACHABLE") || code.contains("TARGET")) return TARGET_UNREACHABLE;
        if (code.contains("OBSTRUCT")) return OBSTRUCTED;
        if (result.status() == ResultEnvelope.Status.TIMED_OUT
                || result.status() == ResultEnvelope.Status.ERROR && result.error() != null
                && result.error().retryable()) return TRANSIENT;
        if (code.contains("WORLD_CHANGED") || code.contains("STALE")) return WORLD_CHANGED;
        return UNKNOWN;
    }
}
