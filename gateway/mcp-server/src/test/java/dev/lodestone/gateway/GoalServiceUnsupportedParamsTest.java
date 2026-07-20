// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.goal.GoalControls;
import dev.lodestone.goal.GoalIntelligence;
import dev.lodestone.goal.GoalMode;
import dev.lodestone.goal.GoalPlan;
import dev.lodestone.goal.GoalSafety;
import dev.lodestone.goal.GoalSegment;
import dev.lodestone.goal.GoalStep;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalService#run} now delegates to {@link GoalOrchestratorLauncher} - a real
 * subprocess-spawning realtime orchestrator - which does not yet support {@code mode=script},
 * {@code dryRun}, a custom plan, {@code taskId}, or {@code worldSeed}. Every one of those must fail
 * closed with {@link IllegalArgumentException} before the call ever reaches the execution queue (and
 * therefore before any process could be spawned), never silently ignored or downgraded to realtime
 * defaults.
 */
class GoalServiceUnsupportedParamsTest {
    @Test
    void rejectsScriptMode() {
        withService(service -> {
            var failure = assertThrows(IllegalArgumentException.class, () -> service.run(
                    "goal", GoalMode.SCRIPT, null, 10, 5_000, false, null, false,
                    GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(), null, false,
                    "caller", AuthorizationPolicy.observeOnly()));
            assertTrue(failure.getMessage().contains("mode=script"), failure.getMessage());
        });
    }

    @Test
    void rejectsDryRun() {
        withService(service -> {
            var failure = assertThrows(IllegalArgumentException.class, () -> service.run(
                    "goal", GoalMode.REALTIME, null, 10, 5_000, true, null, false,
                    GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(), null, false,
                    "caller", AuthorizationPolicy.observeOnly()));
            assertTrue(failure.getMessage().contains("dryRun"), failure.getMessage());
        });
    }

    @Test
    void rejectsCustomPlan() {
        var step = GoalStep.observe("obs", "minecraft.player.state.read", Map.of());
        var segment = new GoalSegment("seg-1", "test segment", List.of(step), List.of());
        var plan = new GoalPlan("test-plan", "test goal", List.of(segment), Map.of());
        withService(service -> {
            var failure = assertThrows(IllegalArgumentException.class, () -> service.run(
                    "goal", GoalMode.REALTIME, null, 10, 5_000, false, plan, false,
                    GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(), null, false,
                    "caller", AuthorizationPolicy.observeOnly()));
            assertTrue(failure.getMessage().contains("plan"), failure.getMessage());
        });
    }

    @Test
    void rejectsTaskId() {
        withService(service -> {
            var failure = assertThrows(IllegalArgumentException.class, () -> service.run(
                    "goal", GoalMode.REALTIME, "survival.collect-wood", 10, 5_000, false, null, false,
                    GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(), null, false,
                    "caller", AuthorizationPolicy.observeOnly()));
            assertTrue(failure.getMessage().contains("taskId"), failure.getMessage());
        });
    }

    @Test
    void rejectsWorldSeed() {
        withService(service -> {
            var failure = assertThrows(IllegalArgumentException.class, () -> service.run(
                    "goal", GoalMode.REALTIME, null, 10, 5_000, false, null, false,
                    GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults(), "12345", false,
                    "caller", AuthorizationPolicy.observeOnly()));
            assertTrue(failure.getMessage().contains("worldSeed"), failure.getMessage());
        });
    }

    private static void withService(Consumer<GoalService> use) {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            use.accept(new GoalService(runtime));
        }
    }
}
