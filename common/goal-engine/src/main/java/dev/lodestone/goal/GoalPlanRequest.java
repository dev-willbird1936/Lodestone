// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;

/** Bounded context supplied to an optional model when no built-in plan matches a goal. */
public record GoalPlanRequest(GoalSpec spec, List<Map<String, Object>> builtInTasks) {
    public GoalPlanRequest {
        if (spec == null) throw new IllegalArgumentException("goal plan request requires a goal spec");
        builtInTasks = builtInTasks == null ? List.of() : builtInTasks.stream()
                .map(Map::copyOf).toList();
    }
}
