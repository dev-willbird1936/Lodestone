// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;

public record GoalRunReport(String runId, String planId, String goal, GoalMode mode, GoalStatus status,
                            String message, long elapsedMs, int completedSteps, int completedSegments,
                            String selectedModel, List<Map<String, Object>> trace, Map<String, Object> state) {
    public GoalRunReport {
        trace = trace == null ? List.of() : List.copyOf(trace);
        state = state == null ? Map.of() : Map.copyOf(state);
        message = message == null ? "" : message;
        selectedModel = selectedModel == null ? "none" : selectedModel;
    }
}
