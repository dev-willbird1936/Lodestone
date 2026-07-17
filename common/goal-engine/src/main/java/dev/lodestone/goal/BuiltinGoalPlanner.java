// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class BuiltinGoalPlanner implements GoalPlanner {
    @Override
    public PlanResult plan(GoalSpec spec) {
        if (spec.customPlan() != null) return PlanResult.supported(spec.customPlan());
        var task = spec.taskId() == null ? infer(spec.goal()) : GoalTaskCatalog.find(spec.taskId()).orElse(null);
        if (task == null) return PlanResult.unsupported(
                "no bounded plan matches goal; provide taskId or a declarative plan");
        return PlanResult.supported(planFor(task.id(), spec));
    }

    private static GoalTaskCatalog.TaskDefinition infer(String goal) {
        var normalized = goal.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("wool") && normalized.contains("zombie") && normalized.contains("diamond sword"))
            return GoalTaskCatalog.find("creative.wool-tree-zombie-defense").orElseThrow();
        if (normalized.contains("nether") || normalized.contains("nether portal"))
            return GoalTaskCatalog.find("survival.reach-nether").orElseThrow();
        if (normalized.contains("wooden axe") || normalized.contains("mine an entire tree"))
            return GoalTaskCatalog.find("survival.wooden-axe-mine-tree").orElseThrow();
        if ((normalized.contains("collect") || normalized.contains("gather") || normalized.contains("chop")
                || normalized.contains("get"))
                && (normalized.contains("wood") || normalized.contains("log") || normalized.contains("tree")))
            return GoalTaskCatalog.find("survival.collect-wood").orElseThrow();
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

    private static GoalPlan planFor(String taskId, GoalSpec spec) {
        var goal = spec.goal();
        return switch (taskId) {
            case "creative.wool-tree-zombie-defense" -> woolTreeZombieDefensePlan(spec);
            case "survival.wooden-axe-mine-tree" -> woodenAxePlan(spec);
            case "survival.reach-nether" -> netherPlan(spec);
            case "creative.place-pillar" -> blockPlan("creative.place-pillar", goal, "minecraft:stone", "pillar must be stone");
            case "creative.clear-pillar" -> blockPlan("creative.clear-pillar", goal, "minecraft:air", "pillar must be air");
            case "navigation.reach-waypoint" -> navigationPlan(goal);
            case "navigation.safe-waypoint" -> safeNavigationPlan(spec);
            case "combat.attack-nearest" -> combatPlan(goal);
            case "commands.set-day" -> commandPlan(goal);
            case "tools.inspect-nearby" -> inspectPlan(goal);
            case "survival.collect-wood" -> collectWoodPlan(spec);
            case "failure.stale-ui-snapshot" -> staleUiPlan(goal);
            default -> throw new IllegalArgumentException("no built-in plan for task: " + taskId);
        };
    }

    private static GoalPlan woodenAxePlan(GoalSpec spec) {
        var goal = spec.goal();
        var open = GoalStep.invoke("open-singleplayer", "lodestone.ui.navigate", "1.0",
                Map.of("target", "singleplayer"), false);
        var createScreen = GoalStep.invoke("open-create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_new_world"), false);
        var createWorldSetup = new ArrayList<GoalSegment>();
        if (spec.worldSeed() != null) {
            createWorldSetup.add(new GoalSegment("open-world-options",
                    "Open the normal world-generation options tab.",
                    List.of(GoalStep.invoke("open-world-options", "lodestone.ui.navigate", "1.0",
                            Map.of("target", "world_tab"), false)), List.of()));
            createWorldSetup.add(new GoalSegment("focus-world-seed",
                    "Focus the normal world seed text field.",
                    List.of(GoalStep.invoke("focus-world-seed", "lodestone.ui.navigate", "1.0",
                            Map.of("target", "world_seed"), false)), List.of()));
            createWorldSetup.add(new GoalSegment("insert-world-seed",
                    "Enter the requested Java seed through normal UI text input.",
                    List.of(GoalStep.invoke("insert-world-seed", "minecraft.ui.text.insert", "1.0",
                            Map.of("text", spec.worldSeed()), false)), List.of()));
        }
        var createWorld = GoalStep.invoke("create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_world"), false);
        var finalAssertions = new GoalAssertion[]{
                new GoalAssertion("survival", "equals", true),
                new GoalAssertion("freshWorld", "equals", true),
                new GoalAssertion("handMinedLogs", "gte", 3),
                new GoalAssertion("planksCrafted", "gte", 12),
                new GoalAssertion("sticksCrafted", "gte", 4),
                new GoalAssertion("craftingTableCrafted", "equals", true),
                new GoalAssertion("woodenAxeCrafted", "equals", true),
                new GoalAssertion("woodenAxeEquipped", "equals", true),
                new GoalAssertion("targetTreeInitialLogs", "gte", 3),
                new GoalAssertion("targetTreeRemainingLogs", "equals", 0),
                new GoalAssertion("fullTreeMined", "equals", true),
                new GoalAssertion("allTargetLogsMinedWithWoodenAxe", "equals", true),
                new GoalAssertion("playerAlive", "equals", true),
                new GoalAssertion("commandsUsed", "equals", false),
                new GoalAssertion("directMutationUsed", "equals", false)};
        var workflowSteps = new ArrayList<GoalStep>();
        if (spec.intelligence().checkpointedWorkflowEnabled()) {
            var gatherInput = new LinkedHashMap<>(workflowInput(spec));
            gatherInput.put("checkpoint", "resource-gather");
            workflowSteps.add(GoalStep.invoke("gather-resource", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                    gatherInput, true,
                    new GoalAssertion("steps.gather-resource.checkpoint", "equals", "resource-gather"),
                    new GoalAssertion("steps.gather-resource.checkpointComplete", "equals", true),
                    new GoalAssertion("steps.gather-resource.handMinedLogs", "gte", 3),
                    new GoalAssertion("steps.gather-resource.playerAlive", "equals", true)));
            var craftInput = new LinkedHashMap<>(workflowInput(spec));
            craftInput.put("checkpoint", "craft-axe");
            craftInput.put("continuationToken", "${steps.gather-resource.continuationToken}");
            workflowSteps.add(GoalStep.invokeWithPreconditions("craft-axe", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                    craftInput, true, List.of(
                            new GoalAssertion("steps.gather-resource.checkpointComplete", "equals", true)),
                    new GoalAssertion("steps.craft-axe.checkpoint", "equals", "craft-axe"),
                    new GoalAssertion("steps.craft-axe.checkpointComplete", "equals", true),
                    new GoalAssertion("steps.craft-axe.woodenAxeCrafted", "equals", true),
                    new GoalAssertion("steps.craft-axe.woodenAxeEquipped", "equals", true),
                    new GoalAssertion("steps.craft-axe.playerAlive", "equals", true)));
            var mineInput = new LinkedHashMap<>(workflowInput(spec));
            mineInput.put("checkpoint", "complete");
            mineInput.put("continuationToken", "${steps.craft-axe.continuationToken}");
            var mineAssertions = new ArrayList<GoalAssertion>();
            for (var assertion : finalAssertions) {
                mineAssertions.add(new GoalAssertion("steps.mine-target." + assertion.path(),
                        assertion.operator(), assertion.expected()));
            }
            workflowSteps.add(new GoalStep("mine-target", GoalStepKind.INVOKE,
                    "minecraft.goal.survival.wooden-axe-tree", "1.0", mineInput, mineAssertions,
                    List.of(new GoalAssertion("steps.craft-axe.woodenAxeEquipped", "equals", true)), true));
        } else {
            var workflow = GoalStep.invoke("survival-workflow", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                    workflowInput(spec), true,
                    java.util.Arrays.stream(finalAssertions)
                            .map(assertion -> new GoalAssertion("steps.survival-workflow." + assertion.path(),
                                    assertion.operator(), assertion.expected()))
                            .toArray(GoalAssertion[]::new));
            workflowSteps.add(workflow);
        }
        var segments = new ArrayList<GoalSegment>();
        segments.add(new GoalSegment("open-singleplayer", "Open Minecraft singleplayer through guarded UI input.",
                List.of(open), List.of()));
        segments.add(new GoalSegment("open-create-world", "Open the create-world screen through guarded UI input.",
                List.of(createScreen), List.of()));
        segments.addAll(createWorldSetup);
        segments.add(new GoalSegment("create-fresh-world", "Create a fresh default survival world through guarded UI input.",
                List.of(createWorld), List.of()));
        segments.add(new GoalSegment("survival-gameplay", "Use normal look, movement, attack, inventory, and crafting-table input until the full predicate is proven.",
                workflowSteps, List.of()));
        return new GoalPlan("survival.wooden-axe-mine-tree", goal, List.copyOf(segments),
                Map.of("taskId", "survival.wooden-axe-mine-tree", "gameMode", "survival",
                        "craftingRequired", true,
                        "adaptiveTreeTraversalRequired", true, "authenticPlayerInputRequired", true,
                        "randomFreshWorldRequired", spec.worldSeed() == null,
                        "completionPredicateReady", true));
    }

    private static GoalPlan netherPlan(GoalSpec spec) {
        var goal = spec.goal();
        var open = GoalStep.invoke("open-singleplayer", "lodestone.ui.navigate", "1.0",
                Map.of("target", "singleplayer"), false);
        var createScreen = GoalStep.invoke("open-create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_new_world"), false);
        var createWorldSetup = new ArrayList<GoalSegment>();
        if (spec.worldSeed() != null) {
            createWorldSetup.add(new GoalSegment("open-world-options",
                    "Open the normal world-generation options tab.",
                    List.of(GoalStep.invoke("open-world-options", "lodestone.ui.navigate", "1.0",
                            Map.of("target", "world_tab"), false)), List.of()));
            createWorldSetup.add(new GoalSegment("focus-world-seed",
                    "Focus the normal world seed text field.",
                    List.of(GoalStep.invoke("focus-world-seed", "lodestone.ui.navigate", "1.0",
                            Map.of("target", "world_seed"), false)), List.of()));
            createWorldSetup.add(new GoalSegment("insert-world-seed",
                    "Enter the requested Java seed through normal UI text input.",
                    List.of(GoalStep.invoke("insert-world-seed", "minecraft.ui.text.insert", "1.0",
                            Map.of("text", spec.worldSeed()), false)), List.of()));
        }
        var createWorld = GoalStep.invoke("create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_world"), false);
        var finalAssertions = List.of(
                new GoalAssertion("freshWorld", "equals", true),
                new GoalAssertion("survival", "equals", true),
                new GoalAssertion("initialDimension", "equals", "minecraft:overworld"),
                new GoalAssertion("manualPortalBuilt", "equals", true),
                new GoalAssertion("portalFrameBlocksPlaced", "equals", 10),
                new GoalAssertion("portalLit", "equals", true),
                new GoalAssertion("enteredPortal", "equals", true),
                new GoalAssertion("reachedNether", "equals", true),
                new GoalAssertion("finalDimension", "equals", "minecraft:the_nether"),
                new GoalAssertion("playerAlive", "equals", true),
                new GoalAssertion("teleportedToBuildSite", "equals", false),
                new GoalAssertion("setupCommandsUsed", "equals", false),
                new GoalAssertion("commandFeedbackSuppressed", "equals", true),
                new GoalAssertion("directMutationUsed", "equals", false));
        var workflowSteps = new ArrayList<GoalStep>();
        if (spec.intelligence().checkpointedWorkflowEnabled()) {
            var starterInput = new LinkedHashMap<>(workflowInput(spec));
            starterInput.put("checkpoint", "starter-tools");
            workflowSteps.add(GoalStep.invoke("gather-starter-tools", "minecraft.goal.survival.reach-nether", "1.0",
                    starterInput, true,
                    new GoalAssertion("steps.gather-starter-tools.checkpoint", "equals", "starter-tools"),
                    new GoalAssertion("steps.gather-starter-tools.checkpointComplete", "equals", true),
                    new GoalAssertion("steps.gather-starter-tools.playerAlive", "equals", true)));
            var portalToolsInput = new LinkedHashMap<>(workflowInput(spec));
            portalToolsInput.put("checkpoint", "portal-tools");
            portalToolsInput.put("continuationToken", "${steps.gather-starter-tools.continuationToken}");
            workflowSteps.add(GoalStep.invokeWithPreconditions("craft-portal-tools", "minecraft.goal.survival.reach-nether", "1.0",
                    portalToolsInput, true, List.of(
                            new GoalAssertion("steps.gather-starter-tools.checkpointComplete", "equals", true)),
                    new GoalAssertion("steps.craft-portal-tools.checkpoint", "equals", "portal-tools"),
                    new GoalAssertion("steps.craft-portal-tools.checkpointComplete", "equals", true),
                    new GoalAssertion("steps.craft-portal-tools.playerAlive", "equals", true)));
            var finalInput = new LinkedHashMap<>(workflowInput(spec));
            finalInput.put("checkpoint", "complete");
            finalInput.put("continuationToken", "${steps.craft-portal-tools.continuationToken}");
            var finalStepAssertions = new ArrayList<GoalAssertion>();
            finalStepAssertions.add(new GoalAssertion("steps.enter-nether.checkpoint", "equals", "complete"));
            finalStepAssertions.add(new GoalAssertion("steps.enter-nether.checkpointComplete", "equals", true));
            for (var assertion : finalAssertions) {
                finalStepAssertions.add(new GoalAssertion("steps.enter-nether." + assertion.path(),
                        assertion.operator(), assertion.expected()));
            }
            workflowSteps.add(new GoalStep("enter-nether", GoalStepKind.INVOKE,
                    "minecraft.goal.survival.reach-nether", "1.0", finalInput, finalStepAssertions,
                    List.of(new GoalAssertion("steps.craft-portal-tools.checkpointComplete", "equals", true)), true));
        } else {
            var workflow = GoalStep.invoke("nether-workflow", "minecraft.goal.survival.reach-nether", "1.0",
                    workflowInput(spec), true,
                    finalAssertions.stream()
                            .map(assertion -> new GoalAssertion("steps.nether-workflow." + assertion.path(),
                                    assertion.operator(), assertion.expected()))
                            .toArray(GoalAssertion[]::new));
            workflowSteps.add(workflow);
        }
        var assertions = new ArrayList<GoalAssertion>();
        if (spec.suppressInGameMessages()) {
            var stepId = spec.intelligence().checkpointedWorkflowEnabled() ? "enter-nether" : "nether-workflow";
            assertions.add(new GoalAssertion("steps." + stepId + ".suppressInGameMessages", "equals", true));
            assertions.add(new GoalAssertion("steps." + stepId + ".inGameMessagesEmitted", "equals", 0));
        }
        if (!assertions.isEmpty()) {
            var last = workflowSteps.remove(workflowSteps.size() - 1);
            workflowSteps.add(new GoalStep(last.id(), last.kind(), last.capability(), last.capabilityVersion(),
                    last.input(), concat(last.assertions(), assertions), last.preconditions(), last.observeAfter()));
        }
        var segments = new ArrayList<GoalSegment>();
        segments.add(new GoalSegment("open-singleplayer", "Open Minecraft singleplayer through guarded UI input.",
                List.of(open), List.of()));
        segments.add(new GoalSegment("open-create-world", "Open the create-world screen through guarded UI input.",
                List.of(createScreen), List.of()));
        segments.addAll(createWorldSetup);
        segments.add(new GoalSegment("create-fresh-world", "Create a fresh default survival world through guarded UI input.",
                List.of(createWorld), List.of()));
        segments.add(new GoalSegment("nether-gameplay", "Use normal portal placement, ignition, movement, and dimension readback until the Nether predicate is proven.",
                workflowSteps, List.of()));
        return new GoalPlan("survival.reach-nether", goal, List.copyOf(segments),
                Map.of("taskId", "survival.reach-nether", "gameMode", "survival",
                        "realtimePreferred", true,
                        "randomFreshWorldRequired", spec.worldSeed() == null, "naturalPortalChestOptional", true,
                        "manualPortalInputRequired", true,
                        "completionPredicateReady", true));
    }

    private static List<GoalAssertion> concat(List<GoalAssertion> first, List<GoalAssertion> second) {
        var output = new ArrayList<GoalAssertion>(first);
        output.addAll(second);
        return output;
    }

    private static GoalPlan woolTreeZombieDefensePlan(GoalSpec spec) {
        var open = GoalStep.invoke("open-singleplayer", "lodestone.ui.navigate", "1.0",
                Map.of("target", "singleplayer"), false);
        var createScreen = GoalStep.invoke("open-create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_new_world"), false);
        var createWorld = GoalStep.invoke("create-world", "lodestone.ui.navigate", "1.0",
                Map.of("target", "create_world"), false);
        var assertions = new ArrayList<GoalAssertion>(List.of(
                new GoalAssertion("steps.wool-tree-defense.freshWorld", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.creativeSetupMode", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.manualTreeBuilt", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.manualPlacementInputOnly", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.trunkLogsPlaced", "gte", 3),
                new GoalAssertion("steps.wool-tree-defense.woolLeavesPlaced", "gte", 9),
                new GoalAssertion("steps.wool-tree-defense.zombieSetupComplete", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.teleportedAway", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.diamondSwordEquipped", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.survivalMode", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.zombieObserved", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.reactiveDefenseEvaluated", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.threatDetections", "gte", 1),
                new GoalAssertion("steps.wool-tree-defense.defensiveResponses", "gte", 1),
                new GoalAssertion("steps.wool-tree-defense.defensiveAttacks", "gte", 1),
                new GoalAssertion("steps.wool-tree-defense.treeRemainingBlocks", "equals", 0),
                new GoalAssertion("steps.wool-tree-defense.fullTreeMined", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.playerAlive", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.setupCommandsUsed", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.commandFeedbackSuppressed", "equals", true),
                new GoalAssertion("steps.wool-tree-defense.unconditionalKillRoutine", "equals", false),
                new GoalAssertion("steps.wool-tree-defense.directMutationUsed", "equals", false)));
        if (spec.suppressInGameMessages()) {
            assertions.add(new GoalAssertion("steps.wool-tree-defense.suppressInGameMessages", "equals", true));
            assertions.add(new GoalAssertion("steps.wool-tree-defense.inGameMessagesEmitted", "equals", 0));
        }
        var workflow = new GoalStep("wool-tree-defense", GoalStepKind.INVOKE,
                "minecraft.goal.creative.wool-tree-zombie-defense", "1.0",
                workflowInput(spec, true), assertions, true);
        return new GoalPlan("creative.wool-tree-zombie-defense", spec.goal(), List.of(
                new GoalSegment("open-singleplayer", "Open Minecraft singleplayer through guarded UI input.",
                        List.of(open), List.of()),
                new GoalSegment("open-create-world", "Open the create-world screen through guarded UI input.",
                        List.of(createScreen), List.of()),
                new GoalSegment("create-fresh-world", "Create a fresh world through guarded UI input.",
                        List.of(createWorld), List.of()),
                new GoalSegment("creative-setup-and-survival-defense",
                        "Build by normal placement input, perform explicit silent command setup, then mine with live reactive defense.",
                        List.of(workflow), List.of())),
                Map.of("taskId", "creative.wool-tree-zombie-defense",
                        "manualTreeInputRequired", true, "reactiveDefenseRequired", true,
                        "setupCommandsSeparated", true, "completionPredicateReady", true));
    }

    private static Map<String, Object> workflowInput(GoalSpec spec) {
        return workflowInput(spec, false);
    }

    private static Map<String, Object> workflowInput(GoalSpec spec, boolean allowCommands) {
        return Map.of("suppressInGameMessages", spec.suppressInGameMessages(),
                "intelligence", spec.intelligence().id(), "safety", spec.safety().id(),
                "observation", spec.controls().observation(),
                "combatPolicy", spec.controls().combatPolicy(),
                "allowBlockBreaking", spec.controls().allowBlockBreaking(),
                "allowBlockPlacing", spec.controls().allowBlockPlacing(),
                "allowCommands", allowCommands);
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

    private static GoalPlan safeNavigationPlan(GoalSpec spec) {
        var goal = spec.goal();
        var input = new LinkedHashMap<String, Object>(workflowInput(spec));
        input.remove("suppressInGameMessages");
        input.put("targetX", coordinate(goal, "x"));
        input.put("targetY", coordinate(goal, "y"));
        input.put("targetZ", coordinate(goal, "z"));
        var before = GoalStep.observe("before", "minecraft.player.state.read", Map.of());
        var navigate = GoalStep.invoke("safe-navigation", "minecraft.goal.navigation.safe-waypoint", "1.0",
                input, true,
                new GoalAssertion("steps.safe-navigation.reachedTarget", "equals", true),
                new GoalAssertion("steps.safe-navigation.playerAlive", "equals", true));
        var after = GoalStep.observe("after", "minecraft.player.state.read", Map.of());
        return new GoalPlan("navigation.safe-waypoint", goal, List.of(
                new GoalSegment("safe-navigation", "Read player state, plan against loaded chunks, and reach the requested waypoint with ordinary movement input.",
                        List.of(before, navigate, after), List.of())),
                Map.of("taskId", "navigation.safe-waypoint", "targetEncodedInGoal", true,
                        "loadedChunkPlanner", true, "completionPredicateReady", true));
    }

    private static int coordinate(String goal, String axis) {
        var matcher = Pattern.compile("(?i)(?:target\\s*)?" + axis + "\\s*[:=]\\s*(-?\\d+)").matcher(goal);
        if (!matcher.find()) {
            throw new IllegalArgumentException("navigation.safe-waypoint goal must encode " + axis + "=<integer>");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static GoalPlan combatPlan(String goal) {
        var attack = GoalStep.invoke("attack-nearest", "minecraft.goal.combat.attack-nearest", "1.0",
                Map.of("intelligence", "${intelligence}", "safety", "${safety}",
                        "observation", "${observation}", "combatPolicy", "${combatPolicy}",
                        "allowBlockBreaking", "${allowBlockBreaking}",
                        "allowBlockPlacing", "${allowBlockPlacing}",
                        "allowCommands", false), true,
                new GoalAssertion("steps.attack-nearest.targetObserved", "equals", true),
                new GoalAssertion("steps.attack-nearest.targetKilled", "equals", true),
                new GoalAssertion("steps.attack-nearest.playerAlive", "equals", true));
        return new GoalPlan("combat.attack-nearest", goal, List.of(
                new GoalSegment("engage", "Observe a nearby hostile, path with ordinary movement, attack with a held input, and prove target death.", List.of(attack), List.of())),
                Map.of("taskId", "combat.attack-nearest", "killConfirmation", "native entity death readback",
                        "ordinaryInputOnly", true, "completionPredicateReady", true));
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

    private static GoalPlan collectWoodPlan(GoalSpec spec) {
        var goal = spec.goal();
        if (spec.intelligence() != GoalIntelligence.RAW_V1) {
            var finalAssertions = List.of(
                    new GoalAssertion("survival", "equals", true),
                    new GoalAssertion("handMinedLogs", "gte", 3),
                    new GoalAssertion("planksCrafted", "gte", 12),
                    new GoalAssertion("sticksCrafted", "gte", 4),
                    new GoalAssertion("craftingTableCrafted", "equals", true),
                    new GoalAssertion("woodenAxeCrafted", "equals", true),
                    new GoalAssertion("woodenAxeEquipped", "equals", true),
                    new GoalAssertion("targetTreeRemainingLogs", "equals", 0),
                    new GoalAssertion("playerAlive", "equals", true),
                    new GoalAssertion("commandsUsed", "equals", false),
                    new GoalAssertion("directMutationUsed", "equals", false));
            var workflowSteps = new ArrayList<GoalStep>();
            if (spec.intelligence().checkpointedWorkflowEnabled()) {
                var gatherInput = new LinkedHashMap<>(workflowInput(spec));
                gatherInput.put("checkpoint", "resource-gather");
                workflowSteps.add(GoalStep.invoke("gather-resource", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                        gatherInput, true,
                        new GoalAssertion("steps.gather-resource.checkpoint", "equals", "resource-gather"),
                        new GoalAssertion("steps.gather-resource.checkpointComplete", "equals", true),
                        new GoalAssertion("steps.gather-resource.handMinedLogs", "gte", 3),
                        new GoalAssertion("steps.gather-resource.playerAlive", "equals", true)));
                var craftInput = new LinkedHashMap<>(workflowInput(spec));
                craftInput.put("checkpoint", "craft-axe");
                craftInput.put("continuationToken", "${steps.gather-resource.continuationToken}");
                workflowSteps.add(GoalStep.invokeWithPreconditions("craft-axe", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                        craftInput, true, List.of(
                                new GoalAssertion("steps.gather-resource.checkpointComplete", "equals", true)),
                        new GoalAssertion("steps.craft-axe.checkpoint", "equals", "craft-axe"),
                        new GoalAssertion("steps.craft-axe.checkpointComplete", "equals", true),
                        new GoalAssertion("steps.craft-axe.woodenAxeCrafted", "equals", true),
                        new GoalAssertion("steps.craft-axe.woodenAxeEquipped", "equals", true),
                        new GoalAssertion("steps.craft-axe.playerAlive", "equals", true)));
                var finalInput = new LinkedHashMap<>(workflowInput(spec));
                finalInput.put("checkpoint", "complete");
                finalInput.put("continuationToken", "${steps.craft-axe.continuationToken}");
                var finalStepAssertions = new ArrayList<GoalAssertion>();
                finalStepAssertions.add(new GoalAssertion("steps.collect-target.checkpoint", "equals", "complete"));
                finalStepAssertions.add(new GoalAssertion("steps.collect-target.checkpointComplete", "equals", true));
                for (var assertion : finalAssertions) {
                    finalStepAssertions.add(new GoalAssertion("steps.collect-target." + assertion.path(),
                            assertion.operator(), assertion.expected()));
                }
                workflowSteps.add(new GoalStep("collect-target", GoalStepKind.INVOKE,
                        "minecraft.goal.survival.wooden-axe-tree", "1.0", finalInput, finalStepAssertions,
                        List.of(new GoalAssertion("steps.craft-axe.woodenAxeEquipped", "equals", true)), true));
            } else {
                workflowSteps.add(GoalStep.invoke("wood-workflow", "minecraft.goal.survival.wooden-axe-tree", "1.0",
                        workflowInput(spec), true,
                        finalAssertions.stream()
                                .map(assertion -> new GoalAssertion("steps.wood-workflow." + assertion.path(),
                                        assertion.operator(), assertion.expected()))
                                .toArray(GoalAssertion[]::new)));
            }
            return new GoalPlan("survival.collect-wood", goal, List.of(
                    new GoalSegment("intelligent-wood", "Acquire the minimum wooden tools through visible crafting before collecting the target tree.",
                            workflowSteps, List.of())),
                    Map.of("taskId", "survival.collect-wood", "gameMode", "survival",
                            "toolPrerequisitePlanning", true,
                            "requiresCrafting", true, "ordinaryInputOnly", true,
                            "completionPredicateReady", true));
        }
        var state = GoalStep.observe("state", "minecraft.player.state.read", Map.of());
        var scan = GoalStep.observe("scan", "minecraft.world.region.scan", Map.of(
                "dimension", "minecraft:overworld", "x", 0, "y", 60, "z", 0,
                "sizeX", 32, "sizeY", 32, "sizeZ", 32));
        var move = GoalStep.invoke("approach", "minecraft.player.move", "2.0",
                Map.of("forward", 1.0, "durationMs", 500), true);
        var attack = GoalStep.invoke("mine", "minecraft.player.interact", "1.0",
                Map.of("action", "attack", "intelligence", spec.intelligence().id()), true);
        return new GoalPlan("survival.collect-wood", goal, List.of(
                new GoalSegment("find", "Read player state and bounded nearby blocks before acting.", List.of(state, scan), List.of()),
                new GoalSegment("act", "Approach and queue bounded mining input; a live agent must adapt aim and repeat.", List.of(move, attack), List.of())),
                Map.of("taskId", "survival.collect-wood", "gameMode", "survival",
                        "adaptiveLoopRequired", true, "completionPredicateReady", false));
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
