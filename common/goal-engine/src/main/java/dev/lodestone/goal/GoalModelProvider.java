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

    Optional<GoalDecision> choose(GoalDecisionRequest request);
}
