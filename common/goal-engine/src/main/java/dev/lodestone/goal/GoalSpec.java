// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

public record GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                       long maxDurationMs, boolean dryRun, GoalPlan customPlan,
                       boolean suppressInGameMessages, GoalIntelligence intelligence,
                       GoalSafety safety, GoalControls controls) {
    public GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                    long maxDurationMs, boolean dryRun, GoalPlan customPlan) {
        this(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan, false,
                GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults());
    }

    public GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                    long maxDurationMs, boolean dryRun, GoalPlan customPlan,
                    boolean suppressInGameMessages) {
        this(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages, GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults());
    }

    public GoalSpec(String goal, GoalMode mode, String taskId, int maxSteps,
                    long maxDurationMs, boolean dryRun, GoalPlan customPlan,
                    boolean suppressInGameMessages, GoalIntelligence intelligence,
                    GoalSafety safety) {
        this(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages, intelligence, safety, GoalControls.defaults());
    }

    public GoalSpec {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        goal = goal.trim();
        mode = mode == null ? GoalMode.SCRIPT : mode;
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim().toLowerCase(Locale.ROOT);
        // A missing policy must be useful by default. Raw/low remains available only
        // when the caller explicitly asks for the legacy fast path.
        intelligence = intelligence == null ? GoalIntelligence.GUARDED_V1 : intelligence;
        safety = safety == null ? GoalSafety.BALANCED : safety;
        controls = controls == null ? GoalControls.defaults() : controls;
        if (maxSteps < 1 || maxSteps > 1_000) {
            throw new IllegalArgumentException("maxSteps must be between 1 and 1000");
        }
        if (maxDurationMs < 100 || maxDurationMs > 600_000) {
            throw new IllegalArgumentException("maxDurationMs must be between 100 and 600000");
        }
    }

    public static GoalSpec of(String goal, GoalMode mode, String taskId, boolean dryRun) {
        return new GoalSpec(goal, mode, taskId, 256, defaultMaxDurationMs(goal, taskId), dryRun, null, false,
                GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults());
    }

    /**
     * Native multi-action actors need time for ordinary movement, mining, visible crafting, and
     * terminal readback. Keep the fast default for bounded observations and short actions, but do
     * not silently give the real survival workflows the old two-minute budget.
     */
    public static long defaultMaxDurationMs(String goal, String taskId) {
        var id = taskId == null ? "" : taskId.trim().toLowerCase(Locale.ROOT);
        var normalizedGoal = goal == null ? "" : goal.trim().toLowerCase(Locale.ROOT);
        if (id.equals("creative.wool-tree-zombie-defense")
                || id.equals("survival.wooden-axe-mine-tree")
                || id.equals("survival.collect-wood")
                || id.equals("survival.reach-nether")
                || (normalizedGoal.contains("wooden axe") && normalizedGoal.contains("tree"))
                || (normalizedGoal.contains("tree") && (normalizedGoal.contains("mine")
                || normalizedGoal.contains("collect") || normalizedGoal.contains("gather")
                || normalizedGoal.contains("chop")))
                || normalizedGoal.contains("nether")
                || (normalizedGoal.contains("wool") && normalizedGoal.contains("zombie"))) {
            return 480_000L;
        }
        return 120_000L;
    }
}
