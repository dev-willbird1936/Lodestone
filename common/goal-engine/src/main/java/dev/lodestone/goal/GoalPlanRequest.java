// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;

/**
 * Bounded context supplied to an optional model when no built-in plan matches a goal.
 *
 * <p>{@code runId} is the same identifier as the owning run's eventual {@link GoalRunReport#runId()};
 * see {@link GoalDecisionRequest#runId()} for why it is threaded through rather than invented here.
 */
public record GoalPlanRequest(String runId, GoalSpec spec, List<Map<String, Object>> builtInTasks) {
    public GoalPlanRequest {
        if (spec == null) throw new IllegalArgumentException("goal plan request requires a goal spec");
        runId = runId == null ? "" : runId;
        builtInTasks = builtInTasks == null ? List.of() : builtInTasks.stream()
                .map(Map::copyOf).toList();
    }
}
