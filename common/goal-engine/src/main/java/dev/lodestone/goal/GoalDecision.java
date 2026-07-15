// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

public record GoalDecision(int candidateIndex, String rationale) {
    public GoalDecision {
        if (candidateIndex < 0) throw new IllegalArgumentException("candidateIndex must not be negative");
        rationale = rationale == null ? "" : rationale;
    }
}
