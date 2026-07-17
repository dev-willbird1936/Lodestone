// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GoalActionBoundaryTest {
    @Test
    void survivalRejectsRawInputAndUnknownCapabilities() {
        var spec = GoalSpec.of("survival", GoalMode.REALTIME, "survival.collect-wood", false);
        var plan = new GoalPlan("survival.collect-wood", "survival", List.of(
                new GoalSegment("run", "run", List.of(), List.of())), Map.of("gameMode", "survival"));

        var rawInput = new GoalStep("raw", GoalStepKind.INVOKE, "minecraft.input.key.set", "1.0",
                Map.of("key", "key.forward", "down", true), List.of(), false);
        var unknown = new GoalStep("unknown", GoalStepKind.INVOKE, "minecraft.generated.action", "1.0",
                Map.of(), List.of(), false);

        assertFalse(GoalActionBoundary.check(spec, plan, rawInput).allowed());
        assertFalse(GoalActionBoundary.check(spec, plan, unknown).allowed());
    }

    @Test
    void survivalMovementRequiresAnActionBoundaryPostcondition() {
        var spec = GoalSpec.of("navigate", GoalMode.REALTIME, "navigation.reach-waypoint", false);
        var plan = new GoalPlan("navigation.reach-waypoint", "navigate", List.of(
                new GoalSegment("run", "run", List.of(), List.of())), Map.of("gameMode", "survival"));
        var unsafe = new GoalStep("move", GoalStepKind.INVOKE, "minecraft.player.move", "1.0",
                Map.of("forward", 1.0), List.of(), false);
        var verified = new GoalStep("move", GoalStepKind.INVOKE, "minecraft.player.move", "1.0",
                Map.of("forward", 1.0), List.of(), true);

        assertFalse(GoalActionBoundary.check(spec, plan, unsafe).allowed());
        assertTrue(GoalActionBoundary.check(spec, plan, verified).allowed());
    }

    @Test
    void nativeSurvivalGoalIsTypedAndMustCarryItsTerminalAssertion() {
        var spec = GoalSpec.of("reach Nether", GoalMode.SCRIPT, "survival.reach-nether", false);
        var plan = new GoalPlan("survival.reach-nether", "reach Nether", List.of(
                new GoalSegment("run", "run", List.of(), List.of())), Map.of("gameMode", "survival"));
        var step = new GoalStep("nether", GoalStepKind.INVOKE, "minecraft.goal.survival.reach-nether", "1.0",
                Map.of(), List.of(new GoalAssertion("steps.nether.reachedNether", "equals", true)), false);

        assertTrue(GoalActionBoundary.check(spec, plan, step).allowed());
    }
}
