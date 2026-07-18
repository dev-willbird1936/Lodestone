// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Optional;

public interface GoalModelProvider {
    default String id() {
        return getClass().getSimpleName();
    }

    default long measuredP95LatencyMs() {
        return 0;
    }

    default String reasoningEffort() {
        return "low";
    }

    default boolean fallback() {
        return false;
    }

    /** Optional bounded plan synthesis for adaptive natural-language goals. */
    default Optional<GoalPlan> plan(GoalPlanRequest request) {
        return Optional.empty();
    }

    Optional<GoalDecision> choose(GoalDecisionRequest request);

    /**
     * Best-effort hook for discarding any per-run conversational state a provider is holding for
     * {@code runId} (see {@link GoalDecisionRequest#runId()}). Called once {@link GoalEngine#run}
     * reaches a terminal outcome - success, failure, or cancellation - so a provider that persists
     * decision history across calls within a run does not leak that history past the run's own
     * lifetime. Providers with no such state (the default) do nothing.
     */
    default void endSession(String runId) {
    }
}
