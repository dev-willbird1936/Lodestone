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
}
