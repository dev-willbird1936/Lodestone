// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

/**
 * {@code fallback} marks a decision the provider itself could not really make - e.g. the bridge's
 * model call errored, timed out, or returned malformed output, and it silently defaulted to the
 * first candidate. Without this flag a degraded decision is indistinguishable from a genuine model
 * choice in the run trace; see the {@code kind: "model-decision"} trace entries built in
 * {@link GoalEngine#run}.
 */
public record GoalDecision(int candidateIndex, String rationale, String reasoningEffort, boolean fallback) {
    /** Compatibility constructor for providers that do not report a per-decision reasoning effort. */
    public GoalDecision(int candidateIndex, String rationale) {
        this(candidateIndex, rationale, null, false);
    }

    /** Compatibility constructor for providers that do not report a fallback/degraded flag. */
    public GoalDecision(int candidateIndex, String rationale, String reasoningEffort) {
        this(candidateIndex, rationale, reasoningEffort, false);
    }

    public GoalDecision {
        if (candidateIndex < 0) throw new IllegalArgumentException("candidateIndex must not be negative");
        rationale = rationale == null ? "" : rationale;
    }
}
