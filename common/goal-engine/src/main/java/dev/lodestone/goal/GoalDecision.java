// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

public record GoalDecision(int candidateIndex, String rationale, String reasoningEffort) {
    /** Compatibility constructor for providers that do not report a per-decision reasoning effort. */
    public GoalDecision(int candidateIndex, String rationale) {
        this(candidateIndex, rationale, null);
    }

    public GoalDecision {
        if (candidateIndex < 0) throw new IllegalArgumentException("candidateIndex must not be negative");
        rationale = rationale == null ? "" : rationale;
    }
}
