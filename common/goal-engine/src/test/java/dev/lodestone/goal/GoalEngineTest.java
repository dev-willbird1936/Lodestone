// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalEngineTest {
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
    void knownCraftingGoalIsHonestAboutMissingNativeCapability() {
        var planned = new BuiltinGoalPlanner().plan(GoalSpec.of("get a wooden axe and mine an entire tree",
                GoalMode.SCRIPT, null, false));
        assertTrue(planned.supported());
        assertTrue(planned.plan().segments().stream().flatMap(segment -> segment.steps().stream())
                .anyMatch(step -> "minecraft.inventory.craft".equals(step.capability())));
        var report = new GoalEngine((spec) -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("get a wooden axe and mine an entire tree", GoalMode.SCRIPT, null, 20,
                        10_000, false, planned.plan()), (capability, version, input, dryRun) -> {
                    if ("minecraft.inventory.craft".equals(capability)) {
                        return ResultEnvelope.error("craft", ResultEnvelope.Status.ERROR,
                                dev.lodestone.protocol.StructuredError.of("CAPABILITY_UNAVAILABLE",
                                        "minecraft.inventory.craft is unavailable", false));
                    }
                    return ResultEnvelope.ok(capability, Map.of("ok", true));
                });
        assertEquals(GoalStatus.UNSUPPORTED, report.status());
        assertTrue(report.message().contains("minecraft.inventory.craft"));
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
}
