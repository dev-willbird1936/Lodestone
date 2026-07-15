// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.reflect.TypeToken;
import dev.lodestone.goal.GoalBenchmarkRunner;
import dev.lodestone.goal.GoalEngine;
import dev.lodestone.goal.GoalInvoker;
import dev.lodestone.goal.GoalMode;
import dev.lodestone.goal.GoalPlan;
import dev.lodestone.goal.GoalRunReport;
import dev.lodestone.goal.GoalSpec;
import dev.lodestone.goal.GoalTaskCatalog;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** MCP-facing orchestration facade. Native execution still goes through LodestoneRuntime. */
public final class GoalService {
    private final LodestoneRuntime runtime;
    private final GoalEngine engine;
    private final GoalBenchmarkRunner benchmarks;

    public GoalService(LodestoneRuntime runtime) {
        this.runtime = runtime;
        this.engine = new GoalEngine();
        this.benchmarks = new GoalBenchmarkRunner(engine);
    }

    public GoalRunReport run(String goal, GoalMode mode, String taskId, int maxSteps, long maxDurationMs,
                             boolean dryRun, GoalPlan customPlan, boolean suppressInGameMessages,
                             String callerSessionId,
                             AuthorizationPolicy authorization) {
        var spec = new GoalSpec(goal, mode, taskId, maxSteps, maxDurationMs, dryRun, customPlan,
                suppressInGameMessages);
        return engine.run(spec, invoker(callerSessionId, authorization));
    }

    public List<GoalBenchmarkRunner.BenchmarkCase> benchmark(List<String> taskIds, boolean dryRun,
                                                             String callerSessionId, AuthorizationPolicy authorization) {
        return benchmarks.run(taskIds, invoker(callerSessionId, authorization), dryRun);
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
