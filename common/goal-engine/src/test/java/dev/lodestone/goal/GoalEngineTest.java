// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalEngineTest {
    @Test
    void intelligenceAndSafetyAreIndependentAndReachNativeWorkflow() {
        var spec = new GoalSpec("get a wooden axe and mine an entire tree", GoalMode.REALTIME,
                "survival.wooden-axe-mine-tree", 20, 10_000, false, null, true,
                GoalIntelligence.ADAPTIVE_V1, GoalSafety.HIGH);
        var workflow = new BuiltinGoalPlanner().plan(spec).plan().segments().stream()
                .flatMap(segment -> segment.steps().stream())
                .filter(step -> "minecraft.goal.survival.wooden-axe-tree".equals(step.capability()))
                .findFirst().orElseThrow();

        assertEquals("adaptive-v1", workflow.input().get("intelligence"));
        assertEquals("high", workflow.input().get("safety"));
        assertEquals("loaded-chunks", workflow.input().get("observation"));
        assertEquals("defensive", workflow.input().get("combatPolicy"));
        assertEquals(true, workflow.input().get("allowBlockBreaking"));
        assertEquals(true, workflow.input().get("allowBlockPlacing"));
    }

    @Test
    void adaptiveIntelligenceCanUseLayeredScriptMode() {
        var spec = new GoalSpec("test", GoalMode.SCRIPT, null, 10, 10_000, false, null, false,
                GoalIntelligence.ADAPTIVE_V1, GoalSafety.HIGH);
        assertEquals(GoalIntelligence.ADAPTIVE_V1, spec.intelligence());
        assertFalse(spec.intelligence().requiresModel(spec.mode()));
    }

    @Test
    void scriptSegmentsPassStructuredStateToLaterStepsAndVerifyPredicates() {
        var calls = new ArrayList<Map<String, Object>>();
        GoalInvoker invoker = (capability, version, input, dryRun) -> {
            calls.add(Map.of("capability", capability, "input", input));
            return switch (capability) {
                case "test.observe" -> ResultEnvelope.ok("observe", Map.of("value", "ready"));
                case "test.action" -> ResultEnvelope.ok("action", Map.of("accepted", true));
                default -> ResultEnvelope.error("error", ResultEnvelope.Status.ERROR,
                        dev.lodestone.protocol.StructuredError.of("UNKNOWN", "unknown fake capability", false));
            };
        };
        var plan = new GoalPlan("state-handoff", "test", List.of(
                new GoalSegment("prepare", "observe", List.of(GoalStep.observe("probe", "test.observe", Map.of())), List.of()),
                new GoalSegment("act", "invoke", List.of(GoalStep.invoke("action", "test.action", "1.0",
                        Map.of("source", "${steps.probe.value}"), false,
                        new GoalAssertion("steps.action.accepted", "equals", true))), List.of())),
                Map.of("completionPredicateReady", true));

        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("test", GoalMode.SCRIPT, null, 10, 10_000, false, plan), invoker);

        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals("ready", calls.get(1).get("input") instanceof Map<?, ?> input ? input.get("source") : null);
    }

    @Test
    void realtimeObservesAfterActionsAndReleasesOwnedInputs() {
        var calls = new ArrayList<String>();
        GoalInvoker invoker = (capability, version, input, dryRun) -> {
            calls.add(capability);
            return ResultEnvelope.ok(capability, capability.equals("test.action")
                    ? Map.of("accepted", true) : Map.of("position", Map.of("x", 1)));
        };
        var plan = new GoalPlan("realtime", "test", List.of(new GoalSegment("run", "run", List.of(
                GoalStep.invoke("action", "test.action", "1.0", Map.of(), true,
                        new GoalAssertion("steps.action.accepted", "equals", true))), List.of())),
                Map.of("completionPredicateReady", true));

        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("test", GoalMode.REALTIME, null, 10, 10_000, false, plan), invoker);

        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals(List.of("test.action", "minecraft.player.state.read", "minecraft.input.release-all"), calls);
    }

    @Test
    void knownCraftingGoalRequiresAuthenticWorkflowAndHardTerminalPredicates() {
        var planned = new BuiltinGoalPlanner().plan(GoalSpec.of("get a wooden axe and mine an entire tree",
                GoalMode.SCRIPT, null, false));
        assertTrue(planned.supported());
        assertTrue(planned.plan().completionPredicateReady());
        assertTrue(planned.plan().segments().stream().flatMap(segment -> segment.steps().stream())
                .anyMatch(step -> "minecraft.goal.survival.wooden-axe-tree".equals(step.capability())));
        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("get a wooden axe and mine an entire tree", GoalMode.SCRIPT, null, 20,
                        10_000, false, planned.plan()), (capability, version, input, dryRun) -> {
                    if ("minecraft.goal.survival.wooden-axe-tree".equals(capability)) {
                        return ResultEnvelope.ok("workflow", Map.ofEntries(
                                Map.entry("survival", true), Map.entry("freshWorld", true),
                                Map.entry("handMinedLogs", 3), Map.entry("planksCrafted", 12),
                                Map.entry("sticksCrafted", 4), Map.entry("craftingTableCrafted", true),
                                Map.entry("woodenAxeCrafted", true), Map.entry("woodenAxeEquipped", true),
                                Map.entry("targetTreeInitialLogs", 5), Map.entry("targetTreeRemainingLogs", 0),
                                Map.entry("fullTreeMined", true),
                                Map.entry("allTargetLogsMinedWithWoodenAxe", true),
                                Map.entry("playerAlive", true), Map.entry("healthAtEnd", 20.0),
                                Map.entry("commandsUsed", false), Map.entry("directMutationUsed", false)));
                    }
                    return ResultEnvelope.ok(capability, Map.of("ok", true));
                });
        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals("all goal predicates passed", report.message());
    }

    @Test
    void woolTreeZombiePlanPropagatesCleanGameplayAndRequiresReactiveDefense() {
        var quiet = new GoalSpec("build a tree with wool and defend from a zombie using a diamond sword",
                GoalMode.SCRIPT, "creative.wool-tree-zombie-defense", 256, 120_000,
                false, null, true);
        var plan = new BuiltinGoalPlanner().plan(quiet).plan();
        var workflow = plan.segments().stream().flatMap(segment -> segment.steps().stream())
                .filter(step -> "minecraft.goal.creative.wool-tree-zombie-defense".equals(step.capability()))
                .findFirst().orElseThrow();
        assertEquals(true, workflow.input().get("suppressInGameMessages"));
        assertTrue(workflow.assertions().stream().anyMatch(assertion ->
                assertion.path().equals("steps.wool-tree-defense.inGameMessagesEmitted")
                        && assertion.expected().equals(0)));
        assertTrue(workflow.assertions().stream().anyMatch(assertion ->
                assertion.path().equals("steps.wool-tree-defense.defensiveResponses")));
        assertTrue(workflow.assertions().stream().anyMatch(assertion ->
                assertion.path().equals("steps.wool-tree-defense.unconditionalKillRoutine")
                        && assertion.expected().equals(false)));

        var defaultOld = new BuiltinGoalPlanner().plan(GoalSpec.of(
                "get a wooden axe and mine an entire tree", GoalMode.SCRIPT,
                "survival.wooden-axe-mine-tree", false)).plan();
        var oldWorkflow = defaultOld.segments().stream().flatMap(segment -> segment.steps().stream())
                .filter(step -> "minecraft.goal.survival.wooden-axe-tree".equals(step.capability()))
                .findFirst().orElseThrow();
        assertFalse((Boolean) oldWorkflow.input().get("suppressInGameMessages"));
    }

    @Test
    void netherPlanInfersRealtimePreferredWorkflowAndTerminalDimension() {
        var spec = new GoalSpec("load into a fresh survival world and get to the Nether",
                GoalMode.REALTIME, null, 256, 480_000, false, null, true);
        var plan = new BuiltinGoalPlanner().plan(spec).plan();
        assertEquals("survival.reach-nether", plan.id());
        assertEquals(true, plan.metadata().get("realtimePreferred"));
        var workflow = plan.segments().stream().flatMap(segment -> segment.steps().stream())
                .filter(step -> "minecraft.goal.survival.reach-nether".equals(step.capability()))
                .findFirst().orElseThrow();
        assertEquals(true, workflow.input().get("suppressInGameMessages"));
        assertTrue(workflow.assertions().stream().anyMatch(assertion ->
                assertion.path().equals("steps.nether-workflow.reachedNether")
                        && assertion.expected().equals(true)));
        assertTrue(workflow.assertions().stream().anyMatch(assertion ->
                assertion.path().equals("steps.nether-workflow.finalDimension")
                        && assertion.expected().equals("minecraft:the_nether")));
    }

    @Test
    void netherPlanEntersRequestedSeedThroughCreateWorldUi() {
        var spec = new GoalSpec("load into a fresh survival world and get to the Nether",
                GoalMode.REALTIME, "survival.reach-nether", 256, 480_000, false, null, true,
                GoalIntelligence.ADAPTIVE_V1, GoalSafety.HIGH, GoalControls.defaults(),
                "281475037711136");

        var plan = new BuiltinGoalPlanner().plan(spec).plan();
        var seedSegments = plan.segments().stream()
                .filter(segment -> List.of("open-world-options", "focus-world-seed",
                        "insert-world-seed", "create-fresh-world").contains(segment.id()))
                .toList();
        var createSteps = seedSegments.stream().flatMap(segment -> segment.steps().stream()).toList();

        assertEquals(List.of("open-world-options", "focus-world-seed", "insert-world-seed", "create-world"),
                createSteps.stream().map(GoalStep::id).toList());
        assertEquals(List.of("open-world-options", "focus-world-seed", "insert-world-seed", "create-fresh-world"),
                seedSegments.stream().map(GoalSegment::id).toList());
        assertEquals(Map.of("target", "world_tab"), createSteps.get(0).input());
        assertEquals(Map.of("target", "world_seed"), createSteps.get(1).input());
        assertEquals("minecraft.ui.text.insert", createSteps.get(2).capability());
        assertEquals(Map.of("text", "281475037711136"), createSteps.get(2).input());
        assertEquals(false, plan.metadata().get("randomFreshWorldRequired"));
    }

    @Test
    void durationBudgetTurnsACompletedLateActionIntoTimeout() {
        var plan = new GoalPlan("slow", "slow", List.of(new GoalSegment("run", "run", List.of(
                new GoalStep("slow", GoalStepKind.OBSERVE, "test.observe", "1.0", Map.of(),
                        List.of(new GoalAssertion("steps.slow.done", "equals", true)), false)), List.of())),
                Map.of("completionPredicateReady", true));
        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("slow", GoalMode.SCRIPT, null, 10, 100, false, plan),
                        (capability, version, input, dryRun) -> {
                            try { Thread.sleep(150); } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return ResultEnvelope.ok(capability, Map.of("done", true));
                        });

        assertEquals(GoalStatus.TIMED_OUT, report.status());
        assertTrue(report.message().contains("duration budget"));
    }

    @Test
    void creativeReadbackRequiresEveryBlockToMatch() {
        var plan = new BuiltinGoalPlanner().plan(GoalSpec.of("place a pillar", GoalMode.SCRIPT,
                "creative.place-pillar", false)).plan();
        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("place a pillar", GoalMode.SCRIPT, "creative.place-pillar", 20,
                        10_000, false, plan), (capability, version, input, dryRun) -> {
                    if ("minecraft.world.blocks.read".equals(capability)) {
                        return ResultEnvelope.ok(capability, Map.of("count", 3, "blocks", List.of(
                                Map.of("block", "minecraft:stone"), Map.of("block", "minecraft:air"),
                                Map.of("block", "minecraft:stone"))));
                    }
                    return ResultEnvelope.ok(capability, Map.of("ok", true));
                });

        assertEquals(GoalStatus.FAILED, report.status());
        assertTrue(report.message().contains("step postcondition"));
    }

    @Test
    void realtimeCleanupFailureCannotReportSuccess() {
        var plan = new GoalPlan("cleanup", "cleanup", List.of(new GoalSegment("run", "run", List.of(
                GoalStep.invoke("action", "test.action", "1.0", Map.of(), false,
                        new GoalAssertion("steps.action.done", "equals", true))), List.of())),
                Map.of("completionPredicateReady", true));
        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("cleanup", GoalMode.REALTIME, null, 10, 10_000, false, plan),
                        (capability, version, input, dryRun) -> {
                            if ("minecraft.input.release-all".equals(capability)) {
                                return ResultEnvelope.error(capability, ResultEnvelope.Status.ERROR,
                                        dev.lodestone.protocol.StructuredError.of("CLEANUP_FAILED",
                                                "release failed", true));
                            }
                            return ResultEnvelope.ok(capability, Map.of("done", true));
                        });

        assertEquals(GoalStatus.INDETERMINATE, report.status());
        assertTrue(report.message().contains("cleanup failed"));
    }

    @Test
    void retriesTransientRateLimitWithinGoalBudget() {
        var calls = new ArrayList<String>();
        var plan = new GoalPlan("rate-limit", "rate-limit", List.of(new GoalSegment("run", "run", List.of(
                new GoalStep("probe", GoalStepKind.OBSERVE, "test.observe", "1.0", Map.of(),
                        List.of(new GoalAssertion("steps.probe.ready", "equals", true)), false)), List.of())),
                Map.of("completionPredicateReady", true));
        GoalInvoker invoker = (capability, version, input, dryRun) -> {
            calls.add(capability);
            if (calls.size() == 1) {
                return ResultEnvelope.error(capability, ResultEnvelope.Status.ERROR,
                        dev.lodestone.protocol.StructuredError.of("RATE_LIMIT_EXCEEDED", "try again", true));
            }
            return ResultEnvelope.ok(capability, Map.of("ready", true));
        };

        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("rate-limit", GoalMode.SCRIPT, null, 10, 5_000, false, plan), invoker);

        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals(List.of("test.observe", "test.observe"), calls);
    }
}
