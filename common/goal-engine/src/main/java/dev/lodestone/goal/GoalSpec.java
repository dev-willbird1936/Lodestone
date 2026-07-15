// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

public record GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                       long maxDurationMs, boolean dryRun, GoalPlan customPlan,
                       boolean suppressInGameMessages) {
    public GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                    long maxDurationMs, boolean dryRun, GoalPlan customPlan) {
        this(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan, false);
    }

    public GoalSpec {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        goal = goal.trim();
        mode = mode == null ? GoalMode.SCRIPT : mode;
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim().toLowerCase(Locale.ROOT);
        if (maxSteps < 1 || maxSteps > 1_000) {
            throw new IllegalArgumentException("maxSteps must be between 1 and 1000");
        }
        if (maxDurationMs < 100 || maxDurationMs > 600_000) {
            throw new IllegalArgumentException("maxDurationMs must be between 100 and 600000");
        }
    }

    public static GoalSpec of(String goal, GoalMode mode, String taskId, boolean dryRun) {
        return new GoalSpec(goal, mode, taskId, 256, 120_000, dryRun, null, false);
    }
}
