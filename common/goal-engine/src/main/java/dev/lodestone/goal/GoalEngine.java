// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.StructuredError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GoalEngine {
    private static final long[] RATE_LIMIT_RETRY_DELAYS_MS = {100, 250, 500, 1_000};

    private final GoalPlanner planner;
    private final GoalModelProvider modelProvider;

    public GoalEngine() {
        this(new BuiltinGoalPlanner(), GoalModelProviders.select());
    }

    public GoalEngine(GoalPlanner planner, GoalModelProvider modelProvider) {
        this.planner = planner;
        this.modelProvider = modelProvider;
    }

    public GoalRunReport run(GoalSpec spec, GoalInvoker invoker) {
        var started = System.nanoTime();
        var planned = planner.plan(spec);
        if (!planned.supported()) {
            return report("none", spec, GoalStatus.UNSUPPORTED, planned.unsupportedReason(), started,
                    0, 0, List.of(), Map.of());
        }
        var plan = planned.plan();
        var state = new LinkedHashMap<String, Object>();
        state.put("goal", spec.goal());
        state.put("mode", spec.mode().toString());
        state.put("planId", plan.id());
        var steps = new LinkedHashMap<String, Object>();
        state.put("steps", steps);
        var trace = new ArrayList<Map<String, Object>>();
        var completedSteps = 0;
        var completedSegments = 0;
        var actionCount = 0;
        var pendingFailure = (ExecutionFailure) null;
        var cleanupFailure = (ExecutionFailure) null;
        GoalRunReport finalReport;
        try {
            for (var segment : plan.segments()) {
                var pending = new ArrayList<>(segment.steps());
                while (!pending.isEmpty()) {
                    if (completedSteps >= spec.maxSteps() || elapsedMs(started) >= spec.maxDurationMs()) {
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "goal budget exhausted");
                        break;
                    }
                    var selected = spec.mode() == GoalMode.REALTIME
                            ? selectRealtimeStep(spec, state, pending)
                            : pending.get(0);
                    if (elapsedMs(started) >= spec.maxDurationMs()) {
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "goal budget exhausted after planning");
                        break;
                    }
                    pending.remove(selected);
                    var stepStarted = System.nanoTime();
                    ResultEnvelope result;
                    if (selected.kind() == GoalStepKind.ASSERT) {
                        result = ResultEnvelope.ok(selected.id(), Map.of("asserted", true));
                    } else {
                        var input = resolve(selected.input(), state);
                        result = invokeWithTransientRetry(invoker, selected.capability(), selected.capabilityVersion(),
                                input, spec.dryRun(), spec, started);
                        actionCount++;
                    }
                    if (result.status() != ResultEnvelope.Status.OK) {
                        pendingFailure = failureFor(result);
                        trace.add(trace(selected, result, elapsedMs(stepStarted)));
                        break;
                    }
                    steps.put(selected.id(), result.output());
                    trace.add(trace(selected, result, elapsedMs(stepStarted)));
                    completedSteps++;
                    if (elapsedMs(started) >= spec.maxDurationMs()) {
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT,
                                "goal duration budget exhausted after step: " + selected.id());
                        break;
                    }
                    if (!assertionsPass(selected.assertions(), state)) {
                        pendingFailure = new ExecutionFailure(GoalStatus.FAILED, "step postcondition failed: " + selected.id());
                        break;
                    }
                    if (spec.mode() == GoalMode.REALTIME && selected.kind() == GoalStepKind.INVOKE
                            && selected.observeAfter() && !"minecraft.player.state.read".equals(selected.capability())) {
                        if (completedSteps >= spec.maxSteps()) {
                            pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "no budget for realtime post-action observation");
                            break;
                        }
                        var observed = invokeWithTransientRetry(invoker, "minecraft.player.state.read", "1.0", Map.of(),
                                spec.dryRun(), spec, started);
                        actionCount++;
                        if (observed.status() != ResultEnvelope.Status.OK) {
                            pendingFailure = failureFor(observed);
                            break;
                        }
                        steps.put("postObserve." + selected.id(), observed.output());
                        completedSteps++;
                        trace.add(Map.of("step", "postObserve." + selected.id(), "kind", "observe",
                                "capability", "minecraft.player.state.read", "status", "ok"));
                        if (elapsedMs(started) >= spec.maxDurationMs()) {
                            pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT,
                                    "goal duration budget exhausted after post-action observation: " + selected.id());
                            break;
                        }
                    }
                }
                if (pendingFailure != null) break;
                if (!assertionsPass(segment.assertions(), state)) {
                    pendingFailure = new ExecutionFailure(GoalStatus.FAILED, "segment postcondition failed: " + segment.id());
                    break;
                }
                completedSegments++;
            }
            if (pendingFailure == null) {
                if (!plan.completionPredicateReady()) {
                    finalReport = report(plan.id(), spec, GoalStatus.UNSUPPORTED,
                            "plan completed actions but has no deterministic terminal predicate for this task",
                            started, completedSteps, completedSegments, trace, state);
                } else {
                    finalReport = report(plan.id(), spec, GoalStatus.SUCCEEDED, "all goal predicates passed", started,
                            completedSteps, completedSegments, trace, state);
                }
            } else {
                finalReport = report(plan.id(), spec, pendingFailure.status, pendingFailure.message, started,
                        completedSteps, completedSegments, trace, state);
            }
        } catch (RuntimeException failure) {
            finalReport = report(plan.id(), spec, GoalStatus.FAILED, safeMessage(failure), started,
                    completedSteps, completedSegments, trace, state);
        } finally {
            if (spec.mode() == GoalMode.REALTIME && actionCount > 0) {
                try {
                    var cleanup = invoker.invoke("minecraft.input.release-all", "1.0", Map.of(), false,
                            Math.max(1, Math.min(1_000L, spec.maxDurationMs())));
                    if (cleanup.status() != ResultEnvelope.Status.OK) {
                        cleanupFailure = new ExecutionFailure(GoalStatus.INDETERMINATE,
                                "realtime input cleanup failed: " + safeErrorMessage(cleanup));
                    }
                } catch (RuntimeException failure) {
                    cleanupFailure = new ExecutionFailure(GoalStatus.INDETERMINATE,
                            "realtime input cleanup failed: " + safeMessage(failure));
                }
            }
        }
        if (cleanupFailure != null && finalReport.status() == GoalStatus.SUCCEEDED) {
            return new GoalRunReport(finalReport.runId(), finalReport.planId(), finalReport.goal(), finalReport.mode(),
                    cleanupFailure.status, cleanupFailure.message, finalReport.elapsedMs(), finalReport.completedSteps(),
                    finalReport.completedSegments(), finalReport.selectedModel(), finalReport.trace(), finalReport.state());
        }
        return finalReport;
    }

    private GoalStep selectRealtimeStep(GoalSpec spec, Map<String, Object> state, List<GoalStep> pending) {
        var decision = modelProvider.choose(new GoalDecisionRequest(spec, state, pending)).orElse(new GoalDecision(0, "provider returned no decision"));
        if (decision.candidateIndex() >= pending.size()) {
            throw new IllegalArgumentException("realtime model selected an invalid candidate index");
        }
        return pending.get(decision.candidateIndex());
    }

    private static ResultEnvelope invokeWithTransientRetry(GoalInvoker invoker, String capability,
                                                           String capabilityVersion, Map<String, Object> input,
                                                           boolean dryRun, GoalSpec spec, long started) {
        for (var attempt = 0; ; attempt++) {
            var remainingMs = spec.maxDurationMs() - elapsedMs(started);
            if (remainingMs <= 0) {
                return ResultEnvelope.error(capability, ResultEnvelope.Status.TIMED_OUT,
                        StructuredError.of("DEADLINE_EXCEEDED", "goal duration budget exhausted before invocation", true));
            }
            var result = invoker.invoke(capability, capabilityVersion, input, dryRun, Math.max(1, remainingMs));
            if (!isRateLimited(result) || attempt >= RATE_LIMIT_RETRY_DELAYS_MS.length) return result;

            var delayMs = RATE_LIMIT_RETRY_DELAYS_MS[attempt];
            if (delayMs >= remainingMs) return result;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ResultEnvelope.error(capability, ResultEnvelope.Status.CANCELLED,
                        StructuredError.of("INTERRUPTED", "goal retry was interrupted", true));
            }
        }
    }

    private static boolean isRateLimited(ResultEnvelope result) {
        return result.status() == ResultEnvelope.Status.ERROR && result.error() != null
                && "RATE_LIMIT_EXCEEDED".equals(result.error().code());
    }

    private static boolean assertionsPass(List<GoalAssertion> assertions, Map<String, Object> state) {
        for (var assertion : assertions) if (!assertion.test(state)) return false;
        return true;
    }

    private static Map<String, Object> resolve(Map<String, Object> input, Map<String, Object> state) {
        var result = new LinkedHashMap<String, Object>();
        input.forEach((key, value) -> result.put(key, resolveValue(value, state)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveValue(Object value, Map<String, Object> state) {
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            return readPath(state, text.substring(2, text.length() - 1));
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), resolveValue(nested, state)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveValue(item, state)).toList();
        return value;
    }

    private static Object readPath(Map<String, Object> state, String path) {
        return GoalValues.read(state, path);
    }

    private static ExecutionFailure failureFor(ResultEnvelope result) {
        var code = result.error() == null ? "" : result.error().code();
        var status = switch (result.status()) {
            case CANCELLED -> GoalStatus.CANCELLED;
            case TIMED_OUT -> GoalStatus.TIMED_OUT;
            case ERROR -> code.contains("UNAVAILABLE") || code.contains("UNSUPPORTED")
                    ? GoalStatus.UNSUPPORTED
                    : code.contains("INDETERMINATE") ? GoalStatus.INDETERMINATE : GoalStatus.FAILED;
            case OK -> GoalStatus.SUCCEEDED;
        };
        return new ExecutionFailure(status, result.error() == null ? "goal action failed" : result.error().message());
    }

    private static String safeErrorMessage(ResultEnvelope result) {
        return result.error() == null ? "unknown result error" : result.error().message();
    }

    private static Map<String, Object> trace(GoalStep step, ResultEnvelope result, long elapsedMs) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("step", step.id());
        entry.put("kind", step.kind().toString());
        if (step.capability() != null) entry.put("capability", step.capability());
        entry.put("status", result.status().toString());
        entry.put("elapsedMs", elapsedMs);
        if (result.error() != null) entry.put("errorCode", result.error().code());
        return Map.copyOf(entry);
    }

    private GoalRunReport report(String planId, GoalSpec spec, GoalStatus status, String message, long started,
                                 int completedSteps, int completedSegments, List<Map<String, Object>> trace,
                                 Map<String, Object> state) {
        return new GoalRunReport(UUID.randomUUID().toString(), planId, spec.goal(), spec.mode(), status, message,
                elapsedMs(started), completedSteps, completedSegments,
                spec.mode() == GoalMode.REALTIME ? modelProvider.id() : "script-interpreter", trace, state);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String safeMessage(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record ExecutionFailure(GoalStatus status, String message) {
    }
}
