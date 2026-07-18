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
        var modelGeneratedPlan = false;
        // Gated by planning depth (modelPlanSynthesisEnabled), not enum identity, so deliberate-v1
        // keeps every adaptive-v1 capability including synthesizing a plan for an unsupported task.
        if (!planned.supported() && spec.intelligence().modelPlanSynthesisEnabled()
                && !modelProvider.fallback()) {
            try {
                var generated = modelProvider.plan(new GoalPlanRequest(spec, builtInTaskMaps()));
                if (generated.isPresent()) {
                    var validationFailure = validateGeneratedPlan(generated.get(), spec);
                    if (validationFailure == null) {
                        planned = GoalPlanner.PlanResult.supported(generated.get());
                        modelGeneratedPlan = true;
                    } else {
                        planned = GoalPlanner.PlanResult.unsupported(validationFailure);
                    }
                } else {
                    planned = GoalPlanner.PlanResult.unsupported(
                            "no bounded plan matches goal and the configured planner returned no valid plan");
                }
            } catch (RuntimeException failure) {
                planned = GoalPlanner.PlanResult.unsupported(
                        "model plan synthesis failed: " + safeMessage(failure));
            }
        }
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
        state.put("stepPreconditionFiltering", true);
        state.put("safeNavigationPlanning", spec.intelligence().safeNavigationPlanningEnabled());
        state.put("obstructionRecovery", spec.intelligence().obstructionRecoveryEnabled());
        state.put("obstructionMining", spec.intelligence().obstructionMiningEnabled());
        state.put("actionSegmentReplanning", spec.intelligence().actionSegmentReplanningEnabled());
        state.put("scriptSegmentObservation", spec.intelligence().scriptSegmentObservationEnabled());
        state.put("modelRequired", spec.intelligence().requiresModel(spec.mode()));
        state.put("planSource", modelGeneratedPlan ? "model-generated" : "declared");
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
        state.put("verifiedActionBoundary", true);
        state.put("recoveryLayer", "bounded-typed-v1");
        var steps = new LinkedHashMap<String, Object>();
        state.put("steps", steps);
        var trace = new ArrayList<Map<String, Object>>();
        var recoveryCoordinator = new GoalRecoveryCoordinator();
        var completedSteps = 0;
        var completedSegments = 0;
        var actionCount = 0;
        var pendingFailure = (ExecutionFailure) null;
        var cleanupFailure = (ExecutionFailure) null;
        GoalRunReport finalReport;
        try {
            for (var segment : plan.segments()) {
                state.put("activeSegment", segment.id());
                consultRealtimeLookaheadPlan(spec, state);
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
                    if (!inputsResolved(selected.input(), state)) {
                        throw new IllegalStateException("goal step dependency is unresolved: " + selected.id());
                    }
                    var failedPreconditions = failedPreconditions(selected, state);
                    if (!failedPreconditions.isEmpty()) {
                        state.put("lastPreconditionFailure", Map.of(
                                "step", selected.id(),
                                "failed", failedPreconditions.stream().map(GoalAssertion::toMap).toList()));
                        throw new IllegalStateException("goal step precondition failed: " + selected.id());
                    }
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
                    var actionBoundary = GoalActionBoundary.check(spec, plan, selected);
                    state.put("lastActionContract", Map.of("step", selected.id(),
                            "kind", actionBoundary.kind().toString(), "allowed", actionBoundary.allowed(),
                            "reason", actionBoundary.reason()));
                    ResultEnvelope result;
                    if (selected.kind() == GoalStepKind.ASSERT) {
                        result = ResultEnvelope.ok(selected.id(), Map.of("asserted", true));
                    } else if (!actionBoundary.allowed()) {
                        result = ResultEnvelope.error(selected.id(), ResultEnvelope.Status.ERROR,
                                StructuredError.of("ACTION_CONTRACT_REJECTED", actionBoundary.reason(), false));
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
                        var input = decorateGoalInput(selected.capability(),
                                resolve(selected.input(), state), spec);
                        result = invokeWithTransientRetry(invoker, selected.capability(), selected.capabilityVersion(),
                                input, spec.dryRun(), spec, started);
                        actionCount++;
                    }
                    if (result.status() != ResultEnvelope.Status.OK) {
                        var failureKind = GoalRecoveryCoordinator.classify(result);
                        var recovery = recoveryCoordinator.decide(selected.id(), failureKind,
                                hasEligibleAlternative(pending, state), spec.mode() == GoalMode.REALTIME
                                        && spec.intelligence().actionSegmentReplanningEnabled());
                        state.put("lastRecovery", recoveryMap(selected, recovery));
                        trace.add(Map.of("step", "recovery." + selected.id(),
                                "kind", "bounded-recovery", "decision", recovery.decision().toString(),
                                "failure", recovery.failure().toString(), "attempt", recovery.attempt(),
                                "rationale", recovery.rationale()));
                        if (recovery.decision() == GoalRecoveryCoordinator.RecoveryDecision.RETRY_ONCE
                                || (recovery.decision() == GoalRecoveryCoordinator.RecoveryDecision.REPLAN_LOCAL
                                        && spec.mode() == GoalMode.REALTIME)) {
                            // ArrayList#addFirst is a Java 21 SequencedCollection method; this module
                            // compiles with --release 17, so use the equivalent indexed insert instead.
                            // REPLAN_LOCAL only fires when no other pending step is currently eligible
                            // (see hasEligibleAlternative below), so the failed step must go back onto
                            // pending itself - otherwise nothing would ever be eligible again next loop.
                            pending.add(0, selected);
                            continue;
                        }
                        if (recovery.decision() == GoalRecoveryCoordinator.RecoveryDecision.TRY_DECLARED_ALTERNATIVE
                                && spec.mode() == GoalMode.REALTIME && !pending.isEmpty()) {
                            continue;
                        }
                        state.put("failureCause", failureCause(failureKind, result));
                        pendingFailure = failureFor(result);
                        trace.add(trace(selected, result, elapsedMs(stepStarted)));
                        break;
                    }
                    steps.put(selected.id(), result.output());
                    trace.add(trace(selected, result, elapsedMs(stepStarted)));
                    completedSteps++;
                    if (elapsedMs(started) >= spec.maxDurationMs()) {
                        state.put("failureCause", "timeout:duration-budget");
                        pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT,
                                "goal duration budget exhausted after step: " + selected.id());
                        break;
                    }
                    if (!assertionsPass(selected.assertions(), state)) {
                        var failureKind = GoalFailureKind.POSTCONDITION_FAILED;
                        var recovery = recoveryCoordinator.decide(selected.id(), failureKind,
                                hasEligibleAlternative(pending, state), spec.mode() == GoalMode.REALTIME
                                        && spec.intelligence().actionSegmentReplanningEnabled());
                        state.put("lastRecovery", recoveryMap(selected, recovery));
                        trace.add(Map.of("step", "recovery." + selected.id(),
                                "kind", "bounded-recovery", "decision", recovery.decision().toString(),
                                "failure", failureKind.toString(), "attempt", recovery.attempt(),
                                "rationale", recovery.rationale()));
                        if (recovery.decision() == GoalRecoveryCoordinator.RecoveryDecision.REPLAN_LOCAL
                                && spec.mode() == GoalMode.REALTIME) {
                            // Same reasoning as the action-failure branch above: REPLAN_LOCAL only
                            // fires when nothing else pending is currently eligible, so the step that
                            // just failed its own postcondition must be retried, not abandoned.
                            pending.add(0, selected);
                            continue;
                        }
                        if (recovery.decision() == GoalRecoveryCoordinator.RecoveryDecision.TRY_DECLARED_ALTERNATIVE
                                && spec.mode() == GoalMode.REALTIME && !pending.isEmpty()) {
                            continue;
                        }
                        state.putIfAbsent("failureCause", "postcondition:" + selected.id());
                        pendingFailure = new ExecutionFailure(GoalStatus.FAILED,
                                "step postcondition failed: " + selected.id());
                        break;
                    }
                    var postObservation = postObservationCapability(spec, selected);
                    if (postObservation != null) {
                        if (completedSteps >= spec.maxSteps()) {
                            pendingFailure = new ExecutionFailure(GoalStatus.TIMED_OUT, "no budget for post-action observation");
                            break;
                        }
                        var observed = invokeWithTransientRetry(invoker, postObservation,
                                postObservationVersion(postObservation),
                                postObservationInput(spec, postObservation),
                                spec.dryRun(), spec, started);
                        actionCount++;
                        if (observed.status() != ResultEnvelope.Status.OK) {
                            pendingFailure = failureFor(observed);
                            break;
                        }
                        postObservationSteps(steps).put(selected.id(), observed.output());
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
                    state.putIfAbsent("failureCause", "postcondition:" + segment.id());
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
            state.putIfAbsent("failureCause", extractDeclaredCause(safeMessage(failure), "error:unhandled"));
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
        var eligible = eligibleSteps(pending, state);
        if (eligible.isEmpty()) {
            throw new IllegalStateException("realtime goal has no eligible step; pending inputs or preconditions are unresolved");
        }
        if (!spec.intelligence().modelReplanningEnabled()) {
            return new StepSelection(eligible.get(0), "guarded declared order", 0);
        }
        var decision = modelProvider.choose(new GoalDecisionRequest(spec, state, eligible)).orElseGet(() -> {
            if (spec.intelligence().requiresModel(spec.mode())) {
                throw new IllegalStateException("adaptive-v1 executor returned no decision");
            }
            return new GoalDecision(0, "provider returned no decision");
        });
        if (decision.candidateIndex() >= eligible.size()) {
            throw new IllegalArgumentException("realtime model selected an invalid candidate index");
        }
        // Additional, per-decision observability field: the base "reasoningEffort" state entry set
        // once in run() stays the provider's configured default. This records what a DELIBERATE_V1
        // situational deliberation budget (or any other provider) actually used for this one step.
        state.put("lastDecisionReasoningEffort",
                decision.reasoningEffort() != null ? decision.reasoningEffort() : modelProvider.reasoningEffort());
        return new StepSelection(eligible.get(decision.candidateIndex()), decision.rationale(),
                decision.candidateIndex());
    }

    /**
     * Only the top deliberate tier also consults the model's bounded lookahead planner at realtime
     * segment boundaries, even though the native/declared task is already supported, so its
     * per-step choices are informed by a short-lived strategy rather than being purely greedy. This
     * is deliberately cheap: it runs once per segment boundary (never per step), and a provider that
     * cannot or does not synthesize a plan simply leaves no lookahead behind - the goal still runs.
     */
    private void consultRealtimeLookaheadPlan(GoalSpec spec, Map<String, Object> state) {
        state.remove("lookaheadPlan");
        if (spec.mode() != GoalMode.REALTIME || !spec.intelligence().realtimePlanConsultationEnabled()) {
            return;
        }
        try {
            modelProvider.plan(new GoalPlanRequest(spec, builtInTaskMaps()))
                    .ifPresent(generated -> state.put("lookaheadPlan", summarizeLookaheadPlan(generated)));
        } catch (RuntimeException ignored) {
            // Best-effort strategy hint only; deliberate-v1 must still work with a plan()-less or
            // failing provider, so a lookahead failure never fails the goal itself.
        }
    }

    /** Key fields only - segment/step ids and a short description, never the verbose plan object. */
    private static Map<String, Object> summarizeLookaheadPlan(GoalPlan generated) {
        var segments = generated.segments().stream()
                .map(segment -> (Map<String, Object>) Map.of(
                        "segment", segment.id(),
                        "description", segment.description(),
                        "steps", segment.steps().stream().map(GoalStep::id).toList()))
                .toList();
        return Map.of("planId", generated.id(), "goal", generated.goal(), "segments", segments);
    }

    private static List<Map<String, Object>> builtInTaskMaps() {
        return GoalTaskCatalog.tasks().stream().map(GoalTaskCatalog.TaskDefinition::toMap).toList();
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

    private static Map<String, Object> postObservationInput(GoalSpec spec, String capability) {
        if (!"minecraft.player.state.read".equals(capability)) return Map.of();
        return Map.of("intelligence", spec.intelligence().id(),
                "safety", spec.safety().id());
    }

    private static String postObservationVersion(String capability) {
        return "minecraft.ui.state.read".equals(capability) ? "2.0" : "1.0";
    }

    private static Map<String, Object> decorateGoalInput(String capability, Map<String, Object> input,
                                                          GoalSpec spec) {
        if (capability == null || spec.intelligence() == GoalIntelligence.RAW_V1
                || (!"minecraft.player.move".equals(capability)
                && !"minecraft.player.interact".equals(capability))) return input;
        var decorated = new LinkedHashMap<>(input);
        decorated.putIfAbsent("intelligence", spec.intelligence().id());
        decorated.putIfAbsent("safety", spec.safety().id());
        return decorated;
    }

    private static Map<String, Object> recoveryMap(GoalStep step, GoalRecoveryCoordinator.Decision recovery) {
        return Map.of("step", step.id(), "decision", recovery.decision().toString(),
                "failure", recovery.failure().toString(), "attempt", recovery.attempt(),
                "rationale", recovery.rationale());
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

    private static boolean preconditionsPass(GoalStep step, Map<String, Object> state) {
        return failedPreconditions(step, state).isEmpty();
    }

    /** Steps whose declared input handoffs and preconditions are satisfied by the current state. */
    private static List<GoalStep> eligibleSteps(List<GoalStep> pending, Map<String, Object> state) {
        return pending.stream().filter(step -> inputsResolved(step.input(), state)
                && preconditionsPass(step, state)).toList();
    }

    /**
     * Whether some other already-pending step (the one that just failed has already been removed
     * from {@code pending} by the caller) is currently selectable. A sequential checkpointed
     * workflow's later steps typically hard-depend on the failed step's own recorded output, so a
     * non-empty {@code pending} list does not by itself mean a genuine alternative exists - only
     * this eligibility check does. Recovery decisions that trust a bare non-empty check instead
     * (i.e. "there is more work queued") can wrongly abandon the only step that could ever unblock
     * the rest of the plan, permanently starving every remaining step of its dependency and
     * surfacing as a confusing "no eligible step" failure one selection later instead of a clear
     * failure at the step that actually failed.
     */
    private static boolean hasEligibleAlternative(List<GoalStep> pending, Map<String, Object> state) {
        return !eligibleSteps(pending, state).isEmpty();
    }

    private static String validateGeneratedPlan(GoalPlan plan, GoalSpec spec) {
        if (plan == null || plan.segments().isEmpty()) return "model generated an empty goal plan";
        if (plan.id().length() > 128 || plan.goal().length() > 4_096) {
            return "model generated an oversized plan identity or goal";
        }
        if (plan.segments().size() > 16) return "model generated too many goal segments";
        if (!Boolean.TRUE.equals(plan.metadata().get("completionPredicateReady"))) {
            return "model plan must declare metadata.completionPredicateReady=true";
        }
        var totalSteps = 0;
        var ids = new java.util.HashSet<String>();
        var hasAssertion = false;
        for (var segment : plan.segments()) {
            if (segment.id().length() > 128) return "model generated an oversized segment id";
            if (segment.steps().isEmpty() || segment.steps().size() > 32) {
                return "model generated an empty or oversized segment: " + segment.id();
            }
            totalSteps += segment.steps().size();
            if (!segment.assertions().isEmpty()) hasAssertion = true;
            for (var step : segment.steps()) {
                if (step.id().length() > 128) return "model generated an oversized step id";
                if (!ids.add(step.id())) return "model generated duplicate step id: " + step.id();
                if (!step.assertions().isEmpty()) hasAssertion = true;
                var capability = step.capability();
                if (step.kind() != GoalStepKind.ASSERT && (capability == null
                        || !(capability.startsWith("minecraft.") || capability.startsWith("lodestone.")))) {
                    return "model generated an unsupported capability namespace: " + capability;
                }
                if (capability != null && capability.length() > 256) {
                    return "model generated an oversized capability id";
                }
                var actionBoundary = GoalActionBoundary.check(spec, plan, step);
                if (!actionBoundary.allowed()) return "model generated an action rejected by the verified boundary: "
                        + actionBoundary.reason();
                if (survivalScoped(plan, spec) && survivalUnsafeGeneratedCapability(plan, spec, capability)) {
                    return "model generated an unsafe command, text, or raw-input action for a survival-scoped goal";
                }
            }
        }
        if (totalSteps > 256) return "model generated too many goal steps";
        return hasAssertion ? null : "model plan must include at least one terminal assertion";
    }

    private static boolean survivalUnsafeGeneratedCapability(GoalPlan plan, GoalSpec spec,
                                                             String capability) {
        if (survivalCheatCapability(plan, spec, capability)) return true;
        if (capability == null || "minecraft.input.release-all".equals(capability)) return false;
        return capability.equals("minecraft.chat.send")
                || capability.equals("minecraft.ui.text.insert")
                || capability.startsWith("minecraft.input.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> postObservationSteps(Map<String, Object> steps) {
        var existing = steps.get("postObserve");
        if (existing instanceof Map<?, ?> map) return (Map<String, Object>) map;
        var observations = new LinkedHashMap<String, Object>();
        steps.put("postObserve", observations);
        return observations;
    }

    private static List<GoalAssertion> failedPreconditions(GoalStep step, Map<String, Object> state) {
        return step.preconditions().stream().filter(assertion -> !assertion.test(state)).toList();
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

    @SuppressWarnings("unchecked")
    private static boolean inputsResolved(Object value, Map<String, Object> state) {
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            return readPath(state, text.substring(2, text.length() - 1)) != null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().allMatch(nested -> inputsResolved(nested, state));
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(nested -> inputsResolved(nested, state));
        }
        return true;
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
        var selectedModel = "model-generated".equals(state.get("planSource")) ? modelProvider.id()
                : spec.mode() == GoalMode.REALTIME
                ? spec.intelligence().modelReplanningEnabled() ? modelProvider.id() : "deterministic-realtime"
                : "script-interpreter";
        return new GoalRunReport(UUID.randomUUID().toString(), planId, spec.goal(), spec.mode(), status, message,
                elapsedMs(started), completedSteps, completedSegments, selectedModel, trace, state);
    }

    /**
     * Machine-readable failure cause for terminal reports. Actors embed an explicit
     * {@code cause=<vocabulary>;} marker in typed failure messages; when present it wins,
     * otherwise the shared failure kind supplies a stable fallback. Callers and benchmark
     * harnesses branch retry policy on this value, so it must stay a small fixed vocabulary.
     */
    static String failureCause(GoalFailureKind kind, ResultEnvelope result) {
        var message = result == null || result.error() == null ? null : result.error().message();
        var declared = extractDeclaredCause(message, null);
        if (declared != null) return declared;
        return switch (kind == null ? GoalFailureKind.UNKNOWN : kind) {
            case PLAYER_DIED -> "died:unknown";
            case NO_PROGRESS -> "stall:unknown";
            case PATH_FAILED, TARGET_UNREACHABLE -> "target:unreachable";
            case OBSTRUCTED -> "target:obstructed";
            case SAFETY_REJECTED -> "safety:rejected";
            case PRECONDITION_FAILED -> "precondition:failed";
            case POSTCONDITION_FAILED -> "postcondition:failed";
            case PLAYER_OVERRIDE -> "player:override";
            case WORLD_CHANGED -> "world:changed";
            case TRANSIENT -> "transient:error";
            default -> "error:unclassified";
        };
    }

    private static String extractDeclaredCause(String message, String fallback) {
        if (message == null) return fallback;
        var marker = message.indexOf("cause=");
        if (marker < 0) return fallback;
        var end = message.indexOf(';', marker);
        var cause = (end > marker ? message.substring(marker + 6, end) : message.substring(marker + 6)).trim();
        return cause.isEmpty() || cause.length() > 64 ? fallback : cause;
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
