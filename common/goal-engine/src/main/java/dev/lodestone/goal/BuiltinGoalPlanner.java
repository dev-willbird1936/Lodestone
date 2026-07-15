// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuiltinGoalPlanner implements GoalPlanner {
    @Override
    public PlanResult plan(GoalSpec spec) {
        if (spec.customPlan() != null) return PlanResult.supported(spec.customPlan());
        var task = spec.taskId() == null ? infer(spec.goal()) : GoalTaskCatalog.find(spec.taskId()).orElse(null);
        if (task == null) return PlanResult.unsupported(
                "no bounded plan matches goal; provide taskId or a declarative plan");
        return PlanResult.supported(planFor(task.id(), spec.goal()));
    }

    private static GoalTaskCatalog.TaskDefinition infer(String goal) {
        var normalized = goal.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("wooden axe") || normalized.contains("mine an entire tree"))
            return GoalTaskCatalog.find("survival.wooden-axe-mine-tree").orElseThrow();
        if (normalized.contains("pillar") && (normalized.contains("place") || normalized.contains("build")))
            return GoalTaskCatalog.find("creative.place-pillar").orElseThrow();
        if (normalized.contains("clear") && normalized.contains("pillar"))
            return GoalTaskCatalog.find("creative.clear-pillar").orElseThrow();
        if (normalized.contains("attack") || normalized.contains("kill"))
            return GoalTaskCatalog.find("combat.attack-nearest").orElseThrow();
        if (normalized.contains("set day") || normalized.contains("command"))
            return GoalTaskCatalog.find("commands.set-day").orElseThrow();
        if (normalized.contains("waypoint") || normalized.contains("go to") || normalized.contains("navigate"))
            return GoalTaskCatalog.find("navigation.reach-waypoint").orElseThrow();
        if (normalized.contains("scan") || normalized.contains("inspect"))
            return GoalTaskCatalog.find("tools.inspect-nearby").orElseThrow();
        return null;
    }

    private static GoalPlan planFor(String taskId, String goal) {
        return switch (taskId) {
            case "survival.wooden-axe-mine-tree" -> woodenAxePlan(goal);
            case "creative.place-pillar" -> blockPlan("creative.place-pillar", goal, "minecraft:stone", "pillar must be stone");
            case "creative.clear-pillar" -> blockPlan("creative.clear-pillar", goal, "minecraft:air", "pillar must be air");
            case "navigation.reach-waypoint" -> navigationPlan(goal);
            case "combat.attack-nearest" -> combatPlan(goal);
            case "commands.set-day" -> commandPlan(goal);
            case "tools.inspect-nearby" -> inspectPlan(goal);
            case "survival.collect-wood" -> collectWoodPlan(goal);
            case "failure.stale-ui-snapshot" -> staleUiPlan(goal);
            default -> throw new IllegalArgumentException("no built-in plan for task: " + taskId);
        };
    }

    private static GoalPlan woodenAxePlan(String goal) {
        var open = GoalStep.invoke("open-singleplayer", "lodestone.ui.navigate", "1.0",
                Map.of("target", "singleplayer"), false);
        var createScreen = GoalStep.invoke("open-create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_new_world"), false);
        var createWorld = GoalStep.invoke("create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_world"), false);
        var workflow = GoalStep.invoke("survival-workflow", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                Map.of(), true,
                new GoalAssertion("steps.survival-workflow.survival", "equals", true),
                new GoalAssertion("steps.survival-workflow.freshWorld", "equals", true),
                new GoalAssertion("steps.survival-workflow.handMinedLogs", "gte", 3),
                new GoalAssertion("steps.survival-workflow.planksCrafted", "gte", 12),
                new GoalAssertion("steps.survival-workflow.sticksCrafted", "gte", 4),
                new GoalAssertion("steps.survival-workflow.craftingTableCrafted", "equals", true),
                new GoalAssertion("steps.survival-workflow.woodenAxeCrafted", "equals", true),
                new GoalAssertion("steps.survival-workflow.woodenAxeEquipped", "equals", true),
                new GoalAssertion("steps.survival-workflow.targetTreeInitialLogs", "gte", 3),
                new GoalAssertion("steps.survival-workflow.targetTreeRemainingLogs", "equals", 0),
                new GoalAssertion("steps.survival-workflow.fullTreeMined", "equals", true),
                new GoalAssertion("steps.survival-workflow.allTargetLogsMinedWithWoodenAxe", "equals", true),
                new GoalAssertion("steps.survival-workflow.commandsUsed", "equals", false),
                new GoalAssertion("steps.survival-workflow.directMutationUsed", "equals", false));
        return new GoalPlan("survival.wooden-axe-mine-tree", goal, List.of(
                new GoalSegment("open-singleplayer", "Open Minecraft singleplayer through guarded UI input.",
                        List.of(open), List.of()),
                new GoalSegment("open-create-world", "Open the create-world screen through guarded UI input.",
                        List.of(createScreen), List.of()),
                new GoalSegment("create-fresh-world", "Create a fresh default survival world through guarded UI input.",
                        List.of(createWorld), List.of()),
                new GoalSegment("survival-gameplay", "Use normal look, movement, attack, inventory, and crafting-table input until the full predicate is proven.",
                        List.of(workflow), List.of())),
                Map.of("taskId", "survival.wooden-axe-mine-tree", "craftingRequired", true,
                        "adaptiveTreeTraversalRequired", true, "authenticPlayerInputRequired", true,
                        "completionPredicateReady", true));
    }

    private static GoalPlan blockPlan(String id, String goal, String block, String description) {
        var changes = new ArrayList<Map<String, Object>>();
        for (var y = 64; y < 67; y++) changes.add(Map.of("x", 0, "y", y, "z", 0, "block", block));
        var write = GoalStep.invoke("write", "minecraft.world.blocks.write", "1.0",
                Map.of("dimension", "minecraft:overworld", "changes", changes), false);
        var read = GoalStep.observe("read", "minecraft.world.blocks.read", Map.of(
                "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0,
                "sizeX", 1, "sizeY", 3, "sizeZ", 1));
        var verify = new GoalStep("verify", GoalStepKind.ASSERT, null, "1.0", Map.of(),
                List.of(new GoalAssertion("steps.read.count", "equals", 3),
                        new GoalAssertion("steps.read.blocks", "all_contains", block)), false);
        return new GoalPlan(id, goal, List.of(
                new GoalSegment("mutate", "Apply one bounded block batch.", List.of(write), List.of()),
                new GoalSegment("verify", description, List.of(read, verify), List.of())),
                Map.of("taskId", id, "successRequiresFreshReadback", true, "completionPredicateReady", true));
    }

    private static GoalPlan navigationPlan(String goal) {
        var before = GoalStep.observe("before", "minecraft.player.state.read", Map.of());
        var move = GoalStep.invoke("move", "minecraft.player.move", "2.0",
                Map.of("forward", 1.0, "strafe", 0.0, "durationMs", 1000, "sprint", true), true);
        var after = GoalStep.observe("after", "minecraft.player.state.read", Map.of());
        var assertAfter = new GoalStep("verify", GoalStepKind.ASSERT, null, "1.0", Map.of(),
                List.of(new GoalAssertion("steps.after.position", "exists", null)), false);
        return new GoalPlan("navigation.reach-waypoint", goal, List.of(
                new GoalSegment("move", "Move under a finite lease; realtime mode observes immediately after.", List.of(before, move, after, assertAfter), List.of())),
                Map.of("taskId", "navigation.reach-waypoint", "requiresWaypointFixture", true,
                        "completionPredicateReady", false));
    }

    private static GoalPlan combatPlan(String goal) {
        var nearby = GoalStep.observe("nearby", "minecraft.entity.nearby.read", Map.of("radius", 16, "limit", 16));
        var attack = GoalStep.invoke("attack", "minecraft.player.interact", "1.0", Map.of("action", "attack"), true);
        var after = GoalStep.observe("after", "minecraft.player.state.read", Map.of());
        var verify = new GoalStep("verify", GoalStepKind.ASSERT, null, "1.0", Map.of(),
                List.of(new GoalAssertion("steps.after", "exists", null)), false);
        return new GoalPlan("combat.attack-nearest", goal, List.of(
                new GoalSegment("engage", "Read nearby entities, queue one attack, and observe fresh state.", List.of(nearby, attack, after, verify), List.of())),
                Map.of("taskId", "combat.attack-nearest", "killConfirmation", "native entity readback required",
                        "completionPredicateReady", false));
    }

    private static GoalPlan commandPlan(String goal) {
        var execute = GoalStep.invoke("execute", "minecraft.command.execute", "1.0",
                Map.of("command", "time set day"), true);
        var info = GoalStep.observe("info", "minecraft.server.info.read", Map.of());
        var verify = new GoalStep("verify", GoalStepKind.ASSERT, null, "1.0", Map.of(),
                List.of(new GoalAssertion("steps.info.dayTime", "equals", 0)), false);
        return new GoalPlan("commands.set-day", goal, List.of(
                new GoalSegment("command", "Execute command, then read back structured day time.", List.of(execute, info, verify), List.of())),
                Map.of("taskId", "commands.set-day", "readbackRequired", true, "completionPredicateReady", true));
    }

    private static GoalPlan inspectPlan(String goal) {
        var region = GoalStep.observe("region", "minecraft.world.region.scan", Map.of(
                "dimension", "minecraft:overworld", "x", 0, "y", 60, "z", 0,
                "sizeX", 16, "sizeY", 16, "sizeZ", 16));
        var nearby = GoalStep.observe("nearby", "minecraft.entity.nearby.read", Map.of("radius", 32, "limit", 64));
        var verify = new GoalStep("verify", GoalStepKind.ASSERT, null, "1.0", Map.of(), List.of(
                new GoalAssertion("steps.region", "exists", null), new GoalAssertion("steps.nearby", "exists", null)), false);
        return new GoalPlan("tools.inspect-nearby", goal, List.of(
                new GoalSegment("inspect", "Perform two bounded observations; no mutations.", List.of(region, nearby, verify), List.of())),
                Map.of("taskId", "tools.inspect-nearby", "mutatesWorld", false, "completionPredicateReady", true));
    }

    private static GoalPlan collectWoodPlan(String goal) {
        var state = GoalStep.observe("state", "minecraft.player.state.read", Map.of());
        var scan = GoalStep.observe("scan", "minecraft.world.region.scan", Map.of(
                "dimension", "minecraft:overworld", "x", 0, "y", 60, "z", 0,
                "sizeX", 32, "sizeY", 32, "sizeZ", 32));
        var move = GoalStep.invoke("approach", "minecraft.player.move", "2.0",
                Map.of("forward", 1.0, "durationMs", 500), true);
        var attack = GoalStep.invoke("mine", "minecraft.player.interact", "1.0", Map.of("action", "attack"), true);
        return new GoalPlan("survival.collect-wood", goal, List.of(
                new GoalSegment("find", "Read player state and bounded nearby blocks before acting.", List.of(state, scan), List.of()),
                new GoalSegment("act", "Approach and queue bounded mining input; a live agent must adapt aim and repeat.", List.of(move, attack), List.of())),
                Map.of("taskId", "survival.collect-wood", "adaptiveLoopRequired", true, "completionPredicateReady", false));
    }

    private static GoalPlan staleUiPlan(String goal) {
        var state = GoalStep.observe("state", "minecraft.ui.state.read", Map.of());
        var click = GoalStep.invoke("click", "minecraft.ui.click", "2.0", Map.of(
                "screenToken", "stale", "snapshotRevision", "0".repeat(64), "label", "Back to Game"), false);
        return new GoalPlan("failure.stale-ui-snapshot", goal, List.of(
                new GoalSegment("guard", "Attempt a deliberately stale guarded click.", List.of(state, click), List.of())),
                Map.of("taskId", "failure.stale-ui-snapshot", "expectedStatus", "FAILED"));
    }
}
