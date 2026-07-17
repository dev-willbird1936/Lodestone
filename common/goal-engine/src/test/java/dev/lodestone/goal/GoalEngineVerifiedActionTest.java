// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.StructuredError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GoalEngineVerifiedActionTest {
    @Test
    void survivalPlanCannotCrossTheRawInputBoundary() {
        var step = new GoalStep("raw", GoalStepKind.INVOKE, "minecraft.input.key.set", "1.0",
                Map.of("key", "key.forward", "down", true),
                List.of(new GoalAssertion("steps.raw.accepted", "equals", true)), false);
        var plan = new GoalPlan("survival.raw", "survival raw", List.of(
                new GoalSegment("run", "run", List.of(step), List.of())),
                Map.of("gameMode", "survival", "completionPredicateReady", true));
        var calls = new AtomicInteger();
        var report = new GoalEngine(spec -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("survival raw", GoalMode.SCRIPT, "survival.raw", 10,
                        10_000, false, plan), (capability, version, input, dryRun) -> {
                    calls.incrementAndGet();
                    return ResultEnvelope.ok(capability, Map.of("accepted", true));
                });

        assertEquals(GoalStatus.FAILED, report.status());
        assertEquals(0, calls.get());
        assertTrue(report.message().contains("survival") || report.message().contains("raw"));
    }

    @Test
    void transientActionFailureUsesOneBoundedRetryBeforeSuccess() {
        var step = new GoalStep("probe", GoalStepKind.OBSERVE, "test.retry", "1.0", Map.of(),
                List.of(new GoalAssertion("steps.probe.ready", "equals", true)), false);
        var plan = new GoalPlan("retry", "retry", List.of(
                new GoalSegment("run", "run", List.of(step), List.of())),
                Map.of("completionPredicateReady", true));
        var calls = new AtomicInteger();
        var report = new GoalEngine(spec -> GoalPlanner.PlanResult.supported(spec.customPlan()),
                request -> java.util.Optional.of(new GoalDecision(0, "test")))
                .run(new GoalSpec("retry", GoalMode.SCRIPT, null, 10, 10_000, false, plan),
                        (capability, version, input, dryRun) -> calls.getAndIncrement() == 0
                                ? ResultEnvelope.error(capability, ResultEnvelope.Status.ERROR,
                                StructuredError.of("TEMPORARY", "retry", true))
                                : ResultEnvelope.ok(capability, Map.of("ready", true)));

        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals(2, calls.get());
    }
}
