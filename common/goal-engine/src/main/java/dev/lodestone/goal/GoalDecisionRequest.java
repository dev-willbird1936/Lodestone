// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;

public record GoalDecisionRequest(GoalSpec spec, Map<String, Object> state, List<GoalStep> candidates) {
    public GoalDecisionRequest {
        state = state == null ? Map.of() : Map.copyOf(state);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
