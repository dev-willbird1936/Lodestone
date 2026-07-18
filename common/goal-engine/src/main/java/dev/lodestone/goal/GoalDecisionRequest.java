// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;

/**
 * {@code runId} is the same identifier as the owning run's eventual {@link GoalRunReport#runId()},
 * generated once up front in {@link GoalEngine#run} rather than invented separately here. It lets a
 * {@link GoalModelProvider} correlate every decision call within one goal run without being handed
 * or caching any world state itself - see {@link HttpJsonGoalModelProvider} for the persisted,
 * decision-only conversation history keyed by this id.
 */
public record GoalDecisionRequest(String runId, GoalSpec spec, Map<String, Object> state, List<GoalStep> candidates) {
    public GoalDecisionRequest {
        runId = runId == null ? "" : runId;
        state = state == null ? Map.of() : Map.copyOf(state);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** Return the bounded state projection intended for a low-latency model prompt. */
    public Map<String, Object> decisionState() {
        return GoalDecisionState.project(state);
    }
}
