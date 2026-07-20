// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.reflect.TypeToken;
import dev.lodestone.goal.GoalBenchmarkRunner;
import dev.lodestone.goal.GoalEngine;
import dev.lodestone.goal.GoalInvoker;
import dev.lodestone.goal.GoalMode;
import dev.lodestone.goal.GoalIntelligence;
import dev.lodestone.goal.GoalSafety;
import dev.lodestone.goal.GoalControls;
import dev.lodestone.goal.GoalPlan;
import dev.lodestone.goal.GoalRunReport;
import dev.lodestone.goal.GoalSpec;
import dev.lodestone.goal.GoalStatus;
import dev.lodestone.goal.GoalTaskCatalog;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP-facing orchestration facade. Native execution still goes through LodestoneRuntime.
 *
 * <p>Exactly one native Minecraft goal actor can run at a time (see
 * {@code NeoForgeClientController}), so every call that drives a goal against the live client - both
 * {@link #run} and {@link #benchmark} - is serialized through the same {@link GoalExecutionQueue}
 * instance. That queue also blocks each calling thread until its own turn arrives, so the synchronous
 * {@code minecraft_goal} contract every caller depends on is unchanged when nothing else is in flight.
 *
 * <p>{@link #run} delegates to {@link GoalOrchestratorLauncher} (a real-model, subaction-loop, MCP
 * loopback client, not a call into {@link GoalEngine}); {@link #benchmark} still drives
 * {@link GoalEngine} directly through {@link GoalBenchmarkRunner} and is unaffected by that migration.
 * Because the new backing does not yet support them, {@link #run} fails closed with
 * {@link IllegalArgumentException} for {@code mode=script}, {@code dryRun}, a {@code customPlan}, a
 * {@code taskId}, or a {@code worldSeed} - a caller silently getting realtime/no-plan/no-taskId
 * behavior it never asked for would be far more surprising and harder to notice than an explicit
 * rejection naming exactly which parameter is unsupported.
 */
public final class GoalService {
    /**
     * {@code minecraft_goal_benchmark} has no single natural per-call duration to reuse as a queue
     * wait cap (it runs many tasks in script and realtime mode back to back), so it uses a fixed cap
     * matching {@code GoalSpec}'s own upper bound on a single goal's duration budget.
     */
    private static final long BENCHMARK_QUEUE_WAIT_CAP_MS = 600_000L;

    private final LodestoneRuntime runtime;
    private final GoalEngine engine;
    private final GoalBenchmarkRunner benchmarks;
    private final GoalOrchestratorLauncher orchestratorLauncher;
    private final GoalExecutionQueue executionQueue = new GoalExecutionQueue();

    public GoalService(LodestoneRuntime runtime) {
        this.runtime = runtime;
        this.engine = new GoalEngine();
        this.benchmarks = new GoalBenchmarkRunner(engine);
        this.orchestratorLauncher = new GoalOrchestratorLauncher(runtime);
    }

    public GoalRunReport run(String goal, GoalMode mode, String taskId, int maxSteps, long maxDurationMs,
                             boolean dryRun, GoalPlan customPlan, boolean suppressInGameMessages,
                             String callerSessionId,
                             AuthorizationPolicy authorization) {
        return run(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages, GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED,
                GoalControls.defaults(),
                callerSessionId, authorization);
    }

    public GoalRunReport run(String goal, GoalMode mode, String taskId, int maxSteps, long maxDurationMs,
                             boolean dryRun, GoalPlan customPlan, boolean suppressInGameMessages,
                             GoalIntelligence intelligence, GoalSafety safety,
                             GoalControls controls,
                             String callerSessionId,
                             AuthorizationPolicy authorization) {
        return run(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages, intelligence, safety, controls, null,
                callerSessionId, authorization);
    }

    public GoalRunReport run(String goal, GoalMode mode, String taskId, int maxSteps, long maxDurationMs,
                             boolean dryRun, GoalPlan customPlan, boolean suppressInGameMessages,
                             GoalIntelligence intelligence, GoalSafety safety,
                             GoalControls controls, String worldSeed,
                             String callerSessionId,
                             AuthorizationPolicy authorization) {
        return run(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan, suppressInGameMessages,
                intelligence, safety, controls, worldSeed, false, callerSessionId, authorization);
    }

    /**
     * Queues this goal behind any goal already running or already waiting, then blocks the calling
     * thread until it is this call's turn and the goal itself finishes. {@code priority=true} jumps
     * this call ahead of other not-yet-started, already-queued calls (arrival order still applies
     * within each tier); it never interrupts a goal that is already running. See
     * {@link GoalExecutionQueue} for the full ordering, cancellation, and wait-timeout contract.
     *
     * @throws IllegalArgumentException if {@code mode} is {@code SCRIPT}, {@code dryRun} is
     *                                  {@code true}, {@code customPlan} is non-null, {@code taskId} is
     *                                  non-blank, or {@code worldSeed} is non-blank - none of these
     *                                  are supported by {@link GoalOrchestratorLauncher} yet. Thrown
     *                                  before this call ever enters the queue.
     */
    public GoalRunReport run(String goal, GoalMode mode, String taskId, int maxSteps, long maxDurationMs,
                             boolean dryRun, GoalPlan customPlan, boolean suppressInGameMessages,
                             GoalIntelligence intelligence, GoalSafety safety,
                             GoalControls controls, String worldSeed, boolean priority,
                             String callerSessionId,
                             AuthorizationPolicy authorization) {
        var spec = new GoalSpec(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages, intelligence, safety, controls, worldSeed);
        requireOrchestratorSupported(spec);
        var label = "minecraft_goal:" + (spec.taskId() != null ? spec.taskId() : spec.goal());
        return executionQueue.run(label, priority, spec.maxDurationMs(),
                context -> withQueueMetadata(
                        orchestratorLauncher.launch(spec, callerSessionId, authorization), context),
                context -> queueOutcomeReport(spec, context, GoalStatus.CANCELLED, "QUEUE_CANCELLED",
                        "cause=queue:cancelled; caller was interrupted", "goal never started"),
                context -> queueOutcomeReport(spec, context, GoalStatus.TIMED_OUT, "QUEUE_TIMEOUT",
                        "cause=queue:timeout; queue wait budget was exhausted", "goal never started"));
    }

    /**
     * Fails closed rather than silently ignoring a parameter {@link GoalOrchestratorLauncher} does
     * not support. Runs before this call ever reaches {@link GoalExecutionQueue} so an unsupported
     * request never consumes a queue slot or blocks another caller's wait budget.
     */
    private static void requireOrchestratorSupported(GoalSpec spec) {
        if (spec.mode() != GoalMode.REALTIME) {
            throw new IllegalArgumentException(
                    "mode=script is not supported by the realtime goal orchestrator; use mode=realtime");
        }
        if (spec.dryRun()) {
            throw new IllegalArgumentException("dryRun is not supported by the realtime goal orchestrator");
        }
        if (spec.customPlan() != null) {
            throw new IllegalArgumentException("plan is not supported by the realtime goal orchestrator");
        }
        if (spec.taskId() != null) {
            throw new IllegalArgumentException("taskId is not supported by the realtime goal orchestrator");
        }
        if (spec.worldSeed() != null) {
            throw new IllegalArgumentException("worldSeed is not supported by the realtime goal orchestrator");
        }
    }

    public List<GoalBenchmarkRunner.BenchmarkCase> benchmark(List<String> taskIds, boolean dryRun,
                                                             String callerSessionId, AuthorizationPolicy authorization) {
        return benchmark(taskIds, dryRun, GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(),
                callerSessionId, authorization);
    }

    /**
     * Runs on the same {@link GoalExecutionQueue} as {@link #run}: a benchmark call and a plain
     * {@code minecraft_goal} call can never race the native goal actor against each other, only wait
     * their turn. Benchmark calls are never priority (that flag is scoped to {@code minecraft_goal}).
     */
    public List<GoalBenchmarkRunner.BenchmarkCase> benchmark(List<String> taskIds, boolean dryRun,
                                                             GoalIntelligence intelligence, GoalSafety safety,
                                                             GoalControls controls, String callerSessionId,
                                                             AuthorizationPolicy authorization) {
        return executionQueue.run("minecraft_goal_benchmark", false, BENCHMARK_QUEUE_WAIT_CAP_MS,
                context -> benchmarks.run(taskIds, invoker(callerSessionId, authorization), dryRun,
                        intelligence, safety, controls),
                context -> queueBlockedBenchmark(taskIds, context, GoalStatus.CANCELLED, "QUEUE_CANCELLED",
                        "cause=queue:cancelled; caller was interrupted", "benchmark never started"),
                context -> queueBlockedBenchmark(taskIds, context, GoalStatus.TIMED_OUT, "QUEUE_TIMEOUT",
                        "cause=queue:timeout; queue wait budget was exhausted", "benchmark never started"));
    }

    /** Merges the granted call's queue timing into a completed {@link GoalRunReport}'s state map. */
    private static GoalRunReport withQueueMetadata(GoalRunReport report, GoalExecutionQueue.Context context) {
        var state = new LinkedHashMap<>(report.state());
        state.put("queuedMs", context.waitedMs());
        state.put("queuePositionAtEnqueue", context.positionAtEnqueue());
        return new GoalRunReport(report.runId(), report.planId(), report.goal(), report.mode(), report.status(),
                report.message(), report.elapsedMs(), report.completedSteps(), report.completedSegments(),
                report.selectedModel(), report.trace(), state);
    }

    /**
     * Builds a {@link GoalRunReport}-shaped result for a goal that never started because it was
     * cancelled or timed out while queued, following this codebase's declared-cause convention
     * ({@code "<LABEL>: cause=<vocabulary>; <detail>"}, see {@code GoalEngine.failureCause}).
     */
    private static GoalRunReport queueOutcomeReport(GoalSpec spec, GoalExecutionQueue.Context context,
                                                     GoalStatus status, String label, String causeClause,
                                                     String detailSuffix) {
        var message = label + ": " + causeClause + "; waited " + context.waitedMs() + "ms in queue position "
                + context.positionAtEnqueue()
                + (context.activeLabel() != null ? (" behind currently running goal \"" + context.activeLabel() + "\"") : "")
                + "; " + detailSuffix;
        return new GoalRunReport(UUID.randomUUID().toString(), "none", spec.goal(), spec.mode(), status, message,
                context.waitedMs(), 0, 0, "none", List.of(),
                Map.of("priority", context.ticket().priority(), "queuedMs", context.waitedMs(),
                        "queuePositionAtEnqueue", context.positionAtEnqueue()));
    }

    /**
     * A benchmark call has no single {@link GoalRunReport} to return, so a queue-blocked outcome is
     * represented as a single synthetic {@code BenchmarkCase} carrying paired script/realtime reports
     * built the same way {@link #queueOutcomeReport} builds one - none of the requested task IDs ran.
     */
    private static List<GoalBenchmarkRunner.BenchmarkCase> queueBlockedBenchmark(List<String> taskIds,
            GoalExecutionQueue.Context context, GoalStatus status, String label, String causeClause,
            String detailSuffix) {
        var blockedGoal = "benchmark:" + (taskIds == null || taskIds.isEmpty() ? "all-tasks" : String.join(",", taskIds));
        var scriptReport = queueOutcomeReport(
                new GoalSpec(blockedGoal, GoalMode.SCRIPT, null, 1, 100, true, null), context, status, label,
                causeClause, detailSuffix);
        var realtimeReport = new GoalRunReport(UUID.randomUUID().toString(), "none", blockedGoal, GoalMode.REALTIME,
                status, scriptReport.message(), scriptReport.elapsedMs(), 0, 0, "none", List.of(), scriptReport.state());
        return List.of(new GoalBenchmarkRunner.BenchmarkCase("queue-blocked", scriptReport, realtimeReport,
                status == GoalStatus.CANCELLED ? "queue-cancelled" : "queue-timeout",
                Map.of("requestedTaskIds", taskIds == null ? List.of() : List.copyOf(taskIds))));
    }

    public List<Map<String, Object>> tasks(String category) {
        return GoalTaskCatalog.tasks().stream()
                .filter(task -> category == null || category.isBlank() || task.category().equalsIgnoreCase(category))
                .map(GoalTaskCatalog.TaskDefinition::toMap).toList();
    }

    public List<String> modelProviders() {
        return dev.lodestone.goal.GoalModelProviders.availableProviderIds();
    }

    private GoalInvoker invoker(String callerSessionId, AuthorizationPolicy authorization) {
        return new GoalInvoker() {
            @Override
            public dev.lodestone.protocol.ResultEnvelope invoke(String capability, String version,
                                                                  Map<String, Object> input, boolean dryRun) {
                return invoke(capability, version, input, dryRun, 120_000L);
            }

            @Override
            public dev.lodestone.protocol.ResultEnvelope invoke(String capability, String version,
                                                                  Map<String, Object> input, boolean dryRun,
                                                                  long timeoutMs) {
                var requestId = UUID.randomUUID().toString();
                var request = new RequestEnvelope(ProtocolVersion.CURRENT, requestId,
                        runtime.sessionId(), capability, version, input, System.currentTimeMillis()
                        + Math.max(1L, timeoutMs), null, dryRun);
                try {
                    return runtime.invoke(request, callerSessionId, authorization)
                            .get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
                } catch (TimeoutException timeout) {
                    return dev.lodestone.protocol.ResultEnvelope.error(requestId,
                            dev.lodestone.protocol.ResultEnvelope.Status.TIMED_OUT,
                            dev.lodestone.protocol.StructuredError.of("DEADLINE_EXCEEDED",
                                    "goal capability deadline exceeded", true));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return dev.lodestone.protocol.ResultEnvelope.error(requestId,
                            dev.lodestone.protocol.ResultEnvelope.Status.CANCELLED,
                            dev.lodestone.protocol.StructuredError.of("INTERRUPTED",
                                    "goal capability invocation was interrupted", true));
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("goal capability invocation failed", failure.getCause());
                }
            }
        };
    }

    public static GoalPlan parsePlan(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) JsonSupport.MAPPER.fromJson(element,
                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
        return GoalPlan.fromMap(raw);
    }
}
