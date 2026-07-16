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
        if (spec.intelligence().requiresModel(spec.mode()) && modelProvider.fallback()) {
            return report(plan.id(), spec, GoalStatus.UNSUPPORTED,
                    "adaptive-v1 requires a realtime low-latency model provider; none is available",
                    started, 0, 0, List.of(), Map.of(
                            "intelligence", spec.intelligence().id(),
                            "safety", spec.safety().id(),
                            "executorModel", modelProvider.id()));
        }
        var state = new LinkedHashMap<String, Object>();
        state.put("goal", spec.goal());
        state.put("mode", spec.mode().toString());
        state.put("intelligence", spec.intelligence().id());
        state.put("planningDepth", spec.intelligence().planningDepth());
        state.put("prerequisitePlanning", spec.intelligence().prerequisitePlanningEnabled());
        state.put("toolPrerequisitePlanning", spec.intelligence().toolPrerequisitePlanningEnabled());
        state.put("safeNavigationPlanning", spec.intelligence().safeNavigationPlanningEnabled());
        state.put("obstructionRecovery", spec.intelligence().obstructionRecoveryEnabled());
        state.put("actionSegmentReplanning", spec.intelligence().actionSegmentReplanningEnabled());
        state.put("scriptSegmentObservation", spec.intelligence().scriptSegmentObservationEnabled());
        state.put("modelRequired", spec.intelligence().requiresModel(spec.mode()));
        state.put("safety", spec.safety().id());
        state.put("hazardAvoidance", spec.safety().hazardAvoidanceEnabled());
        state.put("fallProtection", spec.safety().fallProtectionEnabled());
        state.put("threatPreemption", spec.safety().threatPreemptionEnabled());
        state.put("safetyPriority", spec.safety().progressMayBePreempted());
        state.put("observation", spec.controls().observation());
        state.put("combatPolicy", spec.controls().combatPolicy());
        state.put("allowBlockBreaking", spec.controls().allowBlockBreaking());
        state.put("allowBlockPlacing", spec.controls().allowBlockPlacing());
        state.put("allowCommands", spec.controls().allowCommands());
        state.put("executorModel", modelProvider.id());
        state.put("reasoningEffort", modelProvider.reasoningEffort());
        state.put("modelMeasuredP95Ms", modelProvider.measuredP95LatencyMs());
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
                state.put("activeSegment", segment.id());
                var pending = new ArrayList<>(segment.steps());
                while (!pending.isEmpty()) {
                    if (completedSteps >= spec.maxSteps() || elapsedMs(started) >= spec.maxDurationMs()) {
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "goal budget exhausted");
                        break;
                    }
                    var selection = spec.mode() == GoalMode.REALTIME
                            ? selectRealtimeStep(spec, state, pending)
                            : new StepSelection(pending.get(0), "declared script order", 0);
                    var selected = selection.step();
                    if (spec.mode() == GoalMode.REALTIME) {
                        var decisionKind = spec.intelligence().modelReplanningEnabled()
                                ? "model-decision" : "deterministic-selection";
                        state.put("lastRealtimeSelection", Map.of(
                                "segment", segment.id(), "step", selected.id(),
                                "candidateIndex", selection.candidateIndex(),
                                "rationale", selection.rationale()));
                        trace.add(Map.of("step", "decision." + selected.id(), "kind", decisionKind,
                                "segment", segment.id(), "candidateIndex", selection.candidateIndex(),
                                "rationale", selection.rationale()));
                    }
                    if (elapsedMs(started) >= spec.maxDurationMs()) {
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "goal budget exhausted after planning");
                        break;
                    }
                    pending.remove(selected);
                    var stepStarted = System.nanoTime();
                    ResultEnvelope result;
                    if (selected.kind() == GoalStepKind.ASSERT) {
                        result = ResultEnvelope.ok(selected.id(), Map.of("asserted", true));
                    } else if (survivalCheatCapability(plan, spec, selected.capability())) {
                        result = ResultEnvelope.error(selected.id(), ResultEnvelope.Status.ERROR,
                                StructuredError.of("SURVIVAL_POLICY_DENIED",
                                        "survival-scoped goals cannot invoke commands or direct world mutation",
                                        false));
                    } else if (commandCapability(selected.capability())
                            && !spec.controls().allowCommands()) {
                        result = ResultEnvelope.error(selected.id(), ResultEnvelope.Status.ERROR,
                                StructuredError.of("COMMANDS_DISABLED",
                                        "goal command execution is disabled by allowCommands=false", false));
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
                    var postObservation = postObservationCapability(spec, selected);
                    if (postObservation != null) {
                        if (completedSteps >= spec.maxSteps()) {
                            pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "no budget for post-action observation");
                            break;
                        }
                        var observed = invokeWithTransientRetry(invoker, postObservation, "1.0", Map.of(),
                                spec.dryRun(), spec, started);
                        actionCount++;
                        if (observed.status() != ResultEnvelope.Status.OK) {
                            pendingFailure = failureFor(observed);
                            break;
                        }
                        steps.put("postObserve." + selected.id(), observed.output());
                        completedSteps++;
                        trace.add(Map.of("step", "postObserve." + selected.id(), "kind", "observe",
                                "capability", postObservation, "status", "ok"));
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
                state.put("lastCompletedSegment", segment.id());
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

    private StepSelection selectRealtimeStep(GoalSpec spec, Map<String, Object> state, List<GoalStep> pending) {
        if (!spec.intelligence().modelReplanningEnabled()) {
            return new StepSelection(pending.get(0), "guarded declared order", 0);
        }
        var decision = modelProvider.choose(new GoalDecisionRequest(spec, state, pending)).orElseGet(() -> {
            if (spec.intelligence().requiresModel(spec.mode())) {
                throw new IllegalStateException("adaptive-v1 executor returned no decision");
            }
            return new GoalDecision(0, "provider returned no decision");
        });
        if (decision.candidateIndex() >= pending.size()) {
            throw new IllegalArgumentException("realtime model selected an invalid candidate index");
        }
        return new StepSelection(pending.get(decision.candidateIndex()), decision.rationale(),
                decision.candidateIndex());
    }

    private static String postObservationCapability(GoalSpec spec, GoalStep selected) {
        if (selected.kind() != GoalStepKind.INVOKE) return null;
        var capability = selected.capability();
        if (capability == null || "minecraft.player.state.read".equals(capability)
                || "minecraft.ui.state.read".equals(capability)
                || "minecraft.server.info.read".equals(capability)) return null;

        if (spec.mode() == GoalMode.REALTIME) {
            if (capability.startsWith("lodestone.ui.") || capability.startsWith("minecraft.ui.")
                    || capability.startsWith("minecraft.input.")) return "minecraft.ui.state.read";
            if (commandCapability(capability)) return "minecraft.server.info.read";
            return "minecraft.player.state.read";
        }

        if (spec.intelligence().scriptSegmentObservationEnabled()) {
            if (capability.startsWith("lodestone.ui.") || capability.startsWith("minecraft.ui.")
                    || capability.startsWith("minecraft.input.")) return "minecraft.ui.state.read";
            if (commandCapability(capability)) return "minecraft.server.info.read";
            if (capability.startsWith("minecraft.player.") || capability.startsWith("minecraft.world.")
                    || capability.startsWith("minecraft.entity.") || capability.startsWith("minecraft.goal.")) {
                return "minecraft.player.state.read";
            }
        }
        return selected.observeAfter() ? "minecraft.player.state.read" : null;
    }

    private static boolean survivalCheatCapability(GoalPlan plan, GoalSpec spec, String capability) {
        if (!survivalScoped(plan, spec) || capability == null) return false;
        return commandCapability(capability)
                || capability.equals("minecraft.world.block.write")
                || capability.equals("minecraft.world.blocks.write");
    }

    private static boolean commandCapability(String capability) {
        return capability != null && (capability.startsWith("minecraft.command.")
                || capability.startsWith("minecraft.player.command."));
    }

    private static boolean survivalScoped(GoalPlan plan, GoalSpec spec) {
        return "survival".equals(String.valueOf(plan.metadata().get("gameMode")))
                || survivalScopedId(plan.id()) || survivalScopedId(spec.taskId());
    }

    private static boolean survivalScopedId(String id) {
        return id != null && (id.startsWith("survival.") || id.startsWith("combat.")
                || id.equals("navigation.safe-waypoint") || id.equals("navigation.reach-waypoint"));
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
        var selectedModel = spec.mode() == GoalMode.REALTIME
                ? spec.intelligence().modelReplanningEnabled() ? modelProvider.id() : "deterministic-realtime"
                : "script-interpreter";
        return new GoalRunReport(UUID.randomUUID().toString(), planId, spec.goal(), spec.mode(), status, message,
                elapsedMs(started), completedSteps, completedSegments, selectedModel, trace, state);
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

    private record StepSelection(GoalStep step, String rationale, int candidateIndex) {
    }
}
