// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GoalBenchmarkRunner {
    private final GoalEngine engine;

    public GoalBenchmarkRunner() {
        this(new GoalEngine());
    }

    public GoalBenchmarkRunner(GoalEngine engine) {
        this.engine = engine;
    }

    public List<BenchmarkCase> run(List<String> taskIds, GoalInvoker invoker, boolean dryRun) {
        return run(taskIds, invoker, dryRun, GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults());
    }

    public List<BenchmarkCase> run(List<String> taskIds, GoalInvoker invoker, boolean dryRun,
                                   GoalIntelligence intelligence, GoalSafety safety, GoalControls controls) {
        var selected = taskIds == null || taskIds.isEmpty()
                ? GoalTaskCatalog.tasks().stream().map(GoalTaskCatalog.TaskDefinition::id).toList()
                : List.copyOf(taskIds);
        var results = new ArrayList<BenchmarkCase>();
        for (var taskId : selected) {
            var task = GoalTaskCatalog.find(taskId);
            if (task.isEmpty()) {
                results.add(new BenchmarkCase(taskId, null, null, "unknown task", Map.of()));
                continue;
            }
            var script = engine.run(new GoalSpec(task.get().description(), GoalMode.SCRIPT, taskId,
                    256, 120_000, dryRun, null, false, intelligence, safety, controls), invoker);
            var realtime = engine.run(new GoalSpec(task.get().description(), GoalMode.REALTIME, taskId,
                    256, 120_000, dryRun, null, false, intelligence, safety, controls), invoker);
            results.add(new BenchmarkCase(taskId, script, realtime, compare(script, realtime), Map.of(
                    "correctnessPriority", "status and false-success first",
                    "sameTask", true,
                    "speedMetric", "elapsedMs",
                    "intelligence", intelligence.id(),
                    "safety", safety.id())));
        }
        return List.copyOf(results);
    }

    private static String compare(GoalRunReport script, GoalRunReport realtime) {
        if (script.status() == GoalStatus.SUCCEEDED && realtime.status() != GoalStatus.SUCCEEDED) return "script-correctness-wins";
        if (realtime.status() == GoalStatus.SUCCEEDED && script.status() != GoalStatus.SUCCEEDED) return "realtime-correctness-wins";
        if (script.status() != GoalStatus.SUCCEEDED) return "both-not-successful";
        return realtime.elapsedMs() < script.elapsedMs() ? "realtime-faster" : "script-faster-or-equal";
    }

    public record BenchmarkCase(String taskId, GoalRunReport script, GoalRunReport realtime,
                                String comparison, Map<String, Object> metrics) {
    }
}
