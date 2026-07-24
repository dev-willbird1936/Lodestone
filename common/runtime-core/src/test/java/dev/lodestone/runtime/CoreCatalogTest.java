// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.protocol.Availability;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreCatalogTest {
    @Test
    void loadsRecordBackedCatalogValuesWithoutReflectiveMutation() {
        // 73 = 56 pre-existing capabilities + minecraft.session.reconcile + minecraft.goal.move.goto
        // + minecraft.goal.gather.collect-drops + minecraft.goal.gather.chop-tree
        // + minecraft.goal.combat.attack-entity + minecraft.goal.survival.survive-night
        // + 11 hard-script capabilities loaded from the NeoForge-ready deterministic script catalog.
        assertEquals(73, CoreCatalog.load().size());
    }

    @Test
    void deterministicHardScriptCatalogHasTypedBoundedContracts() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        for (var id : java.util.List.of("minecraft.player.crosshair.read", "minecraft.world.block.find",
                "minecraft.player.block.look-at", "minecraft.player.block.mine",
                "minecraft.player.target-block.mine", "minecraft.inventory.hotbar.select-item",
                "minecraft.player.block.place", "minecraft.player.target-block.place",
                "minecraft.script.current.cancel", "minecraft.ui.inventory.open",
                "minecraft.ui.screen.close")) {
            var capability = capabilities.get(id);
            assertTrue(capability != null, "missing hard-script capability " + id);
            assertEquals("1.0", capability.version());
            assertEquals("client", capability.nativeThread());
            assertTrue(capability.featureFlags().containsAll(java.util.Set.of("hard-script", "agent-tool")));
            assertTrue(SchemaValidator.validateSchema(capability.inputSchema()).isEmpty(),
                    "hard-script input schema must be supported");
            assertTrue(SchemaValidator.validateSchema(capability.outputSchema()).isEmpty(),
                    "hard-script output schema must be supported");
        }
        var find = capabilities.get("minecraft.world.block.find");
        assertTrue(SchemaValidator.validate(find.inputSchema(), Map.of("block", "stone")).isEmpty());
        assertFalse(SchemaValidator.validate(find.inputSchema(), Map.of("block", "stone", "maxDistance", 33)).isEmpty());
        var cancel = capabilities.get("minecraft.script.current.cancel");
        assertTrue(SchemaValidator.validate(cancel.inputSchema(), Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(cancel.inputSchema(), Map.of("unexpected", true)).isEmpty());

        var openInventory = capabilities.get("minecraft.ui.inventory.open");
        assertTrue(SchemaValidator.validate(openInventory.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(openInventory.inputSchema(), Map.of("timeoutMs", 5000)).isEmpty());
        assertFalse(SchemaValidator.validate(openInventory.inputSchema(), Map.of("timeoutMs", 100)).isEmpty());
        assertFalse(SchemaValidator.validate(openInventory.inputSchema(), Map.of("timeoutMs", 10001)).isEmpty());
        assertTrue(SchemaValidator.validate(openInventory.outputSchema(), Map.of("opened", true,
                "alreadyOpen", true, "screenClass", "net.minecraft.client.gui.screens.inventory.InventoryScreen",
                "screenToken", "screen-2", "snapshotRevision", "a".repeat(64))).isEmpty());
        assertFalse(SchemaValidator.validate(openInventory.outputSchema(), Map.of("opened", true,
                "alreadyOpen", true, "screenClass", "net.minecraft.client.gui.screens.inventory.InventoryScreen")).isEmpty());

        var closeScreen = capabilities.get("minecraft.ui.screen.close");
        assertTrue(SchemaValidator.validate(closeScreen.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(closeScreen.inputSchema(), Map.of("timeoutMs", 5000)).isEmpty());
        assertFalse(SchemaValidator.validate(closeScreen.inputSchema(), Map.of("timeoutMs", 10001)).isEmpty());
        assertTrue(SchemaValidator.validate(closeScreen.outputSchema(), Map.of("closed", true,
                "alreadyClosed", true, "beforeScreenClass", "", "afterInWorld", true)).isEmpty());
    }

    @Test
    void netherCheckpointWorkflowHasExactlyThreeAdmissionsPerGoalWindow() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.reach-nether"))
                .findFirst().orElseThrow();

        assertEquals(3, capability.rateLimit().permits());
        assertEquals(600_000, capability.rateLimit().windowMs());
        assertEquals(3, capability.rateLimit().burst());
    }

    @Test
    void gotoGoalAcceptsBoundedInputAndValidatesEveryReasonCode() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.move.goto"))
                .findFirst().orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals("client", capability.nativeThread());
        assertTrue(capability.permissions().contains(dev.lodestone.protocol.PermissionClass.CONTROL_PLAYER));
        assertTrue(capability.permissions().contains(dev.lodestone.protocol.PermissionClass.MODIFY_WORLD));

        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("targetX", 10, "targetY", 64, "targetZ", -3)).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("targetX", 10, "targetY", 64, "targetZ", -3, "arriveRadius", 4,
                        "allowBlockBreaking", true, "allowMining", true, "timeoutTicks", 4800)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("targetX", 10, "targetY", 64)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("targetX", 10, "targetY", 64, "targetZ", -3, "arriveRadius", 9)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("targetX", 10, "targetY", 64, "targetZ", -3, "timeoutTicks", 50)).isEmpty());

        for (var reason : java.util.List.of("arrived", "timeout", "no-route", "repeated-mutation-failure", "cancelled")) {
            assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                    "arrived", reason.equals("arrived"), "finalPosition", Map.of("x", 1, "y", 64, "z", 2),
                    "distanceRemaining", 0.5, "ticksElapsed", 12, "blocksMined", 0, "reason", reason)).isEmpty(),
                    "reason " + reason + " must validate");
        }
        assertFalse(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "arrived", false, "finalPosition", Map.of("x", 1, "y", 64, "z", 2),
                "distanceRemaining", 0.5, "ticksElapsed", 12, "blocksMined", 0, "reason", "unknown-reason")).isEmpty());
        assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "arrived", false, "finalPosition", Map.of("x", 1, "y", 64, "z", 2),
                "distanceRemaining", 5.5, "ticksElapsed", 2400, "blocksMined", 3, "reason", "no-route",
                "nearestReachable", Map.of("x", 1, "y", 64, "z", 0),
                "obstructionSample", java.util.List.of(Map.of("position", Map.of("x", 1, "y", 65, "z", 0),
                        "block", "minecraft:oak_leaves")))).isEmpty());
    }

    @Test
    void collectDropsGoalAcceptsBoundedInputAndValidatesEveryReasonCode() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.gather.collect-drops"))
                .findFirst().orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals("client", capability.nativeThread());

        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("radius", 20, "timeoutTicks", 1200, "itemFilter", "oak_log")).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("radius", 33)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("timeoutTicks", 10)).isEmpty());

        for (var reason : java.util.List.of("collected-all", "timeout", "unreachable-remainder", "cancelled")) {
            assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                    "collected", Map.of("minecraft:oak_log", 4), "itemsRemaining", 0,
                    "ticksElapsed", 40, "reason", reason)).isEmpty(), "reason " + reason + " must validate");
        }
        assertFalse(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "collected", Map.of(), "itemsRemaining", 0, "ticksElapsed", 40, "reason", "bogus")).isEmpty());
    }

    @Test
    void chopTreeGoalAcceptsBoundedInputAndValidatesEveryReasonCode() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.gather.chop-tree"))
                .findFirst().orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals("client", capability.nativeThread());

        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("species", "dark_oak", "maxDistance", 40, "collectDrops", false,
                        "timeoutTicks", 1800)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("species", "acacia_wood")).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("maxDistance", 3)).isEmpty());

        for (var reason : java.util.List.of("complete", "no-tree-found", "timeout", "unreachable", "cancelled")) {
            assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                    "logsMined", 5, "logsCollected", Map.of("minecraft:oak_log", 5),
                    "saplingsCollected", 1, "reason", reason)).isEmpty(), "reason " + reason + " must validate");
        }
        assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "logsMined", 0, "logsCollected", Map.of(), "saplingsCollected", 0, "reason", "no-tree-found")).isEmpty());
        assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "logsMined", 6, "logsCollected", Map.of(), "saplingsCollected", 0,
                "treePosition", Map.of("x", 10, "y", 64, "z", -2), "reason", "complete")).isEmpty());
    }

    @Test
    void attackEntityGoalAcceptsBoundedInputAndValidatesEveryReasonCode() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.combat.attack-entity"))
                .findFirst().orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals("client", capability.nativeThread());

        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of("entityId", 42)).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("entityId", 42, "maxChaseBlocks", 40, "timeoutTicks", 2000)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("entityId", 42, "maxChaseBlocks", 65)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("entityId", 42, "timeoutTicks", 99)).isEmpty());

        for (var reason : java.util.List.of("killed", "target-lost", "fled-too-far", "timeout",
                "player-endangered", "cancelled")) {
            assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                    "killed", reason.equals("killed"), "targetType", "minecraft:zombie", "hits", 3,
                    "ticksElapsed", 80, "lootCollected", Map.of("minecraft:rotten_flesh", 1),
                    "reason", reason)).isEmpty(), "reason " + reason + " must validate");
        }
        assertFalse(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "killed", false, "targetType", "", "hits", 0, "ticksElapsed", 0,
                "lootCollected", Map.of(), "reason", "unknown-reason")).isEmpty());
    }

    @Test
    void surviveNightGoalAcceptsBoundedInputAndValidatesEveryReasonCode() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.survive-night"))
                .findFirst().orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals("client", capability.nativeThread());

        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of("timeoutTicks", 12000)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("timeoutTicks", 999)).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(), Map.of("timeoutTicks", 16001)).isEmpty());

        for (var reason : java.util.List.of("dawn", "not-night", "timeout", "no-shelter-material", "cancelled")) {
            assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.of(
                    "sheltered", reason.equals("dawn"), "ticksWaited", 9000,
                    "position", Map.of("x", 10, "y", 64, "z", -3), "reason", reason)).isEmpty(),
                    "reason " + reason + " must validate");
        }
        assertFalse(SchemaValidator.validate(capability.outputSchema(), Map.of(
                "sheltered", false, "ticksWaited", 0, "position", Map.of("x", 0, "y", 64, "z", 0),
                "reason", "unknown-reason")).isEmpty());
    }

    @Test
    void survivalTreeGoalRequiresAuthenticClientInputAndTerminalProof() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.wooden-axe-tree"))
                .findFirst().orElseThrow();

        assertTrue(capability.featureFlags().contains("authentic-input-only"));
        assertTrue(capability.featureFlags().contains("no-commands"));
        assertTrue(capability.featureFlags().contains("no-direct-world-mutation"));
        assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.ofEntries(
                Map.entry("survival", true), Map.entry("freshWorld", true), Map.entry("worldName", "New World"),
                Map.entry("worldGameTimeAtStart", 20), Map.entry("handMinedLogs", 3),
                Map.entry("planksCrafted", 12), Map.entry("sticksCrafted", 4),
                Map.entry("craftingTableCrafted", true), Map.entry("woodenAxeCrafted", true),
                Map.entry("woodenAxeEquipped", true), Map.entry("targetTreeInitialLogs", 5),
                Map.entry("targetTreeMinedLogs", 5), Map.entry("targetTreeRemainingLogs", 0),
                Map.entry("fullTreeMined", true), Map.entry("allTargetLogsMinedWithWoodenAxe", true),
                Map.entry("playerAlive", true), Map.entry("healthAtEnd", 20.0),
                Map.entry("commandsUsed", false), Map.entry("directMutationUsed", false),
                Map.entry("suppressInGameMessages", false), Map.entry("inGameMessagesEmitted", 8),
                Map.entry("navigationDiagnostics", java.util.List.of("planned resource tree route")),
                Map.entry("inputActions", java.util.List.of("move", "attack", "container-click")))).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("suppressInGameMessages", true)).isEmpty());
    }

    @Test
    void stoneToolsetGoalRequiresWoodenPickaxeBeforeStoneMiningAndTerminalSurfaceProof() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.stone-toolset"))
                .findFirst().orElseThrow();

        assertTrue(capability.featureFlags().contains("authentic-input-only"));
        assertTrue(capability.featureFlags().contains("no-commands"));
        assertTrue(capability.featureFlags().contains("no-direct-world-mutation"));
        assertTrue(capability.featureFlags().contains("full-stone-toolset"));
        assertTrue(capability.featureFlags().contains("terminal-surface-readback"));
        assertTrue(capability.featureFlags().contains("furnace-placement-readback"));
        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("suppressInGameMessages", true, "checkpoint", "wooden-tools")).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("checkpoint", "craft-axe")).isEmpty());
        assertTrue(SchemaValidator.validate(capability.outputSchema(), Map.ofEntries(
                Map.entry("freshWorld", true), Map.entry("survival", true), Map.entry("worldName", "New World"),
                Map.entry("worldGameTimeAtStart", 20), Map.entry("handMinedLogs", 4),
                Map.entry("planksCrafted", 16), Map.entry("sticksCrafted", 12),
                Map.entry("craftingTableCrafted", true), Map.entry("woodenPickaxeCrafted", true),
                Map.entry("woodenPickaxeEquipped", true), Map.entry("woodenAxeCrafted", true),
                Map.entry("cobblestoneMinedCount", 18), Map.entry("stonePickaxeCrafted", true),
                Map.entry("stoneAxeCrafted", true), Map.entry("stoneSwordCrafted", true),
                Map.entry("stoneShovelCrafted", true), Map.entry("fullStoneToolsetCrafted", true),
                Map.entry("furnaceCrafted", true), Map.entry("furnacePlaced", true),
                Map.entry("initialSurfaceY", 68), Map.entry("finalPosition", Map.of("x", 3, "y", 68, "z", -2)),
                Map.entry("endedOnSurface", true), Map.entry("playerAlive", true), Map.entry("healthAtEnd", 20.0),
                Map.entry("commandsUsed", false), Map.entry("directMutationUsed", false),
                Map.entry("suppressInGameMessages", false), Map.entry("inGameMessagesEmitted", 10),
                Map.entry("navigationDiagnostics", java.util.List.of("planned resource tree route")),
                Map.entry("inputActions", java.util.List.of("move", "attack", "container-click")))).isEmpty());
    }

    @Test
    void woolTreeZombieGoalSeparatesSilentSetupFromReactiveInputGameplay() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.creative.wool-tree-zombie-defense"))
                .findFirst().orElseThrow();
        assertTrue(capability.featureFlags().contains("manual-placement-input"));
        assertTrue(capability.featureFlags().contains("silent-explicit-setup-commands"));
        assertTrue(capability.featureFlags().contains("reactive-defense"));
        assertTrue(capability.featureFlags().contains("no-unconditional-kill"));
        assertTrue(capability.featureFlags().contains("no-direct-world-mutation"));
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("suppressInGameMessages", true)).isEmpty());
        var output = new java.util.LinkedHashMap<String, Object>();
        output.put("freshWorld", true);
        output.put("creativeSetupMode", true);
        output.put("manualTreeBuilt", true);
        output.put("manualPlacementInputOnly", true);
        output.put("trunkLogsPlaced", 3);
        output.put("woolLeavesPlaced", 9);
        output.put("manuallyPlacedBlocks", 12);
        output.put("zombieSetupComplete", true);
        output.put("teleportedAway", true);
        output.put("teleportDistance", 8.0);
        output.put("diamondSwordEquipped", true);
        output.put("survivalMode", true);
        output.put("zombieObserved", true);
        output.put("reactiveDefenseEvaluated", true);
        output.put("defensePolicyEvaluations", 40);
        output.put("threatDetections", 1);
        output.put("defensiveResponses", 8);
        output.put("defensiveAttacks", 2);
        output.put("zombieKilledByReactiveDefense", true);
        output.put("unconditionalKillRoutine", false);
        output.put("treeInitialBlocks", 12);
        output.put("treeMinedBlocks", 12);
        output.put("treeRemainingBlocks", 0);
        output.put("fullTreeMined", true);
        output.put("playerAlive", true);
        output.put("healthAtEnd", 18.0);
        output.put("setupCommandsUsed", true);
        output.put("setupCommandCount", 8);
        output.put("setupCommands", java.util.List.of("gamemode creative @p"));
        output.put("commandFeedbackSuppressed", true);
        output.put("suppressInGameMessages", true);
        output.put("inGameMessagesEmitted", 0);
        output.put("directMutationUsed", false);
        output.put("defenseDiagnostics", java.util.List.of("threat-detected"));
        output.put("inputActions", java.util.List.of("place:key.use", "defense:key.attack-reactive"));
        output.put("buildOrigin", Map.of("x", 2, "y", 64, "z", 2));
        output.put("awayPosition", Map.of("x", -6, "y", 64, "z", 2));
        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty());
    }

    @Test
    void netherGoalRequiresManualPortalInputAndDimensionReadback() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.reach-nether"))
                .findFirst().orElseThrow();
        assertTrue(capability.featureFlags().contains("manual-portal-input"));
        assertTrue(capability.featureFlags().contains("terminal-dimension-readback"));
        assertTrue(capability.featureFlags().contains("no-direct-world-mutation"));
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("suppressInGameMessages", true)).isEmpty());
        var output = new java.util.LinkedHashMap<String, Object>();
        output.put("freshWorld", true);
        output.put("survival", true);
        output.put("worldName", "New World");
        output.put("worldGameTimeAtStart", 20);
        output.put("initialDimension", "minecraft:overworld");
        output.put("finalDimension", "minecraft:the_nether");
        output.put("portalBase", Map.of("x", 0, "y", 64, "z", 0));
        output.put("teleportedToBuildSite", false);
        output.put("setupCommandsUsed", false);
        output.put("setupCommandCount", 0);
        output.put("setupCommands", java.util.List.of());
        output.put("commandFeedbackSuppressed", true);
        output.put("manualPortalBuilt", true);
        output.put("portalFrameBlocksPlaced", 10);
        output.put("portalLit", true);
        output.put("portalBlocksObserved", true);
        output.put("enteredPortal", true);
        output.put("reachedNether", true);
        output.put("playerAlive", true);
        output.put("healthAtEnd", 20.0);
        output.put("suppressInGameMessages", true);
        output.put("inGameMessagesEmitted", 0);
        output.put("directMutationUsed", false);
        output.put("inputActions", java.util.List.of("place:key.use:obsidian", "portal:key.use:flint-and-steel", "move:key.forward-into-portal"));
        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty());
    }

    @Test
    void spawnGauntletGoalRequiresOrdinaryInputAndSelfDiscoveredWaypointReadback() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.goal.survival.spawn-gauntlet"))
                .findFirst().orElseThrow();

        assertTrue(capability.featureFlags().contains("benchmark"));
        assertTrue(capability.featureFlags().contains("loaded-chunk-path-planner"));
        assertTrue(capability.featureFlags().contains("ordinary-player-input"));
        assertTrue(capability.featureFlags().contains("self-discovered-waypoint"));
        assertTrue(SchemaValidator.validate(capability.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(capability.inputSchema(),
                Map.of("intelligence", "medium", "safety", "medium")).isEmpty());
        assertFalse(SchemaValidator.validate(capability.inputSchema(),
                Map.of("targetX", 0, "targetY", 64, "targetZ", 0)).isEmpty());
        var output = new java.util.LinkedHashMap<String, Object>();
        output.put("playerAlive", true);
        output.put("reachedWaypoint", true);
        output.put("survivedFullDuration", true);
        output.put("elapsedTicks", 1800);
        output.put("spawnPosition", Map.of("x", 0, "y", 64, "z", 0));
        output.put("waypoint", Map.of("x", 32, "y", 64, "z", 0));
        output.put("finalPosition", Map.of("x", 32, "y", 64, "z", 0));
        output.put("healthAtEnd", 20.0);
        output.put("survival", true);
        output.put("freshWorld", true);
        output.put("worldGameTimeAtStart", 20);
        output.put("intelligence", "guarded-v1");
        output.put("safety", "balanced");
        output.put("policyMode", "guarded-v1+balanced");
        output.put("observation", "loaded-chunks");
        output.put("combatPolicy", "defensive");
        output.put("replans", 0);
        output.put("plannedPathLength", 4);
        output.put("pathNodesVisited", 4);
        output.put("directFallback", false);
        output.put("safetyInterventions", java.util.List.of());
        output.put("safetyInterventionCount", 0);
        output.put("inputActions", java.util.List.of("move:key.forward-held"));
        output.put("commandsUsed", false);
        output.put("directMutationUsed", false);
        output.put("allowCommands", false);
        output.put("toolPrerequisiteGuard", true);
        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty());
    }

    @Test
    void screenshotCaptureDeclaresItsHistoricalPermissionWithoutRestrictingUse() {
        var screenshot = CoreCatalog.load().stream()
                .filter(capability -> capability.id().equals("minecraft.client.screenshot.capture"))
                .findFirst().orElseThrow();

        assertEquals(java.util.Set.of(dev.lodestone.protocol.PermissionClass.CAPTURE_SCREEN),
                screenshot.permissions());
        assertTrue(AuthorizationPolicy.observeOnly().allows(screenshot));
        assertTrue(new AuthorizationPolicy(java.util.Set.of(
                dev.lodestone.protocol.PermissionClass.CAPTURE_SCREEN)).allows(screenshot));
    }

    @Test
    void formerNativePermissionCapabilitiesAreAvailableToReadyAdapters() {
        var navigate = CoreCatalog.load().stream()
                .filter(capability -> capability.id().equals("minecraft.player.look"))
                .findFirst().orElseThrow();

        assertEquals(Availability.AVAILABLE, navigate.availability());
        assertEquals(null, navigate.reason());
        assertTrue(navigate.documentation().contains("granted automatically"));
    }

    @Test
    void screenshotCaptureRunsOnClientRenderWithoutWorldPrerequisites() {
        var screenshot = CoreCatalog.load().stream()
                .filter(capability -> capability.id().equals("minecraft.client.screenshot.capture"))
                .findFirst().orElseThrow();

        assertEquals("1.0", screenshot.version());
        assertEquals(dev.lodestone.protocol.Stability.EXPERIMENTAL, screenshot.stability());
        assertEquals(dev.lodestone.protocol.Availability.UNAVAILABLE, screenshot.availability());
        assertEquals("native-handler-not-installed", screenshot.reason().code());
        assertEquals(dev.lodestone.protocol.Environment.CLIENT, screenshot.environment());
        assertEquals(dev.lodestone.protocol.SideEffect.NONE, screenshot.sideEffect());
        assertEquals(dev.lodestone.protocol.Idempotency.NON_IDEMPOTENT, screenshot.idempotency());
        assertEquals("client-render", screenshot.nativeThread());
        assertFalse(screenshot.prerequisites().requiresWorld());
        assertFalse(screenshot.prerequisites().requiresPlayer());
        assertFalse(screenshot.prerequisites().requiresScreen());
        assertFalse(screenshot.prerequisites().requiresContainer());
    }

    @Test
    void screenshotCapturePublishesBoundedPngArtifactSchema() {
        var screenshot = CoreCatalog.load().stream()
                .filter(capability -> capability.id().equals("minecraft.client.screenshot.capture"))
                .findFirst().orElseThrow();
        var hash = "a".repeat(64);
        var artifact = Map.<String, Object>ofEntries(
                Map.entry("uri", "lodestone://artifacts/sha256/" + hash),
                Map.entry("mediaType", "image/png"), Map.entry("sha256", hash),
                Map.entry("sizeBytes", 1024), Map.entry("expiresAtEpochMs", 60_000));
        var metadata = Map.<String, Object>ofEntries(
                Map.entry("artifact", artifact),
                Map.entry("width", 1920), Map.entry("height", 1080),
                Map.entry("originalWidth", 2560), Map.entry("originalHeight", 1440),
                Map.entry("playerPosition", Map.of("x", 1.5, "y", 64.0, "z", -2.5)),
                Map.entry("playerRotation", Map.of("yaw", 90.0, "pitch", -15.0)));

        assertTrue(SchemaValidator.validate(screenshot.inputSchema(), Map.of()).isEmpty());
        assertTrue(SchemaValidator.validate(screenshot.inputSchema(),
                Map.of("maxWidth", 1, "maxHeight", 8192)).isEmpty());
        assertFalse(SchemaValidator.validate(screenshot.inputSchema(), Map.of("maxWidth", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(screenshot.inputSchema(), Map.of("maxHeight", 8193)).isEmpty());
        assertFalse(SchemaValidator.validate(screenshot.inputSchema(), Map.of("maxWidth", 1920,
                "maxHeight", 1080, "extra", true)).isEmpty());
        assertTrue(SchemaValidator.validate(screenshot.outputSchema(), metadata).isEmpty());
        var missingHashArtifact = new java.util.LinkedHashMap<>(artifact);
        missingHashArtifact.remove("sha256");
        var missingHash = new java.util.LinkedHashMap<>(metadata);
        missingHash.put("artifact", missingHashArtifact);
        assertFalse(SchemaValidator.validate(screenshot.outputSchema(), missingHash).isEmpty());
        var invalidArtifact = new java.util.LinkedHashMap<>(artifact);
        invalidArtifact.put("mediaType", "image/jpeg");
        var invalidMediaType = new java.util.LinkedHashMap<>(metadata);
        invalidMediaType.put("artifact", invalidArtifact);
        assertFalse(SchemaValidator.validate(screenshot.outputSchema(), invalidMediaType).isEmpty());
        var oversizedArtifact = new java.util.LinkedHashMap<>(artifact);
        oversizedArtifact.put("sizeBytes", 11L * 1024L * 1024L + 1L);
        var oversized = new java.util.LinkedHashMap<>(metadata);
        oversized.put("artifact", oversizedArtifact);
        assertFalse(SchemaValidator.validate(screenshot.outputSchema(), oversized).isEmpty());
        assertTrue(screenshot.featureFlags().contains("staged-artifact"));
        assertTrue(screenshot.featureFlags().contains("total-pixel-bound"));
    }

    @Test
    void stableImplementedCapabilitiesPublishEnforcedSchemas() {
        var capabilities = CoreCatalog.load();
        for (var capability : capabilities) {
            if (capability.stability() != dev.lodestone.protocol.Stability.STABLE) {
                continue;
            }
            assertFalse(isBareObject(capability.inputSchema()), capability.id() + " has a placeholder input schema");
            assertFalse(isBareObject(capability.outputSchema()), capability.id() + " has a placeholder output schema");
        }
    }

    @Test
    void promotedCommandBlockAndMouseContractsRejectMalformedInputs() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        var command = capabilities.get("minecraft.command.execute");
        assertFalse(command.prerequisites().requiresPlayer());
        assertTrue(SchemaValidator.validate(command.inputSchema(), Map.of("command", "say lodestone")).isEmpty());
        assertFalse(SchemaValidator.validate(command.inputSchema(), Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(command.inputSchema(), Map.of("command", "say hi", "extra", true)).isEmpty());

        var blockRead = capabilities.get("minecraft.world.block.read");
        assertTrue(SchemaValidator.validate(blockRead.inputSchema(), Map.of("x", 0, "y", 64, "z", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(blockRead.inputSchema(), Map.of("x", 0, "z", 0)).isEmpty());

        var mouse = capabilities.get("minecraft.input.mouse.set");
        assertTrue(SchemaValidator.validate(mouse.inputSchema(), Map.of("button", 0, "down", true)).isEmpty());
        assertTrue(SchemaValidator.validate(mouse.inputSchema(), Map.of("key", "attack", "down", true)).isEmpty());
        assertFalse(SchemaValidator.validate(mouse.inputSchema(), Map.of("down", true)).isEmpty());
    }

    @Test
    void catalogPublishesGuardedUiLeasedMovementReleaseAndHonestCraftContracts() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        var revision = "a".repeat(64);

        var uiState = capabilities.get("minecraft.ui.state.read");
        assertEquals("2.0", uiState.version());
        assertTrue(SchemaValidator.validate(uiState.outputSchema(), Map.ofEntries(
                Map.entry("open", true), Map.entry("inWorld", true), Map.entry("screen", "pause"),
                Map.entry("screenClass", "net.minecraft.client.gui.screens.PauseScreen"),
                Map.entry("title", "Game Menu"), Map.entry("screenToken", "screen-2"),
                Map.entry("snapshotRevision", revision), Map.entry("capturedAtTick", 20),
                Map.entry("width", 426), Map.entry("height", 240), Map.entry("guiScale", 2),
                Map.entry("coverage", "partial"), Map.entry("truncated", true),
                Map.entry("truncationCauses", java.util.List.of("unsupported-widget")),
                Map.entry("widgets", java.util.List.of(Map.of("nodeId", "n0", "path", java.util.List.of(0),
                        "depth", 0, "type", "button", "focused", true, "actions", java.util.List.of("click"))))
        )).isEmpty());

        var click = capabilities.get("minecraft.ui.click");
        assertEquals("2.0", click.version());
        assertTrue(SchemaValidator.validate(click.inputSchema(), Map.of("screenToken", "screen-2",
                "snapshotRevision", revision, "button", 0, "label", "Back to Game")).isEmpty());
        assertTrue(SchemaValidator.validate(click.inputSchema(), Map.of("screenToken", "screen-2",
                "snapshotRevision", revision, "label", "Back to Game")).isEmpty());
        assertFalse(SchemaValidator.validate(click.inputSchema(), Map.of("screenToken", "screen-2",
                "snapshotRevision", revision, "button", 0, "x", 12)).isEmpty());
        assertFalse(SchemaValidator.validate(click.inputSchema(), Map.of("screenToken", "screen-2",
                "snapshotRevision", revision, "nodeId", "n0", "label", "Back to Game")).isEmpty());

        var key = capabilities.get("minecraft.ui.key");
        assertEquals("1.0", key.version());
        assertTrue(SchemaValidator.validate(key.outputSchema(), Map.of("handled", true)).isEmpty());
        assertTrue(SchemaValidator.validate(key.outputSchema(), Map.of("handled", true, "openedPause", true)).isEmpty());

        var move = capabilities.get("minecraft.player.move");
        assertEquals("2.0", move.version());
        assertTrue(SchemaValidator.validate(move.outputSchema(), Map.of("forward", 1, "strafe", 0,
                "jump", false, "sprint", true, "sneak", false, "durationMs", 100,
                "leaseGeneration", 3)).isEmpty());
        assertFalse(SchemaValidator.validate(move.outputSchema(), Map.of("forward", 1, "strafe", 0,
                "jump", false, "sprint", true, "sneak", false, "durationMs", 100)).isEmpty());

        var release = capabilities.get("minecraft.input.release-all");
        assertEquals(dev.lodestone.protocol.CapabilityKind.INPUT, release.kind());
        assertEquals(java.util.Set.of(dev.lodestone.protocol.PermissionClass.CONTROL_PLAYER), release.permissions());
        assertTrue(SchemaValidator.validate(release.inputSchema(), Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(release.inputSchema(), Map.of("extra", true)).isEmpty());

        var craft = capabilities.get("minecraft.inventory.craft");
        assertEquals(dev.lodestone.protocol.Stability.EXPERIMENTAL, craft.stability());
        assertEquals(dev.lodestone.protocol.Availability.UNAVAILABLE, craft.availability());
        assertFalse(craft.prerequisites().requiresScreen());
        assertFalse(craft.prerequisites().requiresContainer());
        assertTrue(SchemaValidator.validate(craft.outputSchema(), Map.of("item", "minecraft:torch",
                "requestedCount", 4, "craftedCount", 4, "complete", true)).isEmpty());
        assertFalse(SchemaValidator.validate(craft.outputSchema(), Map.of("item", "minecraft:torch",
                "requestedCount", 4, "craftedCount", 4)).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiWaitPublishesAnExactUiStateV2ProjectionAndBoundedPollingContract() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        var uiState = capabilities.get("minecraft.ui.state.read");
        var wait = capabilities.get("lodestone.ui.wait");
        var outputProperties = (Map<String, Object>) wait.outputSchema().get("properties");

        assertEquals("1.0", wait.version());
        assertEquals(dev.lodestone.protocol.Stability.EXPERIMENTAL, wait.stability());
        assertEquals(uiState.outputSchema(), outputProperties.get("state"));
        assertTrue(wait.featureFlags().containsAll(java.util.Set.of(
                "delegates-native", "asynchronous-polling", "deterministic-polling", "ui-state-v2")));
        assertTrue(SchemaValidator.validate(wait.inputSchema(), Map.of("until", "in_world")).isEmpty());
        assertTrue(SchemaValidator.validate(wait.inputSchema(), Map.of("until", "screen_class:PauseScreen",
                "timeoutMs", 60_000, "pollIntervalMs", 100)).isEmpty());
        assertFalse(SchemaValidator.validate(wait.inputSchema(), Map.of("until", "screen_class:")).isEmpty());
        assertFalse(SchemaValidator.validate(wait.inputSchema(), Map.of("until", "screen_open",
                "pollIntervalMs", 99)).isEmpty());
        assertTrue(SchemaValidator.validate(wait.outputSchema(), Map.of(
                "timedOut", false, "waitedFor", "screen_open", "pollCount", 1, "elapsedMs", 0,
                "state", uiStateOutput(true, "pause", "net.minecraft.client.gui.screens.PauseScreen"))).isEmpty());

        var click = capabilities.get("minecraft.ui.click");
        var navigate = capabilities.get("lodestone.ui.navigate");
        var navigateProperties = (Map<String, Object>) navigate.outputSchema().get("properties");
        assertEquals(uiState.outputSchema(), navigateProperties.get("before"));
        assertEquals(click.outputSchema(), navigateProperties.get("click"));
        assertEquals(uiState.outputSchema(), navigateProperties.get("after"));
        assertTrue(SchemaValidator.validate(navigate.inputSchema(), Map.of("target", "singleplayer")).isEmpty());
        assertTrue(SchemaValidator.validate(navigate.inputSchema(), Map.of("target", "world_tab")).isEmpty());
        assertTrue(SchemaValidator.validate(navigate.inputSchema(), Map.of("target", "world_seed")).isEmpty());
        assertFalse(SchemaValidator.validate(navigate.inputSchema(), Map.of("target", "inventory")).isEmpty());
        assertTrue(SchemaValidator.validate(navigate.outputSchema(), Map.ofEntries(
                Map.entry("target", "singleplayer"), Map.entry("label", "Singleplayer"),
                Map.entry("match", "exact"), Map.entry("handled", true),
                Map.entry("before", uiStateOutput(true, "title", "net.minecraft.client.gui.screens.TitleScreen")),
                Map.entry("click", Map.of("handled", true, "x", 100, "y", 80,
                        "screenToken", "screen-2", "snapshotRevision", "a".repeat(64), "nodeId", "n0")),
                Map.entry("after", uiStateOutput(true, "select-world",
                        "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen"))
        )).isEmpty());
    }

    @Test
    void parityReadAndPlayerActorCommandContractsAreBoundedAndTyped() {
        var capabilities = CoreCatalog.load().stream()
                .collect(Collectors.toMap(dev.lodestone.protocol.CapabilityDescriptor::id, Function.identity()));
        var itemSearch = capabilities.get("minecraft.registry.item.search");
        assertTrue(SchemaValidator.validate(itemSearch.inputSchema(), Map.of("query", "stone", "limit", 50)).isEmpty());
        assertFalse(SchemaValidator.validate(itemSearch.inputSchema(), Map.of("query", "", "limit", 51)).isEmpty());

        var serverInfo = capabilities.get("minecraft.server.info.read");
        assertTrue(SchemaValidator.validate(serverInfo.inputSchema(), Map.of()).isEmpty());
        assertFalse(SchemaValidator.validate(serverInfo.inputSchema(), Map.of("extra", true)).isEmpty());

        var context = capabilities.get("minecraft.player.context.read");
        assertTrue(SchemaValidator.validate(context.inputSchema(), Map.of("reach", 128)).isEmpty());
        assertFalse(SchemaValidator.validate(context.inputSchema(), Map.of("reach", 257)).isEmpty());

        var nearby = capabilities.get("minecraft.entity.nearby.read");
        assertTrue(SchemaValidator.validate(nearby.inputSchema(), Map.of(
                "radius", 32, "limit", 64, "includePlayers", false)).isEmpty());
        assertFalse(SchemaValidator.validate(nearby.inputSchema(), Map.of("limit", 0)).isEmpty());

        var playerCommand = capabilities.get("minecraft.player.command.execute");
        assertTrue(SchemaValidator.validate(playerCommand.inputSchema(), Map.of(
                "player", Map.of("uuid", "00000000-0000-0000-0000-000000000001"),
                "command", "//pos1", "capture", Map.of("enabled", true, "maxMessages", 64))).isEmpty());
        assertFalse(SchemaValidator.validate(playerCommand.inputSchema(), Map.of(
                "player", Map.of(), "command", "//pos1")).isEmpty());
        assertEquals(dev.lodestone.protocol.PermissionClass.ADMINISTER_SERVER,
                playerCommand.permissions().iterator().next());
        assertEquals(dev.lodestone.protocol.SideEffect.ADMINISTER_SERVER, playerCommand.sideEffect());

        var furniture = capabilities.get("lodestone.furniture.place");
        assertTrue(SchemaValidator.validate(furniture.inputSchema(), Map.of(
                "furniture_id", "corner_table", "origin_x", 10, "origin_y", 64, "origin_z", -5,
                "facing", "east", "place_on_surface", true, "preview_only", true)).isEmpty());
        assertFalse(SchemaValidator.validate(furniture.inputSchema(), Map.of(
                "furniture_id", "corner_table", "origin_x", 10, "origin_y", 64, "origin_z", -5,
                "facing", "up")).isEmpty());
        assertEquals(java.util.Set.of(dev.lodestone.protocol.PermissionClass.MODIFY_WORLD),
                furniture.permissions());
        assertTrue(furniture.featureFlags().contains("delegates-native"));

        var maskValidation = capabilities.get("lodestone.worldedit.mask.validate");
        assertEquals(dev.lodestone.protocol.Stability.EXPERIMENTAL, maskValidation.stability());
        assertEquals(java.util.Set.of(dev.lodestone.protocol.PermissionClass.OBSERVE),
                maskValidation.permissions());
        assertEquals(dev.lodestone.protocol.SideEffect.NONE, maskValidation.sideEffect());
        assertTrue(maskValidation.featureFlags().containsAll(java.util.Set.of(
                "local-structural-validation", "no-worldedit-dependency", "no-evaluation",
                "server-validation-required")));
        assertTrue(SchemaValidator.validate(maskValidation.inputSchema(), Map.of("mask", "#existing")).isEmpty());
        assertFalse(SchemaValidator.validate(maskValidation.inputSchema(), Map.of("mask", "")).isEmpty());

        var heightmap = capabilities.get("minecraft.world.heightmap.read");
        assertTrue(SchemaValidator.validate(heightmap.inputSchema(), Map.of(
                "x", 0, "z", 0, "sizeX", 256, "sizeZ", 256, "includeSurfaceBlocks", true)).isEmpty());
        assertFalse(SchemaValidator.validate(heightmap.inputSchema(), Map.of(
                "x", 0, "z", 0, "sizeX", 257, "sizeZ", 1)).isEmpty());
        assertTrue(SchemaValidator.validate(heightmap.outputSchema(), Map.of(
                "dimension", "minecraft:overworld", "origin", Map.of("x", 0, "z", 0),
                "size", Map.of("x", 1, "z", 1), "columnCount", 1, "loadedColumns", 0,
                "unloadedColumns", 1, "columns", java.util.List.of(Map.of(
                        "x", 0, "z", 0, "loaded", false, "empty", false)),
                "stats", Map.of("hasHeightData", false, "minHeight", 0, "maxHeight", 0,
                        "heightRange", 0))).isEmpty());

        var light = capabilities.get("minecraft.world.light.analyze");
        assertTrue(SchemaValidator.validate(light.inputSchema(), Map.of(
                "x", 0, "y", 64, "z", 0, "sizeX", 1, "sizeY", 1, "sizeZ", 1,
                "resolution", 4, "darkSpotLimit", 0, "lightSourceLimit", 0)).isEmpty());
        assertFalse(SchemaValidator.validate(light.inputSchema(), Map.of(
                "x", 0, "y", 64, "z", 0, "sizeX", 1, "sizeY", 1, "sizeZ", 1,
                "resolution", 5)).isEmpty());
        var emptyDistribution = Map.of("count", 0, "percentage", 0);
        assertTrue(SchemaValidator.validate(light.outputSchema(), Map.ofEntries(
                Map.entry("dimension", "minecraft:overworld"),
                Map.entry("origin", Map.of("x", 0, "y", 64, "z", 0)),
                Map.entry("size", Map.of("x", 1, "y", 1, "z", 1)), Map.entry("resolution", 1),
                Map.entry("candidateSamples", 1), Map.entry("analyzedSamples", 0),
                Map.entry("solidSamples", 0), Map.entry("unloadedSamples", 1),
                Map.entry("averageCombinedLight", 0),
                Map.entry("histogram", java.util.Collections.nCopies(16, 0)),
                Map.entry("distribution", Map.of("wellLit", emptyDistribution,
                        "dim", emptyDistribution, "dark", emptyDistribution)),
                Map.entry("darkSpotCount", 0), Map.entry("darkSpotsTruncated", false),
                Map.entry("darkSpots", java.util.List.of()), Map.entry("lightSourceCount", 0),
                Map.entry("lightSourcesTruncated", false), Map.entry("lightSources", java.util.List.of()),
                Map.entry("mobSpawnRisk", "none"), Map.entry("suggestions", java.util.List.of())
        )).isEmpty());
    }

    private static Map<String, Object> uiStateOutput(boolean open, String screen, String screenClass) {
        return Map.ofEntries(
                Map.entry("open", open), Map.entry("inWorld", true), Map.entry("screen", screen),
                Map.entry("screenClass", screenClass), Map.entry("title", open ? "Game Menu" : ""),
                Map.entry("screenToken", "screen-2"), Map.entry("snapshotRevision", "a".repeat(64)),
                Map.entry("capturedAtTick", 20), Map.entry("width", 426), Map.entry("height", 240),
                Map.entry("guiScale", 2), Map.entry("coverage", "complete"), Map.entry("truncated", false),
                Map.entry("truncationCauses", java.util.List.of()), Map.entry("widgets", java.util.List.of()));
    }

    private static boolean isBareObject(Map<String, Object> schema) {
        return schema.size() == 1 && "object".equals(schema.get("type"));
    }
}
