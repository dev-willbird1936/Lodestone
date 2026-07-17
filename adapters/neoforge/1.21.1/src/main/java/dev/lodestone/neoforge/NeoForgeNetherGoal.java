// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Realtime-first survival Nether workflow.
 *
 * <p>The actor starts from a fresh random-seed survival world and gathers or loots every required
 * resource through ordinary player input. The portal frame, ignition, movement, and dimension
 * transition are all performed and read back through ordinary client input. No command, teleport,
 * direct inventory edit, or direct world mutation is available to this actor.</p>
 */
final class NeoForgeNetherGoal implements NeoForgeResumableGoal {
    // The MCP goal engine accepts at most ten minutes for a single capability call. Keep the
    // actor bound aligned with that limit so a legitimate random-seed resource route has time
    // to discover wood, craft tools, gather iron/flint, and build the portal by hand.
    private static final int MAX_TOTAL_TICKS = 12_000;
    private static final double MINING_VANTAGE_TOLERANCE = 0.45;
    private static final int MAX_SEARCH_ATTEMPTS = 18;
    private static final int LOGS_REQUIRED = 5;
    private static final int COBBLESTONE_REQUIRED = 11;
    private static final int RAW_IRON_REQUIRED = 4;
    private static final int STARTER_RESOURCE_SCAN_HORIZONTAL_RADIUS = 32;
    private static final int STARTER_RESOURCE_SCAN_BELOW = 8;
    private static final int STARTER_RESOURCE_SCAN_ABOVE = 24;
    private static final int MINING_SEARCH_WORK_BUDGET = 4;
    private static final int MAX_PREREQUISITE_STAIR_STEPS = 10;
    private static final int MAX_PREREQUISITE_PLAN_REJECTIONS = 4;
    private static final int MAX_PREREQUISITE_BLOCKS_BROKEN = 20;

    private InvocationContext invocation;
    private CompletableFuture<Map<String, Object>> result;
    private final boolean suppressInGameMessages;
    private final NeoForgeGoalPolicy policy;
    private final List<String> inputActions = new ArrayList<>();
    private final List<String> safetyDiagnostics = new ArrayList<>();
    private final HashSet<BlockPos> rejectedResourceSources = new HashSet<>();
    private final NeoForgeMiningTargetSearch miningTargetSearch = new NeoForgeMiningTargetSearch();
    private final NeoForgeLivenessWatchdog livenessWatchdog = new NeoForgeLivenessWatchdog();
    private final NeoForgeFrontierWatchdog frontierWatchdog = new NeoForgeFrontierWatchdog();
    private final HashSet<Long> rejectedLivenessFrontiers = new HashSet<>();
    private final NeoForgeGoalSupervisor supervisor;
    private final String continuationToken = UUID.randomUUID().toString();
    private final List<Placement> placements = new ArrayList<>();
    private final java.util.ArrayDeque<ClickOp> clicks = new java.util.ArrayDeque<>();
    private final List<BlockPos> portalScaffolds = new ArrayList<>();

    private Stage stage = Stage.WAIT_WORLD;
    private Stage lastLoggedStage;
    private int lastLoggedTotalTicks;
    private String requestedCheckpoint;
    private boolean paused;
    private boolean finished;
    private Site site;
    private BlockPos lootChest;
    private BlockPos lootInteractionVantage;
    private Runnable clicksComplete;
    private int clickDelay;
    private int waitTicks;
    private int stageTicks;
    private int totalTicks;
    private int placementIndex;
    private int placementAttempts;
    private int lightAttempts;
    private int portalScaffoldIndex;
    private int portalTargetIndex;
    private int portalPhase;
    private int clearIndex;
    private int inGameMessagesEmitted;
    private int frameBlocksPlaced;
    private boolean survival;
    private boolean freshWorld;
    private boolean teleportedToBuildSite;
    private boolean manualPortalBuilt;
    private boolean portalLit;
    private boolean portalBlocksObserved;
    private boolean enteredPortal;
    private boolean reachedNether;
    private boolean recipeStarted;
    private boolean smeltLoaded;
    private boolean smeltCollected;
    private boolean woodPickVerificationPending;
    private int woodPickAttempts;
    private BlockPos placementVantage;
    private BlockPos navigationDestination;
    private double navigationLastDistance = Double.POSITIVE_INFINITY;
    private int navigationStuckTicks;
    private NeoForgeSafePathPlanner.ArrivalSpec navigationArrival;
    private long observedNavigationReplanEpoch;
    private long observedNavigationAbandonEpoch;
    private BlockPos verticalRecoveryGoal;
    private BlockPos verticalRecoveryWaypoint;
    private final HashSet<Long> rejectedVerticalRecoveryWaypoints = new HashSet<>();
    private List<BlockPos> navigationPath = List.of();
    private int navigationIndex;
    private Vec3 explorationLastPosition;
    private int explorationNoProgressTicks;
    private BlockPos explorationWaypoint;
    private Vec3 explorationProgressAnchor;
    private int explorationProgressTicks;
    private int explorationReplanCooldown;
    private Stage afterTableClose = Stage.EQUIP_WOOD_PICK;
    private Stage nextTableStage = Stage.CRAFT_WOOD_PICK;
    private int searchAttempts;
    private int mineIndex;
    private int stoneMineIndex;
    private int ironMineIndex;
    private int gravelMineIndex;
    private int handMinedLogs;
    private int resourceBlocksToMine = LOGS_REQUIRED;
    private int cobblestoneMined;
    private int rawIronMined;
    private int gravelMined;
    private int flintObserved;
    private int smeltTicks;
    private int interactionAttempts;
    private int toolHotbarSlot = -1;
    private int tableHotbarSlot = -1;
    private int furnaceHotbarSlot = -1;
    private int bucketHotbarSlot = -1;
    private int flintSteelHotbarSlot = -1;
    private NeoForgeStarterResourceCatalog.Source resourceSource;
    private BlockPos resourceMiningVantage;
    private boolean resourceApproachOnly;
    private final HashSet<Long> rejectedResourceVantages = new HashSet<>();
    private BlockPos resourceRejectionAnchor;
    private int localResourceVantageRejections;
    private final HashSet<UUID> rejectedCollectibles = new HashSet<>();
    private UUID collectibleTargetId;
    private BlockPos collectibleSupportBlock;
    private BlockPos miningVantageTarget;
    private BlockPos miningVantage;
    private String miningSearchKind = "";
    private NeoForgeMiningTargetSearch.BatchResult miningSearchTelemetry;
    private NeoForgeLivenessWatchdog.TickResult livenessTelemetry;
    private SafeFrontier livenessFrontier;
    private BlockPos tablePosition;
    private BlockPos tableInteractionVantage;
    private boolean tableInteractionPositionReady;
    private BlockPos furnacePosition;
    private BlockPos waterSource;
    private BlockPos lavaSource;
    private BlockPos fallbackPortalBase;
    private List<BlockPos> observedStone = List.of();
    private List<BlockPos> observedIron = List.of();
    private List<BlockPos> observedGravel = List.of();
    private BlockPos exhaustedStoneScanOrigin;
    private BlockPos exhaustedIronScanOrigin;
    private BlockPos exhaustedGravelScanOrigin;
    private PrerequisiteResource prerequisiteResource;
    private NeoForgePrerequisiteAcquisitionPlanner.Plan prerequisitePlan;
    private BlockPos prerequisiteRetreatOrigin;
    private Direction prerequisiteDirection;
    private BlockPos prerequisiteLastBreakTarget;
    private int prerequisiteBreakIndex;
    private int prerequisitePhase;
    private int prerequisiteCommittedSteps;
    private int prerequisitePlanRejections;
    private int prerequisiteBlocksBroken;
    private boolean prerequisiteSwingActive;
    private int prerequisiteStepMovementTicks;
    private BlockPos prerequisiteStepMovementCell;
    private final HashSet<Long> prerequisiteRejectedSteps = new HashSet<>();
    private String prerequisiteAcquisitionStrategy = "none";
    private String prerequisiteAcquisitionReason = "none";
    private String prerequisiteGateOutcome = "none";
    private String worldName = "fresh-survival-world";
    private String initialDimension = "";
    private String finalDimension = "";
    private long worldGameTimeAtStart;

    NeoForgeNetherGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.suppressInGameMessages = Boolean.TRUE.equals(
                invocation.request().input().get("suppressInGameMessages"));
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
        this.supervisor = new NeoForgeGoalSupervisor(policy, inputActions, safetyDiagnostics);
        this.requestedCheckpoint = checkpoint(invocation.request().input());
    }

    @Override
    public boolean done() {
        return finished;
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public String continuationToken() {
        return continuationToken;
    }

    @Override
    public void resume(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        if (!paused || finished) throw new IllegalStateException("Nether goal is not resumable");
        this.invocation = invocation;
        this.result = result;
        this.requestedCheckpoint = checkpoint(invocation.request().input());
        this.paused = false;
        this.stageTicks = 0;
        this.waitTicks = 1;
    }

    void tick(Minecraft client) {
        if (done() || paused) return;
        try {
            var player = client.player;
            if (player != null) {
                var survivalAction = NeoForgeSurvivalInvariant.decide(player.isDeadOrDying(),
                        player.getHealth(), client.screen instanceof DeathScreen, player.isInWater(),
                        player.getAirSupply(), player.getMaxAirSupply());
                if (survivalAction == NeoForgeSurvivalInvariant.Action.PLAYER_DIED) {
                    throw new IllegalStateException("PLAYER_DIED: survival actor health reached zero");
                }
                if (survivalAction == NeoForgeSurvivalInvariant.Action.WATER_RETREAT
                        && supervisor.tickImmediateSurvival(client)) return;
            }
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("Nether goal exceeded its bounded input budget");
            }
            livenessTelemetry = frontierWatchdog.active() ? null : tickLiveness(client);
            if (livenessTelemetry != null
                    && livenessTelemetry.action() == NeoForgeLivenessWatchdog.Action.LIVENESS_EXHAUSTED) {
                throw new IllegalStateException("LIVENESS_EXHAUSTED: no displacement, inventory/stage progress, "
                        + "new geometry, or reachable safe frontier after "
                        + livenessTelemetry.recoveryCount() + " bounded recoveries");
            }
            if (livenessTelemetry != null
                    && livenessTelemetry.action() == NeoForgeLivenessWatchdog.Action.RECOVER) {
                scheduleLivenessRecovery(client);
            }
            if (stage != lastLoggedStage || totalTicks - lastLoggedTotalTicks >= 200) {
                logStage(client);
                lastLoggedStage = stage;
                lastLoggedTotalTicks = totalTicks;
            }
            // Startup validation must own the tick until the player/world contract is proven.
            // Otherwise a stale movement lease from the UI transition can let the safety
            // supervisor preempt WAIT_WORLD forever, leaving the actor visibly idle at spawn.
            var replanEpoch = supervisor.navigationReplanEpoch();
            var abandonEpoch = supervisor.navigationAbandonEpoch();
            if (abandonEpoch != observedNavigationAbandonEpoch) {
                observedNavigationAbandonEpoch = abandonEpoch;
                if (verticalRecoveryWaypoint != null) {
                    rejectedVerticalRecoveryWaypoints.add(verticalRecoveryWaypoint.asLong());
                    safetyDiagnostics.add("navigation-replan:reject-vertical-recovery-waypoint:"
                            + verticalRecoveryWaypoint);
                    clearVerticalRecovery(false);
                    resetNavigation();
                    stopMovement(client);
                    return;
                }
                if (stage == Stage.NAVIGATE_STARTER_RESOURCE && resourceSource != null) {
                    rejectedResourceSources.add(resourceSource.anchor().immutable());
                    safetyDiagnostics.add("resource-replan:supervisor-abandoned-local-source:"
                            + resourceSource.anchor());
                    resourceSource = null;
                    resourceMiningVantage = null;
                    resourceApproachOnly = false;
                    resetNavigation();
                    stopMovement(client);
                    transition(Stage.FIND_STARTER_RESOURCE, 15);
                    return;
                }
            }
            if (replanEpoch != observedNavigationReplanEpoch) {
                observedNavigationReplanEpoch = replanEpoch;
                resetNavigation();
            }
            if (stage != Stage.WAIT_WORLD
                    && supervisor.tick(client, supervisorMovementExpected(client), movementIntent())) return;
            if (livenessFrontier != null) {
                stopAttack(client);
                var frontierId = livenessFrontier.waypoint().asLong();
                var frontierDistance = Math.sqrt(requirePlayer(client)
                        .distanceToSqr(Vec3.atCenterOf(livenessFrontier.waypoint())));
                var frontierAction = frontierWatchdog.tick(frontierId, frontierDistance);
                if (frontierAction != NeoForgeFrontierWatchdog.Action.NONE) {
                    rejectLivenessFrontier(client, frontierAction);
                    return;
                }
                if (navigateTo(client, livenessFrontier.waypoint(), 1.2,
                        "liveness-safe-frontier:" + stage)) {
                    var reached = livenessFrontier;
                    frontierWatchdog.reached(frontierId);
                    rejectedLivenessFrontiers.add(reached.waypoint().asLong());
                    safetyDiagnostics.add("liveness-frontier:reached:" + reached.waypoint()
                            + ":path=" + reached.pathLength() + ":retreat=" + reached.retreatLength());
                    livenessFrontier = null;
                    resetNavigation();
                    var sample = livenessSample(client);
                    if (sample != null) {
                        livenessWatchdog.meaningfulFrontierProgress(sample);
                        livenessWatchdog.restartAfterFrontier(sample);
                    }
                }
                return;
            }
            if (!clicks.isEmpty() || clicksComplete != null) {
                tickClicks(client);
                return;
            }
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            stageTicks++;
            switch (stage) {
                case WAIT_WORLD -> waitForFreshWorld(client);
                case FIND_LOOT -> findLoot(client);
                case NAVIGATE_LOOT -> navigateLoot(client);
                case LOOT_CHEST -> lootChest(client);
                case FIND_STARTER_RESOURCE -> findStarterResource(client);
                case NAVIGATE_STARTER_RESOURCE -> navigateStarterResource(client);
                case MINE_STARTER_RESOURCE -> mineStarterResource(client);
                case COLLECT_STARTER_RESOURCE -> collectStarterResource(client);
                case OPEN_INVENTORY -> openInventory(client);
                case CRAFT_PLANKS -> craftPlanks(client);
                case CRAFT_TABLE -> craftTable(client);
                case CRAFT_STICKS -> craftSticks(client);
                case CRAFT_WOOD_PICK -> craftWoodPick(client);
                case MOVE_TABLE_TO_HOTBAR -> moveTableToHotbar(client);
                case CLOSE_INVENTORY -> closeInventory(client);
                case PLACE_TABLE -> placeTable(client);
                case OPEN_TABLE -> openTable(client);
                case CRAFT_STONE_PICK -> craftStonePick(client);
                case CRAFT_FURNACE -> craftFurnace(client);
                case MOVE_WOOD_PICK_TO_HOTBAR -> moveWoodPickToHotbar(client);
                case MOVE_TOOL_TO_HOTBAR -> moveStonePickToHotbar(client);
                case MOVE_FURNACE_TO_HOTBAR -> moveFurnaceToHotbar(client);
                case CLOSE_TABLE -> closeTable(client);
                case EQUIP_WOOD_PICK -> equipTool(client, Items.WOODEN_PICKAXE);
                case EQUIP_STONE_PICK -> equipTool(client, Items.STONE_PICKAXE);
                case FIND_STONE -> findStone(client);
                case MINE_STONE -> mineStone(client);
                case PLAN_PREREQUISITE_ACQUISITION -> planPrerequisiteAcquisition(client);
                case EXCAVATE_PREREQUISITE_ROUTE -> excavatePrerequisiteRoute(client);
                case FIND_IRON -> findIron(client);
                case MINE_IRON -> mineIron(client);
                case FIND_GRAVEL -> findGravel(client);
                case MINE_GRAVEL -> mineGravel(client);
                case PLACE_FURNACE -> placeFurnace(client);
                case OPEN_FURNACE -> openFurnace(client);
                case SMELT_IRON -> smeltIron(client);
                case CLOSE_FURNACE -> closeFurnace(client);
                case CRAFT_BUCKET -> craftBucket(client);
                case CRAFT_FLINT_STEEL -> craftFlintSteel(client);
                case MOVE_BUCKET_TO_HOTBAR -> moveBucketToHotbar(client);
                case MOVE_FLINT_STEEL_TO_HOTBAR -> moveFlintSteelToHotbar(client);
                case FIND_WATER -> findWater(client);
                case FIND_LAVA -> findLava(client);
                case FILL_WATER_BUCKET -> fillWaterBucket(client);
                case FILL_LAVA_BUCKET -> fillLavaBucket(client);
                case BUILD_BUCKET_PORTAL -> buildBucketPortal(client);
                case CLEAR_PORTAL_SCAFFOLD -> clearPortalScaffold(client);
                case FIND_SITE -> findSite(client);
                case BUILD_PORTAL -> buildPortal(client);
                case LIGHT_PORTAL -> lightPortal(client);
                case ENTER_PORTAL -> enterPortal(client);
                case COMPLETE_DELAY -> complete(client);
            }
        } catch (Throwable failure) {
            releaseInput(client);
            finished = true;
            paused = false;
            result.completeExceptionally(failure);
        }
    }

    private void waitForFreshWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (stageTicks < 40) return;

        var player = client.player;
        survival = client.gameMode.getPlayerMode() == GameType.SURVIVAL;
        initialDimension = client.level.dimension().location().toString();
        worldGameTimeAtStart = client.level.getGameTime();
        freshWorld = survival && Level.OVERWORLD.location().toString().equals(initialDimension)
                && countNonEmpty(player) == 0 && worldGameTimeAtStart <= 6_000;
        var server = client.getSingleplayerServer();
        if (server != null && server.getWorldData() != null) worldName = server.getWorldData().getLevelName();
        if (!freshWorld) {
            throw new IllegalStateException("Nether goal requires a fresh empty overworld survival world; gameTime="
                    + worldGameTimeAtStart + ", dimension=" + initialDimension
                    + ", nonEmptySlots=" + countNonEmpty(player));
        }
        if (policy.allowCommands()) {
            throw new IllegalStateException("survival Nether workflow refuses allowCommands=true");
        }
        if (!policy.allowBlockBreaking() || !policy.allowBlockPlacing()) {
            throw new IllegalStateException("survival Nether workflow requires block breaking and placing permissions");
        }
        announce(client, "Fresh survival world ready; observing the natural ruined-portal route");
        transition(Stage.FIND_LOOT, 20);
    }

    private void findLoot(Minecraft client) {
        // The ruined-portal chest is an optional shortcut. Keep its observation local so a
        // missing chest cannot monopolize the client tick before the genuine wood route starts.
        lootChest = findNearestChest(client, 8);
        var action = NeoForgeOptionalLootPolicy.choose(lootChest != null);
        if (action == NeoForgeOptionalLootPolicy.Action.USE_OBSERVED_CHEST) {
            lootInteractionVantage = null;
            announce(client, "Observed a natural portal chest at " + lootChest + "; walking there normally");
            navigationDestination = null;
            transition(Stage.NAVIGATE_LOOT, 12);
            return;
        }
        searchAttempts++;
        inputActions.add("observe:no-local-natural-portal-chest");
        safetyDiagnostics.add("optional-loot:local-scope-exhausted:radius=8");
        stopMovement(client);
        resetNavigation();
        announce(client, "No local ruined-portal chest observed; starting genuine survival prerequisites");
        transition(Stage.FIND_STARTER_RESOURCE, 20);
    }

    private void navigateLoot(Minecraft client) {
        if (lootInteractionVantage == null) {
            lootInteractionVantage = findMiningVantage(client, lootChest);
            if (lootInteractionVantage == null) {
                safetyDiagnostics.add("loot-replan:reject-chest-without-safe-visible-vantage:" + lootChest);
                inputActions.add("observe:reject-occluded-natural-chest:" + lootChest);
                lootChest = null;
                searchAttempts++;
                resetNavigation();
                stopMovement(client);
                transition(Stage.FIND_STARTER_RESOURCE, 20);
                return;
            }
            resetNavigation();
            inputActions.add("observe:reachable-natural-chest-vantage:" + lootInteractionVantage);
        }
        if (navigateTo(client, lootInteractionVantage, MINING_VANTAGE_TOLERANCE,
                "natural chest interaction vantage")) {
            stopMovement(client);
            transition(Stage.LOOT_CHEST, 15);
        }
    }

    private void lootChest(Minecraft client) {
        var player = requirePlayer(client);
        if (client.screen instanceof ContainerScreen screen) {
            if (clicks.isEmpty() && clicksComplete == null) {
                var containerSlots = screen.getMenu().slots.size() - player.getInventory().getContainerSize();
                var sequence = new ArrayList<ClickOp>();
                for (int slot = 0; slot < containerSlots; slot++) {
                    if (!screen.getMenu().getSlot(slot).getItem().isEmpty()) {
                        sequence.add(new ClickOp(slot, 0, ClickType.QUICK_MOVE, "loot-portal-chest-slot-" + slot));
                    }
                }
                if (sequence.isEmpty()) throw new IllegalStateException("observed portal chest was empty");
                startClicks(sequence, () -> {
                    if (countItem(player, Items.FLINT_AND_STEEL) < 1
                            || countItem(player, Items.OBSIDIAN) < 1) {
                        throw new IllegalStateException("natural portal chest did not provide flint-and-steel and obsidian"
                                + "; flintAndSteel=" + countItem(player, Items.FLINT_AND_STEEL)
                                + ", obsidian=" + countItem(player, Items.OBSIDIAN));
                    }
                    closeContainer(client);
                    announce(client, "Looted portal materials through normal chest clicks; locating the existing frame");
                    transition(Stage.FIND_SITE, 20);
                });
            }
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening portal chest: "
                + client.screen.getClass().getName());
        lookAt(player, Vec3.atCenterOf(lootChest));
        var hit = player.pick(5.0F, 0.0F, false);
        var chestVisible = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(lootChest);
        if (chestVisible && stageTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-natural-portal-chest");
        }
        if (stageTicks > 180) {
            safetyDiagnostics.add("loot-replan:chest-interaction-did-not-open:" + lootChest);
            inputActions.add("observe:abandon-unopened-natural-chest:" + lootChest);
            lootChest = null;
            lootInteractionVantage = null;
            resetNavigation();
            stopMovement(client);
            transition(Stage.FIND_STARTER_RESOURCE, 20);
        }
    }

    private void findStarterResource(Minecraft client) {
        var player = requirePlayer(client);
        if (resourceRejectionAnchor != null
                && player.blockPosition().distManhattan(resourceRejectionAnchor) >= 4) {
            resourceRejectionAnchor = null;
            localResourceVantageRejections = 0;
        }
        if (localResourceVantageRejections >= 6) {
            exploreSafely(client, "starter-resource-vantage-rejection-escape");
            return;
        }
        // Chunk reads are authoritative but intentionally periodic. A full local resource scan is
        // hundreds of thousands of block reads; doing it every render tick starves the normal
        // movement input that is needed to discover the next chunk.
        if (stageTicks == 1 || stageTicks % 20 == 1) {
            var sources = scanStarterResourceSources(client);
            if (!sources.isEmpty()) {
                resourceSource = sources.getFirst();
                resourceMiningVantage = null;
                resourceApproachOnly = false;
                rejectedResourceVantages.clear();
                rejectedCollectibles.clear();
                collectibleTargetId = null;
                collectibleSupportBlock = null;
                handMinedLogs = 0;
                resetNavigation();
                announce(client, "Observed a starter wood source at " + resourceSource.anchor()
                        + " (" + resourceSource.provenance() + "); walking there to gather wood by hand");
                transition(Stage.NAVIGATE_STARTER_RESOURCE, 15);
                return;
            }
        }
        if (stageTicks == 1) {
            searchAttempts++;
            player.setYRot(player.getYRot() + 55.0F);
            player.setYHeadRot(player.getYRot());
            inputActions.add("look:visible-starter-resource-search-sweep");
        }
        exploreSafely(client, "starter-resource-search");
        if (stageTicks >= 140) {
            stopMovement(client);
            if (searchAttempts > MAX_SEARCH_ATTEMPTS) {
                throw new IllegalStateException("no starter wood source was observed after bounded survival exploration");
            }
            transition(Stage.FIND_STARTER_RESOURCE, 15);
        }
    }

    private void navigateStarterResource(Minecraft client) {
        if (verticalRecoveryGoal != null && verticalRecoveryGoal.equals(resourceSource.anchor())) {
            navigateTo(client, resourceSource.anchor(), 0.8, "vertical route recovery");
            return;
        }
        if (resourceMiningVantage == null) {
            resourceMiningVantage = findReachableResourceMiningVantage(client);
            if (resourceMiningVantage == null) {
                var approach = findResourceApproachWaypoint(client);
                if (approach != null) {
                    resourceMiningVantage = approach;
                    resourceApproachOnly = true;
                    resetNavigation();
                    inputActions.add("observe:reachable-starter-resource-approach-cell:" + approach);
                }
            }
            if (resourceMiningVantage == null) {
                if (beginVerticalRecovery(client, resourceSource.anchor(), "starter resource mining vantage")) {
                    navigateTo(client, resourceSource.anchor(), 0.8, "vertical route recovery");
                    return;
                }
                rejectedResourceSources.add(resourceSource.anchor().immutable());
                var player = requirePlayer(client);
                if (resourceRejectionAnchor == null
                        || player.blockPosition().distManhattan(resourceRejectionAnchor) >= 4) {
                    resourceRejectionAnchor = player.blockPosition().immutable();
                    localResourceVantageRejections = 0;
                }
                localResourceVantageRejections++;
                safetyDiagnostics.add("resource-replan:reject-unreachable-source:" + resourceSource.anchor());
                resourceSource = null;
                resetNavigation();
                stopMovement(client);
                transition(Stage.FIND_STARTER_RESOURCE, 15);
                return;
            }
            resetNavigation();
            inputActions.add("observe:reachable-starter-resource-mining-vantage:" + resourceMiningVantage);
        }
        boolean reachedMiningVantage;
        try {
            reachedMiningVantage = navigateTo(client, resourceMiningVantage, 0.8,
                    "starter resource mining vantage");
        } catch (IllegalStateException failure) {
            var message = String.valueOf(failure.getMessage());
            if (!message.startsWith("safe intelligent path unavailable before reaching ")
                    && !message.startsWith("safe intelligent navigation could not continue toward ")
                    && !message.startsWith("safe intelligent navigation remained blocked during ")) {
                throw failure;
            }
            safetyDiagnostics.add("resource-replan:reject-vantage-route:" + resourceMiningVantage);
            rejectedResourceVantages.add(resourceMiningVantage.asLong());
            resourceMiningVantage = null;
            resourceApproachOnly = false;
            resetNavigation();
            stopMovement(client);
            waitTicks = 2;
            return;
        }
        if (reachedMiningVantage) {
            stopMovement(client);
            if (resourceApproachOnly) {
                // A safe approach cell is progress, not yet permission to mine. Re-read the
                // loaded chunks from this new pose so foliage/terrain occlusion can be handled
                // by the ordinary vantage search rather than rejecting the whole source.
                resourceApproachOnly = false;
                resourceMiningVantage = null;
                resetNavigation();
                inputActions.add("observe:rescan-starter-resource-from-approach-cell");
                waitTicks = 3;
                return;
            }
            mineIndex = 0;
            resourceBlocksToMine = Math.max(1, LOGS_REQUIRED - countLogs(requirePlayer(client)));
            transition(Stage.MINE_STARTER_RESOURCE, 15);
        }
    }

    private void mineStarterResource(Minecraft client) {
        if (handMinedLogs >= resourceBlocksToMine || mineIndex >= resourceSource.blocks().size()) {
            stopAttack(client);
            transition(Stage.COLLECT_STARTER_RESOURCE, 0);
            return;
        }
        var player = requirePlayer(client);
        if (!player.getMainHandItem().isEmpty()) {
            var emptySlot = emptyHotbarSlot(player);
            if (emptySlot < 0) throw new IllegalStateException(
                    "hand-mining began with an item equipped and no empty hotbar slot was observed");
            selectHotbar(client, emptySlot);
            inputActions.add("select:empty-hotbar-slot-for-hand-mining");
            return;
        }
        var target = resourceSource.blocks().get(mineIndex);
        var state = client.level.getBlockState(target);
        if (!state.is(BlockTags.LOGS) && !state.isAir()) {
            stopAttack(client);
            throw new IllegalStateException("starter resource target is occupied by non-log block " + target
                    + ": " + state.getBlock().getName().getString());
        }
        if (state.isAir()) {
            stopAttack(client);
            handMinedLogs++;
            mineIndex++;
            stageTicks = 0;
            inputActions.add("read:block-broken-by-hand:" + target);
            waitTicks = 10;
            return;
        }
        lookAt(player, Vec3.atCenterOf(target));
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        if (!snapshot.safeMiningSite(player.blockPosition(), target,
                player.getEyePosition(), Vec3.atCenterOf(target))) {
            stopAttack(client);
            safetyDiagnostics.add("mining-hazard-gate:reject-starter-resource-target:" + target);
            mineIndex++;
            stageTicks = 0;
            waitTicks = 5;
            return;
        }
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(target)) {
            if (hit instanceof BlockHitResult blocker) {
                var blockerState = client.level.getBlockState(blocker.getBlockPos());
                if ((blockerState.is(BlockTags.LEAVES) || blockerState.is(BlockTags.LOGS))
                        && snapshot.safeMiningSite(player.blockPosition(), blocker.getBlockPos(),
                        player.getEyePosition(), Vec3.atCenterOf(blocker.getBlockPos()))) {
                    lookAt(player, Vec3.atCenterOf(blocker.getBlockPos()));
                    clickAndHoldAttack(client, "clear-resource-foliage");
                    inputActions.add("attack:key.attack-held-clear-resource-foliage");
                    if (stageTicks > 120) {
                        stopAttack(client);
                        safetyDiagnostics.add("mining-replan:skip-occluded-resource-target:" + target
                                + ":blocker=" + blocker.getBlockPos());
                        mineIndex++;
                        stageTicks = 0;
                        waitTicks = 5;
                    }
                    return;
                }
            }
            var vantage = findResourceMiningVantage(client, target);
            if (vantage != null) {
                stopAttack(client);
                var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(1.8, 0.8);
                if (!arrival.reached(player.getX(), player.getY(), player.getZ(), vantage)) {
                    try {
                        navigateTo(client, vantage, 1.8, "starter-resource-vantage");
                    } catch (IllegalStateException failure) {
                        var message = String.valueOf(failure.getMessage());
                        if (!message.startsWith("safe intelligent path unavailable before reaching ")
                                && !message.startsWith("safe intelligent navigation could not continue toward ")
                                && !message.startsWith("safe intelligent navigation remained blocked during ")) {
                            throw failure;
                        }
                        safetyDiagnostics.add("mining-replan:reject-stalled-resource-vantage:" + target
                                + ":vantage=" + vantage);
                        mineIndex++;
                        stageTicks = 0;
                        waitTicks = 5;
                    }
                    return;
                }
                // Reaching a legal vantage is not proof that the target is mineable. If
                // the ray is still occluded after a short bounded observation window,
                // reject this target and try another connected log/source rather than
                // looping forever at the same cell.
                if (stageTicks <= 60) return;
                safetyDiagnostics.add("mining-replan:reject-occluded-resource-target:" + target
                        + ":vantage=" + vantage);
                mineIndex++;
                stageTicks = 0;
                waitTicks = 5;
                return;
            }
            if (stageTicks > 180) {
                stopAttack(client);
                safetyDiagnostics.add("mining-replan:skip-unreachable-starter-resource:" + target);
                mineIndex++;
                stageTicks = 0;
                waitTicks = 5;
                return;
            }
        }
        clickAndHoldAttack(client, "hand");
        inputActions.add("attack:key.attack-held-by-hand");
        if (stageTicks > 240) {
            stopAttack(client);
            safetyDiagnostics.add("mining-replan:skip-stalled-starter-resource:" + target);
            mineIndex++;
            stageTicks = 0;
            waitTicks = 5;
        }
    }

    private BlockPos findResourceMiningVantage(Minecraft client, BlockPos target) {
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            for (int distance = 1; distance <= 3; distance++) {
                for (int yOffset = -1; yOffset <= 2; yOffset++) {
                    var candidate = target.relative(direction, distance).offset(0, yOffset, 0);
                    if (!snapshot.safeMiningSite(candidate, target,
                            new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                                    candidate.getZ() + 0.5), Vec3.atCenterOf(target))) continue;
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    var clip = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, requirePlayer(client)));
                    if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findReachableResourceMiningVantage(Minecraft client) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(0.8, 0.8);
            for (var target : resourceSource.blocks().stream().limit(32).toList()) {
            if (!client.level.getBlockState(target).is(BlockTags.LOGS)) continue;
            var candidates = new ArrayList<BlockPos>();
            for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
                for (int distance = 1; distance <= 3; distance++) {
                    for (int yOffset = -3; yOffset <= 4; yOffset++) {
                        var candidate = target.relative(direction, distance).offset(0, yOffset, 0);
                        if (rejectedResourceVantages.contains(candidate.asLong())) continue;
                        if (candidates.contains(candidate)) continue;
                        var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                                candidate.getZ() + 0.5);
                        if (!snapshot.safeMiningSite(candidate, target, eye, Vec3.atCenterOf(target))) continue;
                        var clip = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                        if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
                            candidates.add(candidate.immutable());
                        }
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(candidate -> player.distanceToSqr(Vec3.atCenterOf(candidate))));
            BlockPos best = null;
            var bestPathLength = Integer.MAX_VALUE;
            for (var candidate : candidates.stream().limit(24).toList()) {
                var path = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), candidate,
                        policy, arrival);
                var retreat = NeoForgeSafePathPlanner.find(client.level, candidate,
                        player.blockPosition(), policy, arrival);
                if (snapshot.safeMiningPath(path) && snapshot.safeMiningPath(retreat)
                        && path.size() < bestPathLength) {
                    best = candidate;
                    bestPathLength = path.size();
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * Find a safe, reversible approach cell near an observed generic log source when the
     * current pose cannot yet see a legal mining ray. This is deliberately source-agnostic:
     * the same recovery applies to a natural trunk, a village house, or any connected logs.
     */
    private BlockPos findResourceApproachWaypoint(Minecraft client) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(0.8, 0.8);
        BlockPos best = null;
        var bestScore = Double.POSITIVE_INFINITY;
        for (var target : resourceSource.blocks().stream().limit(32).toList()) {
            for (var direction : Direction.Plane.HORIZONTAL) {
                for (int distance = 2; distance <= 8; distance++) {
                    for (int yOffset = -6; yOffset <= 6; yOffset++) {
                        var candidate = target.relative(direction, distance).offset(0, yOffset, 0);
                        if (rejectedResourceVantages.contains(candidate.asLong())) continue;
                        if (candidate.distSqr(player.blockPosition()) < 9.0
                                || !snapshot.bufferedWalkable(candidate)) continue;
                        var path = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), candidate,
                                policy, arrival);
                        var retreat = NeoForgeSafePathPlanner.find(client.level, candidate,
                                player.blockPosition(), policy, arrival);
                        if (!snapshot.safeMiningPath(path) || !snapshot.safeMiningPath(retreat)) continue;
                        var score = path.size() + player.distanceToSqr(Vec3.atCenterOf(candidate)) * 0.05;
                        if (score < bestScore) {
                            best = candidate.immutable();
                            bestScore = score;
                        }
                    }
                }
            }
        }
        return best;
    }

    private MiningTargetPreparation prepareMiningTarget(Minecraft client, BlockPos target, String label) {
        var player = requirePlayer(client);
        if (verticalRecoveryGoal != null && verticalRecoveryGoal.equals(target)) {
            return navigateMiningRoute(client, target, "vertical route recovery:" + label)
                    ? MiningTargetPreparation.MOVING : MiningTargetPreparation.UNREACHABLE;
        }
        if (!target.equals(miningVantageTarget)) {
            miningVantageTarget = target.immutable();
            miningVantage = null;
        }
        lookAt(player, Vec3.atCenterOf(target));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
            var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
            if (snapshot.safeMiningSite(player.blockPosition(), target,
                    player.getEyePosition(), Vec3.atCenterOf(target))) {
                return MiningTargetPreparation.READY;
            }
            stopAttack(client);
            safetyDiagnostics.add("mining-hazard-gate:reject-ready-target:" + target);
            return MiningTargetPreparation.UNREACHABLE;
        }
        if (miningVantage == null) miningVantage = findMiningVantage(client, target);
        if (miningVantage == null) {
            if (beginVerticalRecovery(client, target, "mining-vantage:" + label)) {
                return navigateMiningRoute(client, target, "vertical route recovery:" + label)
                        ? MiningTargetPreparation.MOVING : MiningTargetPreparation.UNREACHABLE;
            }
            return MiningTargetPreparation.UNREACHABLE;
        }
        if (!navigateMiningRoute(client, miningVantage, "mining-vantage:" + label)) {
            return MiningTargetPreparation.UNREACHABLE;
        }
        if (!new NeoForgeSafePathPlanner.ArrivalSpec(MINING_VANTAGE_TOLERANCE, 0.8)
                .reached(player.getX(), player.getY(), player.getZ(), miningVantage)) {
            return MiningTargetPreparation.MOVING;
        }
        lookAt(player, Vec3.atCenterOf(target));
        if (player.pick(5.0F, 0.0F, false) instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(target)) {
            return MiningTargetPreparation.READY;
        }
        clearMiningVantage();
        return MiningTargetPreparation.UNREACHABLE;
    }

    /** Convert only typed safe-route exhaustion into mining-target rejection and re-scan. */
    private boolean navigateMiningRoute(Minecraft client, BlockPos destination, String label) {
        try {
            navigateTo(client, destination, MINING_VANTAGE_TOLERANCE, label);
            return true;
        } catch (IllegalStateException failure) {
            var message = String.valueOf(failure.getMessage());
            if (!message.startsWith("safe intelligent path unavailable before reaching ")
                    && !message.startsWith("safe intelligent navigation could not continue toward ")
                    && !message.startsWith("safe intelligent navigation remained blocked during ")) throw failure;
            safetyDiagnostics.add("mining-replan:typed-route-unreachable:" + label + ":" + destination);
            stopMovement(client);
            resetNavigation();
            return false;
        }
    }

    private void clearMiningVantage() {
        miningVantageTarget = null;
        miningVantage = null;
    }

    private BlockPos findMiningVantage(Minecraft client, BlockPos target) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(MINING_VANTAGE_TOLERANCE, 0.8);
        var candidates = new ArrayList<BlockPos>();
        for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            for (int distance = 1; distance <= 3; distance++) {
                for (int yOffset = -1; yOffset <= 2; yOffset++) {
                    var candidate = target.relative(direction, distance).offset(0, yOffset, 0);
                    if (candidates.contains(candidate)) continue;
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    if (!snapshot.safeMiningSite(candidate, target, eye, Vec3.atCenterOf(target))) continue;
                    var clip = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> player.distanceToSqr(Vec3.atCenterOf(candidate))));
        BlockPos best = null;
        var bestPathLength = Integer.MAX_VALUE;
        for (var candidate : candidates.stream().limit(24).toList()) {
            var path = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), candidate, policy, arrival);
            var retreat = NeoForgeSafePathPlanner.find(client.level, candidate,
                    player.blockPosition(), policy, arrival);
            if (snapshot.safeMiningPath(path) && snapshot.safeMiningPath(retreat)
                    && path.size() < bestPathLength) {
                best = candidate;
                bestPathLength = path.size();
            }
        }
        return best;
    }

    private void beginMiningSearch(Minecraft client, String kind, List<BlockPos> candidates) {
        miningSearchKind = kind;
        miningTargetSearch.begin(candidates);
        miningSearchTelemetry = null;
        safetyDiagnostics.add("mining-search:local-search:" + kind + ":candidates=" + candidates.size());
        inputActions.add("observe:mining-candidate-batch:" + kind);
    }

    private NeoForgeMiningTargetSearch.BatchResult pollMiningSearch(Minecraft client) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        miningSearchTelemetry = miningTargetSearch.poll(new NeoForgeMiningTargetSearch.Validator() {
            @Override
            public long worldRevision(BlockPos candidate) {
                return localWorldRevision(client, candidate, 1);
            }

            @Override
            public long poseRevision() {
                return player.blockPosition().asLong();
            }

            @Override
            public String prefilter(BlockPos candidate) {
                return snapshot.breakExposureSafe(candidate) ? null : "HAZARD_PREFILTER";
            }

            @Override
            public NeoForgeMiningTargetSearch.Validation validate(BlockPos candidate) {
                var vantage = findMiningVantage(client, candidate);
                return vantage == null
                        ? NeoForgeMiningTargetSearch.Validation.rejected("NO_SAFE_VANTAGE_OR_RETREAT")
                        : NeoForgeMiningTargetSearch.Validation.safe(vantage);
            }
        }, MINING_SEARCH_WORK_BUDGET);
        if (miningSearchTelemetry.outcome() != NeoForgeMiningTargetSearch.Outcome.SEARCH_INCOMPLETE) {
            safetyDiagnostics.add("mining-search:outcome=" + miningSearchTelemetry.outcome()
                    + ":kind=" + miningSearchKind + ":cursor=" + miningSearchTelemetry.cursor()
                    + "/" + miningSearchTelemetry.candidateCount()
                    + ":prefilterRejected=" + miningSearchTelemetry.prefilterRejected()
                    + ":validated=" + miningSearchTelemetry.validated()
                    + ":cacheHits=" + miningSearchTelemetry.cacheHits()
                    + ":rejections=" + miningSearchTelemetry.rejectionHistogram());
        }
        return miningSearchTelemetry;
    }

    private void collectStarterResource(Minecraft client) {
        var player = requirePlayer(client);
        if (countLogs(player) == 0 && handMinedLogs == 0
                && mineIndex >= Math.min(LOGS_REQUIRED, resourceSource.blocks().size())) {
            safetyDiagnostics.add("mining-replan:resource-source-produced-no-logs:"
                    + resourceSource.anchor());
            stopMovement(client);
            resourceSource = null;
            resourceApproachOnly = false;
            transition(Stage.FIND_STARTER_RESOURCE, 15);
            return;
        }
        if (countLogs(player) >= LOGS_REQUIRED) {
            stopMovement(client);
            announce(client, "Collected hand-mined wood; opening the inventory crafting grid");
            transition(Stage.OPEN_INVENTORY, 20);
            return;
        }
        collectEquivalentResource(client, NeoForgeNetherGoal::isLogStack);
        if (stageTicks > 700) {
            abandonStarterResource(client, "collectible-recovery-timeout");
        }
    }

    private void collectEquivalentResource(Minecraft client, Predicate<ItemStack> equivalent) {
        var player = requirePlayer(client);
        var drops = client.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        player.getBoundingBox().inflate(18.0), entity -> entity.isAlive()
                                && equivalent.test(entity.getItem()) && !rejectedCollectibles.contains(entity.getUUID()))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        var target = drops.stream().filter(entity -> entity.getUUID().equals(collectibleTargetId))
                .findFirst().orElse(drops.isEmpty() ? null : drops.getFirst());
        if (target == null) {
            if (collectibleSupportBlock != null && clearCollectibleSupport(client)) return;
            stopMovement(client);
            if (stageTicks > 100) abandonStarterResource(client, "no-equivalent-drop-observed");
            return;
        }
        collectibleTargetId = target.getUUID();
        var vantage = findCollectibleVantage(client, target.position());
        var blocker = visibleCollectibleBlocker(client, target.position());
        var support = findCollectibleSupportPlacement(client, target.position());
        var alternative = NeoForgeCollectibleRecovery.choose(new NeoForgeCollectibleRecovery.Options(
                vantage != null, blocker != null, support != null, drops.size() > 1));
        switch (alternative) {
            case SAFE_VANTAGE -> {
                stopAttack(client);
                if (navigateTo(client, vantage, 0.55, "equivalent-collectible-safe-vantage")) {
                    lookAt(player, target.position());
                    stopMovement(client);
                    client.options.keyJump.setDown(player.onGround() && target.getY() > player.getY() + 0.7);
                }
                inputActions.add("move:equivalent-collectible-safe-vantage:" + target.getUUID());
            }
            case CLEAR_BREAKABLE_BLOCKER -> {
                stopMovement(client);
                lookAt(player, Vec3.atCenterOf(blocker));
                clickAndHoldAttack(client, "clear-equivalent-collectible-obstruction");
                inputActions.add("attack:clear-legal-collectible-obstruction:" + blocker);
            }
            case PLACE_SUPPORT -> placeCollectibleSupport(client, support);
            case RETARGET_EQUIVALENT -> {
                stopMovement(client);
                stopAttack(client);
                rejectedCollectibles.add(target.getUUID());
                collectibleTargetId = null;
                resetNavigation();
                stageTicks = Math.min(stageTicks, 500);
                safetyDiagnostics.add("collectible-replan:retarget-equivalent:" + target.getUUID());
            }
            case EXHAUSTED -> abandonStarterResource(client, "collectible-local-scope-exhausted");
        }
    }

    private BlockPos findCollectibleVantage(Minecraft client, Vec3 collectible) {
        var player = requirePlayer(client);
        var target = BlockPos.containing(collectible);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(0.55, 0.8);
        BlockPos best = null;
        var bestLength = Integer.MAX_VALUE;
        for (var dx = -3; dx <= 3; dx++) {
            for (var dz = -3; dz <= 3; dz++) {
                for (var dy = -3; dy <= 1; dy++) {
                    var candidate = target.offset(dx, dy, dz);
                    var pickupPoint = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.0,
                            candidate.getZ() + 0.5);
                    if (pickupPoint.distanceTo(collectible) > 1.65 || !snapshot.bufferedWalkable(candidate)) continue;
                    var path = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), candidate,
                            policy, arrival);
                    var retreat = NeoForgeSafePathPlanner.find(client.level, candidate,
                            player.blockPosition(), policy, arrival);
                    if (snapshot.safeMiningPath(path) && snapshot.safeMiningPath(retreat)
                            && path.size() < bestLength) {
                        best = candidate.immutable();
                        bestLength = path.size();
                    }
                }
            }
        }
        return best;
    }

    private BlockPos visibleCollectibleBlocker(Minecraft client, Vec3 collectible) {
        var player = requirePlayer(client);
        var hit = client.level.clip(new ClipContext(player.getEyePosition(), collectible,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (!(hit instanceof BlockHitResult blockHit)) return null;
        var blocker = blockHit.getBlockPos();
        if (blocker.equals(BlockPos.containing(collectible))) return null;
        var state = client.level.getBlockState(blocker);
        if (state.isAir()) return null;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var legal = state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.getDestroySpeed(client.level, blocker) >= 0.0F
                && state.getDestroySpeed(client.level, blocker) <= 0.6F
                && !state.is(BlockTags.MINEABLE_WITH_PICKAXE);
        if (!legal || !state.getFluidState().isEmpty() || snapshot.hazard(blocker)) return null;
        return snapshot.safeMiningSite(player.blockPosition(), blocker,
                player.getEyePosition(), Vec3.atCenterOf(blocker)) ? blocker.immutable() : null;
    }

    private Placement findCollectibleSupportPlacement(Minecraft client, Vec3 collectible) {
        if (findSafeSupportBlockSlot(requirePlayer(client)) < 0) return null;
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var item = BlockPos.containing(collectible);
        for (var dy = -2; dy <= 0; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                for (var dz = -1; dz <= 1; dz++) {
                    var target = item.offset(dx, dy, dz);
                    var support = target.below();
                    if (!client.level.getBlockState(target).canBeReplaced()
                            || !client.level.getFluidState(target).isEmpty()
                            || client.level.getBlockState(support).getCollisionShape(client.level, support).isEmpty()
                            || !snapshot.safePlacementSite(target)
                            || player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) continue;
                    var aim = facePoint(support, Direction.UP);
                    if (player.getEyePosition().distanceTo(aim) > 4.5) continue;
                    var clip = client.level.clip(new ClipContext(player.getEyePosition(), aim,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                            && blockHit.getDirection() == Direction.UP) return new Placement(target, support);
                }
            }
        }
        return null;
    }

    private void placeCollectibleSupport(Minecraft client, Placement placement) {
        var player = requirePlayer(client);
        var slot = findSafeSupportBlockSlot(player);
        if (slot < 0 || !NeoForgeWorldSnapshot.capture(client.level, policy)
                .safePlacementSite(placement.target())) return;
        stopMovement(client);
        stopAttack(client);
        selectHotbar(client, slot);
        lookAt(player, facePoint(placement.support(), Direction.UP));
        if (stageTicks % 12 == 1) {
            collectibleSupportBlock = placement.target().immutable();
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:place-safe-collectible-support:" + placement.target());
        }
    }

    private int findSafeSupportBlockSlot(LocalPlayer player) {
        for (var slot = 0; slot < 9; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof BlockItem) || stack.is(Items.CRAFTING_TABLE)
                    || stack.is(Items.FURNACE) || stack.is(Items.OBSIDIAN)) continue;
            return slot;
        }
        return -1;
    }

    /** Recover a drop that landed on foliage/terrain by dismantling the temporary normal-input support. */
    private boolean clearCollectibleSupport(Minecraft client) {
        var support = collectibleSupportBlock;
        if (support == null) return false;
        var player = requirePlayer(client);
        var state = client.level.getBlockState(support);
        if (state.isAir()) {
            collectibleSupportBlock = null;
            inputActions.add("observe:collectible-support-cleared");
            return false;
        }
        if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.PLANKS)) {
            collectibleSupportBlock = null;
            return false;
        }
        var supportItem = state.getBlock().asItem();
        if (!player.getMainHandItem().is(supportItem)) {
            var slot = hotbarSlot(player, supportItem);
            if (slot < 0) return false;
            selectHotbar(client, slot);
            return true;
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        lookAt(player, Vec3.atCenterOf(support));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                && snapshot.safeMiningSite(player.blockPosition(), support,
                player.getEyePosition(), Vec3.atCenterOf(support))) {
            clickAndHoldAttack(client, "clear-temporary-collectible-support");
            inputActions.add("attack:clear-temporary-collectible-support:" + support);
            return true;
        }
        var vantage = findResourceMiningVantage(client, support);
        if (vantage != null && !new NeoForgeSafePathPlanner.ArrivalSpec(1.8, 0.8)
                .reached(player.getX(), player.getY(), player.getZ(), vantage)) {
            try {
                navigateTo(client, vantage, 1.8, "temporary-collectible-support-vantage");
                inputActions.add("move:temporary-collectible-support-vantage:" + vantage);
            } catch (IllegalStateException failure) {
                var message = String.valueOf(failure.getMessage());
                if (!message.startsWith("safe intelligent path unavailable before reaching ")
                        && !message.startsWith("safe intelligent navigation could not continue toward ")
                        && !message.startsWith("safe intelligent navigation remained blocked during ")) {
                    throw failure;
                }
                collectibleSupportBlock = null;
                safetyDiagnostics.add("collectible-replan:temporary-support-vantage-unreachable:" + vantage);
                return false;
            }
        }
        return true;
    }

    private void abandonStarterResource(Minecraft client, String reason) {
        stopMovement(client);
        stopAttack(client);
        if (resourceSource != null) rejectedResourceSources.add(resourceSource.anchor().immutable());
        safetyDiagnostics.add("resource-replan:" + reason + ":inventory="
                + countLogs(requirePlayer(client)) + ":required=" + LOGS_REQUIRED
                + ":source=" + (resourceSource == null ? "none" : resourceSource.anchor()));
        resourceSource = null;
        resourceMiningVantage = null;
        resourceApproachOnly = false;
        collectibleTargetId = null;
        collectibleSupportBlock = null;
        rejectedCollectibles.clear();
        handMinedLogs = 0;
        mineIndex = 0;
        resetNavigation();
        transition(Stage.FIND_STARTER_RESOURCE, 15);
    }

    private void openInventory(Minecraft client) {
        if (client.screen instanceof InventoryScreen) {
            transition(Stage.CRAFT_PLANKS, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen before survival crafting: "
                + client.screen.getClass().getName());
        KeyMapping.click(client.options.keyInventory.getKey());
        inputActions.add("ui:key.inventory");
        if (stageTicks > 100) throw new IllegalStateException("inventory did not open through normal key input");
    }

    private void craftPlanks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var logs = findPlayerItemSlot(screen, NeoForgeNetherGoal::isLogStack);
            if (logs < 0) throw new IllegalStateException("hand-mined logs missing from inventory");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(logs, 0, ClickType.PICKUP, "pick-up-hand-mined-logs"),
                    new ClickOp(1, 0, ClickType.PICKUP, "place-logs-in-inventory-grid"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-planks")), () -> {
                if (countPlanks(requirePlayer(client)) < 12) {
                    throw new IllegalStateException("visible plank recipe produced insufficient planks");
                }
                transition(Stage.CRAFT_TABLE, 12);
            });
        }
    }

    private void craftTable(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            if (planks < 0) throw new IllegalStateException("planks missing before crafting table");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-table"),
                    new ClickOp(1, 1, ClickType.PICKUP, "crafting-table-grid-1"),
                    new ClickOp(2, 1, ClickType.PICKUP, "crafting-table-grid-2"),
                    new ClickOp(3, 1, ClickType.PICKUP, "crafting-table-grid-3"),
                    new ClickOp(4, 1, ClickType.PICKUP, "crafting-table-grid-4"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-table-planks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafting-table")), () -> {
                if (countItem(requirePlayer(client), Items.CRAFTING_TABLE) < 1) {
                    throw new IllegalStateException("visible crafting-table recipe did not produce a table");
                }
                transition(Stage.CRAFT_STICKS, 12);
            });
        }
    }

    private void craftSticks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            if (planks < 0) throw new IllegalStateException("planks missing before crafting sticks");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-sticks"),
                    new ClickOp(1, 1, ClickType.PICKUP, "stick-grid-top"),
                    new ClickOp(3, 1, ClickType.PICKUP, "stick-grid-bottom"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-stick-planks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-sticks")), () -> {
                if (countItem(requirePlayer(client), Items.STICK) < 4) {
                    throw new IllegalStateException("visible stick recipe did not produce sticks");
                }
                transition(Stage.MOVE_TABLE_TO_HOTBAR, 12);
            });
        }
    }

    private void craftWoodPick(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (woodPickVerificationPending) {
            if (countItem(requirePlayer(client), Items.WOODEN_PICKAXE) >= 1) {
                woodPickVerificationPending = false;
                afterTableClose = Stage.EQUIP_WOOD_PICK;
                transition(Stage.MOVE_WOOD_PICK_TO_HOTBAR, 15);
                return;
            }
            if (stageTicks <= 20) return;
            woodPickVerificationPending = false;
            if (++woodPickAttempts <= 2) {
                clearCraftingGrid(client);
                recipeStarted = false;
                stageTicks = 0;
                waitTicks = 8;
                inputActions.add("craft:retry-wooden-pickaxe-after-container-sync");
                return;
            }
            throw new IllegalStateException("visible wooden-pick recipe did not produce a pickaxe");
        }
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            var sticks = findPlayerItemSlot(screen, stack -> stack.is(Items.STICK));
            if (planks < 0 || sticks < 0) throw new IllegalStateException("wooden pick ingredients missing");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-wood-pick"),
                    new ClickOp(1, 1, ClickType.PICKUP, "wood-pick-top-left"),
                    new ClickOp(2, 1, ClickType.PICKUP, "wood-pick-top-middle"),
                    new ClickOp(3, 1, ClickType.PICKUP, "wood-pick-top-right"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-pick-planks"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "pick-up-sticks-for-wood-pick"),
                    new ClickOp(5, 1, ClickType.PICKUP, "wood-pick-stick-middle"),
                    new ClickOp(8, 1, ClickType.PICKUP, "wood-pick-stick-bottom"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "return-unused-pick-sticks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-wooden-pickaxe")), () -> {
                woodPickVerificationPending = true;
                stageTicks = 0;
                recipeStarted = false;
            });
        }
    }

    private void clearCraftingGrid(Minecraft client) {
        if (client.gameMode == null) throw new IllegalStateException("game mode unavailable while clearing recipe grid");
        var screen = requireContainer(client, CraftingScreen.class);
        for (int slot = 1; slot <= 9; slot++) {
            if (!screen.getMenu().getSlot(slot).getItem().isEmpty()) {
                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, 0,
                        ClickType.QUICK_MOVE, requirePlayer(client));
                inputActions.add("container-click:clear-wood-pick-grid-" + slot);
            }
        }
    }

    private void moveTableToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.CRAFTING_TABLE, () -> {
            afterTableClose = Stage.PLACE_TABLE;
            transition(Stage.CLOSE_INVENTORY, 12);
        });
    }

    private void moveWoodPickToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.WOODEN_PICKAXE, () -> {
            afterTableClose = Stage.EQUIP_WOOD_PICK;
            transition(Stage.CLOSE_TABLE, 12);
        });
    }

    private void moveStonePickToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.STONE_PICKAXE, () -> {
            afterTableClose = Stage.EQUIP_STONE_PICK;
            transition(Stage.MOVE_FURNACE_TO_HOTBAR, 12);
        });
    }

    private void moveFurnaceToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.FURNACE, () -> {
            afterTableClose = Stage.EQUIP_STONE_PICK;
            transition(Stage.CLOSE_TABLE, 12);
        });
    }

    private void moveBucketToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.BUCKET, () -> transition(Stage.MOVE_FLINT_STEEL_TO_HOTBAR, 12));
    }

    private void moveFlintSteelToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.FLINT_AND_STEEL, () -> {
            afterTableClose = Stage.FIND_WATER;
            transition(Stage.CLOSE_TABLE, 12);
            pauseAtCheckpoint(client, "portal-tools");
        });
    }

    private void moveCraftedToHotbar(Minecraft client, Item item, Runnable complete) {
        var container = requireAnyContainer(client);
        var player = requirePlayer(client);
        var hotbar = hotbarSlot(player, item);
        if (hotbar >= 0) {
            if (item == Items.CRAFTING_TABLE) tableHotbarSlot = hotbar;
            if (item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE) toolHotbarSlot = hotbar;
            if (item == Items.FURNACE) furnaceHotbarSlot = hotbar;
            if (item == Items.BUCKET) bucketHotbarSlot = hotbar;
            if (item == Items.FLINT_AND_STEEL) flintSteelHotbarSlot = hotbar;
            complete.run();
            return;
        }
        if (!recipeStarted) {
            var source = findPlayerItemSlot(container, stack -> stack.is(item));
            if (source < 0) throw new IllegalStateException("crafted item missing before hotbar transfer: " + item);
            recipeStarted = true;
            startClicks(List.of(new ClickOp(source, 0, ClickType.QUICK_MOVE,
                    "move-crafted-" + item.toString().replace(':', '-') + "-to-hotbar")), () -> {
                if (hotbarSlot(requirePlayer(client), item) < 0) {
                    throw new IllegalStateException("normal quick-move did not place item in hotbar: " + item);
                }
                recipeStarted = false;
                complete.run();
            });
        }
    }

    private void closeInventory(Minecraft client) {
        if (client.screen == null) {
            tablePosition = findTablePlacement(client);
            transition(Stage.PLACE_TABLE, 12);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-inventory");
        if (stageTicks > 100) throw new IllegalStateException("inventory did not close");
    }

    private void placeTable(Minecraft client) {
        var player = requirePlayer(client);
        if (client.level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
            nextTableStage = Stage.CRAFT_WOOD_PICK;
            transition(Stage.OPEN_TABLE, 15);
            return;
        }
        selectHotbar(client, tableHotbarSlot);
        var support = tablePosition.below();
        if (!client.level.noCollision(player, player.getBoundingBox())) {
            if (stageTicks > 120) throw new IllegalStateException("player lost a standable position before table placement");
            return;
        }
        lookAt(player, facePoint(support, Direction.UP));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                && blockHit.getDirection() == Direction.UP && stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-place-crafting-table");
        }
        if (stageTicks > 240) throw new IllegalStateException("normal table placement did not complete");
    }

    private void openTable(Minecraft client) {
        if (client.screen instanceof CraftingScreen) {
            tableInteractionVantage = requirePlayer(client).blockPosition().immutable();
            tableInteractionPositionReady = false;
            transition(nextTableStage, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening crafting table");
        if (!client.level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
            throw new IllegalStateException("placed crafting table is no longer present");
        }
        if (!tableInteractionPositionReady) {
            if (tableInteractionVantage == null) {
                // The first interaction occurs immediately after placement from the already
                // ray-validated player cell. Cache it only after the screen actually opens.
                tableInteractionPositionReady = true;
                stageTicks = 0;
            } else {
                var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
                if (!snapshot.walkable(tableInteractionVantage)) {
                    tableInteractionVantage = findMiningVantage(client, tablePosition);
                    if (tableInteractionVantage == null) {
                        throw new IllegalStateException("no reachable high-safety crafting-table interaction vantage");
                    }
                }
                if (!navigateTo(client, tableInteractionVantage, 0.45, "crafting-table interaction")) return;
                tableInteractionPositionReady = true;
                stageTicks = 0;
            }
        }
        lookAt(requirePlayer(client), Vec3.atCenterOf(tablePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-crafting-table");
        }
        if (stageTicks > 120) throw new IllegalStateException("normal table use did not open crafting screen");
    }

    private void craftStonePick(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var cobble = findPlayerItemSlot(screen, stack -> stack.is(Items.COBBLESTONE));
            var sticks = findPlayerItemSlot(screen, stack -> stack.is(Items.STICK));
            if (cobble < 0 || sticks < 0) throw new IllegalStateException("stone-pick ingredients missing");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(cobble, 0, ClickType.PICKUP, "pick-up-cobblestone-for-stone-pick"),
                    new ClickOp(1, 1, ClickType.PICKUP, "stone-pick-top-left"),
                    new ClickOp(2, 1, ClickType.PICKUP, "stone-pick-top-middle"),
                    new ClickOp(3, 1, ClickType.PICKUP, "stone-pick-top-right"),
                    new ClickOp(cobble, 0, ClickType.PICKUP, "return-unused-stone-pick-cobble"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "pick-up-sticks-for-stone-pick"),
                    new ClickOp(5, 1, ClickType.PICKUP, "stone-pick-stick-middle"),
                    new ClickOp(8, 1, ClickType.PICKUP, "stone-pick-stick-bottom"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "return-unused-stone-pick-sticks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-stone-pickaxe")), () -> {
                if (countItem(requirePlayer(client), Items.STONE_PICKAXE) < 1) {
                    throw new IllegalStateException("visible stone-pick recipe did not produce a pickaxe");
                }
                transition(Stage.CRAFT_FURNACE, 12);
            });
        }
    }

    private void craftFurnace(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var cobble = findPlayerItemSlot(screen, stack -> stack.is(Items.COBBLESTONE));
            if (cobble < 0 || countItem(requirePlayer(client), Items.COBBLESTONE) < 8) {
                throw new IllegalStateException("eight cobblestone required for furnace");
            }
            recipeStarted = true;
            var sequence = new ArrayList<ClickOp>();
            sequence.add(new ClickOp(cobble, 0, ClickType.PICKUP, "pick-up-cobblestone-for-furnace"));
            for (var slot : List.of(1, 2, 3, 4, 6, 7, 8, 9)) {
                sequence.add(new ClickOp(slot, 1, ClickType.PICKUP, "place-furnace-cobble-" + slot));
            }
            sequence.add(new ClickOp(cobble, 0, ClickType.PICKUP, "return-unused-furnace-cobble"));
            sequence.add(new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-furnace"));
            startClicks(sequence, () -> {
                if (countItem(requirePlayer(client), Items.FURNACE) < 1) {
                    throw new IllegalStateException("visible furnace recipe did not produce a furnace");
                }
                transition(Stage.MOVE_TOOL_TO_HOTBAR, 12);
            });
        }
    }

    private void closeTable(Minecraft client) {
        if (client.screen == null) {
            transition(afterTableClose, 12);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-crafting-table");
        if (stageTicks > 100) throw new IllegalStateException("crafting table did not close");
    }

    private void equipTool(Minecraft client, Item item) {
        var slot = hotbarSlot(requirePlayer(client), item);
        if (slot < 0) throw new IllegalStateException("tool missing from hotbar: " + item);
        toolHotbarSlot = slot;
        selectHotbar(client, slot);
        if (requirePlayer(client).getMainHandItem().is(item)) {
            if (item == Items.WOODEN_PICKAXE) {
                transition(Stage.FIND_STONE, 15);
                pauseAtCheckpoint(client, "starter-tools");
            } else {
                transition(Stage.FIND_IRON, 15);
            }
            return;
        }
        if (stageTicks > 80) throw new IllegalStateException("normal hotbar input did not equip " + item);
    }

    private void findStone(Minecraft client) {
        requirePrerequisiteTool(client, Items.WOODEN_PICKAXE, "stone mining");
        clearMiningVantage();
        if (exhaustedStoneScanOrigin != null) {
            if (requirePlayer(client).blockPosition().distSqr(exhaustedStoneScanOrigin) < 36.0) {
                exploreForObservation(client, "new stone exposure after exhausted scan");
                return;
            }
            exhaustedStoneScanOrigin = null;
        }
        if (!"stone".equals(miningSearchKind)) {
            if ("none".equals(prerequisiteAcquisitionStrategy)) {
                prerequisiteAcquisitionStrategy = "prerequisite-acquisition:safe-exposed-search";
                prerequisiteAcquisitionReason = "observed-surface-scope-available:stone";
            }
            beginMiningSearch(client, "stone",
                    scanVisibleBlocks(client, state -> state.is(Blocks.STONE), 28, 20).stream()
                            .limit(256).toList());
        }
        var search = pollMiningSearch(client);
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.SAFE_TARGET) {
            observedStone = List.of(search.target());
            stoneMineIndex = 0;
            miningVantageTarget = search.target();
            miningVantage = search.vantage();
            transition(Stage.MINE_STONE, 12);
            return;
        }
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.LOCAL_SCOPE_EXHAUSTED) {
            exhaustedStoneScanOrigin = requirePlayer(client).blockPosition().immutable();
            miningSearchKind = "";
            observedStone = List.of();
            beginPrerequisiteAcquisition(client, stonePrerequisite(), search);
        }
    }

    private void mineStone(Minecraft client) {
        requireEquippedTool(client, Items.WOODEN_PICKAXE, "stone mining");
        if (countItem(requirePlayer(client), Items.COBBLESTONE) >= COBBLESTONE_REQUIRED) {
            stopAttack(client);
            nextTableStage = Stage.CRAFT_STONE_PICK;
            resetRecipeState();
            transition(Stage.OPEN_TABLE, 15);
            return;
        }
        if (stoneMineIndex >= observedStone.size()) {
            exhaustedStoneScanOrigin = requirePlayer(client).blockPosition().immutable();
            observedStone = List.of();
            transition(Stage.FIND_STONE, 3);
            return;
        }
        var target = observedStone.get(stoneMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.STONE)) {
            stopAttack(client);
            cobblestoneMined++;
            observedStone = List.of();
            clearMiningVantage();
            transition(Stage.FIND_STONE, 8);
            return;
        }
        var preparation = prepareMiningTarget(client, target, "stone");
        if (preparation != MiningTargetPreparation.READY) {
            if (preparation == MiningTargetPreparation.UNREACHABLE || stageTicks > 90) {
                stopAttack(client);
                safetyDiagnostics.add("mining-replan:skip-"
                        + (preparation == MiningTargetPreparation.UNREACHABLE ? "unreachable" : "movement-timeout")
                        + "-stone:" + target);
                observedStone = List.of();
                clearMiningVantage();
                transition(Stage.FIND_STONE, 1);
            }
            return;
        }
        clickAndHoldAttack(client, "wooden-pickaxe");
        inputActions.add("attack:key.attack-held-with-wooden-pickaxe");
        if (stageTicks > 420) throw new IllegalStateException("wooden pickaxe failed to mine observed stone");
    }

    private PrerequisiteResource stonePrerequisite() {
        return new PrerequisiteResource("stone", Items.WOODEN_PICKAXE, Items.COBBLESTONE,
                COBBLESTONE_REQUIRED, state -> state.is(Blocks.STONE), state -> state.is(Blocks.STONE),
                state -> true, Stage.FIND_STONE);
    }

    private void beginPrerequisiteAcquisition(Minecraft client, PrerequisiteResource resource,
                                               NeoForgeMiningTargetSearch.BatchResult exhaustedSearch) {
        var strategy = NeoForgePrerequisiteAcquisitionPlanner.choose(policy, true);
        prerequisiteAcquisitionStrategy = switch (strategy) {
            case DIRECT_OBSERVED_ONLY -> "prerequisite-acquisition:direct-observed-only";
            case SAFE_EXPOSED_SEARCH -> "prerequisite-acquisition:safe-exposed-search";
            case SAFE_DESCENDING_STAIRCASE -> "prerequisite-acquisition:staircase";
        };
        prerequisiteAcquisitionReason = "local-scope-exhausted:" + resource.id()
                + ":enumerated=" + exhaustedSearch.enumerated()
                + ":validated=" + exhaustedSearch.validated()
                + ":rejections=" + exhaustedSearch.rejectionHistogram();
        prerequisiteGateOutcome = "strategy-selected";
        safetyDiagnostics.add(prerequisiteAcquisitionStrategy + ":why=" + prerequisiteAcquisitionReason);
        inputActions.add("observe:" + prerequisiteAcquisitionStrategy);
        if (strategy != NeoForgePrerequisiteAcquisitionPlanner.Strategy.SAFE_DESCENDING_STAIRCASE) {
            scheduleLivenessRecovery(client);
            return;
        }
        if (prerequisiteResource == null || !prerequisiteResource.id().equals(resource.id())) {
            prerequisiteRetreatOrigin = requirePlayer(client).blockPosition().immutable();
            prerequisiteDirection = null;
            prerequisiteCommittedSteps = 0;
            prerequisitePlanRejections = 0;
            prerequisiteBlocksBroken = 0;
            prerequisiteRejectedSteps.clear();
        }
        prerequisiteResource = resource;
        prerequisitePlan = null;
        prerequisiteBreakIndex = 0;
        prerequisitePhase = 0;
        prerequisiteLastBreakTarget = null;
        prerequisiteSwingActive = false;
        prerequisiteStepMovementTicks = 0;
        prerequisiteStepMovementCell = null;
        stopMovement(client);
        resetNavigation();
        transition(Stage.PLAN_PREREQUISITE_ACQUISITION, 4);
    }

    private void planPrerequisiteAcquisition(Minecraft client) {
        var player = requirePlayer(client);
        var resource = requirePrerequisiteResource();
        requireEquippedTool(client, resource.tool(), resource.id() + " underground acquisition");
        if (countItem(player, resource.output()) >= resource.successQuantity()) {
            completePrerequisiteAcquisition(client);
            return;
        }
        if (prerequisiteCommittedSteps >= MAX_PREREQUISITE_STAIR_STEPS
                || prerequisiteBlocksBroken >= MAX_PREREQUISITE_BLOCKS_BROKEN
                || prerequisitePlanRejections >= MAX_PREREQUISITE_PLAN_REJECTIONS) {
            throw new IllegalStateException("PREREQUISITE_ACQUISITION_EXHAUSTED: resource=" + resource.id()
                    + ", steps=" + prerequisiteCommittedSteps + ", blocks=" + prerequisiteBlocksBroken
                    + ", rejections=" + prerequisitePlanRejections);
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var from = player.blockPosition().immutable();
        if (!snapshot.bufferedWalkable(from)) {
            throw new IllegalStateException("PREREQUISITE_SAFETY_VETO: unsafe excavation origin " + from);
        }
        if (prerequisiteRetreatOrigin == null) prerequisiteRetreatOrigin = from;
        if (!safeRetreatPath(client, from)) {
            throw new IllegalStateException("PREREQUISITE_SAFETY_VETO: mining-free retreat unavailable from "
                    + from + " to " + prerequisiteRetreatOrigin);
        }

        var directions = new ArrayList<Direction>();
        if (prerequisiteDirection != null) directions.add(prerequisiteDirection);
        for (var direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) directions.add(direction);
        }
        for (var direction : directions) {
            var step = preflightPrerequisiteStep(client, from, direction, snapshot);
            if (step == null || prerequisiteRejectedSteps.contains(step.toFeet().asLong())) continue;
            var plan = new NeoForgePrerequisiteAcquisitionPlanner.Plan(from, direction, List.of(step));
            if (!NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(plan)) continue;
            prerequisiteDirection = direction;
            prerequisitePlan = plan;
            prerequisiteBreakIndex = 0;
            prerequisitePhase = 0;
            prerequisiteLastBreakTarget = null;
            prerequisiteSwingActive = false;
            prerequisiteStepMovementTicks = 0;
            prerequisiteStepMovementCell = null;
            prerequisiteGateOutcome = "preflight-pass:" + step.toFeet();
            safetyDiagnostics.add("prerequisite-staircase:preflight-pass:" + resource.id() + ":"
                    + from + "->" + step.toFeet() + ":retreat=" + prerequisiteRetreatOrigin);
            transition(Stage.EXCAVATE_PREREQUISITE_ROUTE, 2);
            return;
        }
        prerequisitePlanRejections++;
        prerequisiteDirection = null;
        prerequisiteGateOutcome = "preflight-reject:no-safe-step";
        safetyDiagnostics.add("prerequisite-staircase:preflight-reject:no-safe-step:"
                + resource.id() + ":origin=" + from + ":attempt=" + prerequisitePlanRejections);
        waitTicks = 4;
    }

    private NeoForgePrerequisiteAcquisitionPlanner.StairStep preflightPrerequisiteStep(
            Minecraft client, BlockPos from, Direction direction, NeoForgeWorldSnapshot snapshot) {
        var toFeet = from.relative(direction).below().immutable();
        var support = toFeet.below().immutable();
        var headTarget = toFeet.above().immutable();
        var breakTargets = List.of(headTarget, toFeet);
        var loaded = client.level.hasChunkAt(from) && client.level.hasChunkAt(toFeet)
                && client.level.hasChunkAt(toFeet.above()) && client.level.hasChunkAt(support);
        var supportState = loaded ? client.level.getBlockState(support) : Blocks.AIR.defaultBlockState();
        var supportSolid = loaded && supportState.getFluidState().isEmpty()
                && !(supportState.getBlock() instanceof FallingBlock)
                && supportState.isFaceSturdy(client.level, support, Direction.UP)
                && !snapshot.hazard(support);
        var hazardFree = loaded && !snapshot.hazard(toFeet) && !snapshot.hazard(toFeet.above())
                && breakTargets.stream().allMatch(target -> snapshot.breakExposureSafe(target));
        var routeMaterialsSafe = loaded && breakTargets.stream()
                .allMatch(target -> admissiblePrerequisiteRouteBlock(client, target));
        var retreatVerified = safeRetreatPath(client, from);
        if (!loaded || !supportSolid || !hazardFree || !routeMaterialsSafe || !retreatVerified) return null;
        return new NeoForgePrerequisiteAcquisitionPlanner.StairStep(from, toFeet, support,
                breakTargets, true, true, true, true);
    }

    private boolean admissiblePrerequisiteRouteBlock(Minecraft client, BlockPos target) {
        var resource = requirePrerequisiteResource();
        var state = client.level.getBlockState(target);
        if (state.isAir()) return true;
        var hazard = NeoForgeWorldSnapshot.capture(client.level, policy).hazard(target);
        return resource.routeMaterial().test(state)
                && NeoForgePrerequisiteAcquisitionPlanner.admissibleRouteMaterial(state,
                state.getDestroySpeed(client.level, target), hazard,
                requirePlayer(client).getMainHandItem().isCorrectToolForDrops(state));
    }

    private void excavatePrerequisiteRoute(Minecraft client) {
        var resource = requirePrerequisiteResource();
        var player = requirePlayer(client);
        requireEquippedTool(client, resource.tool(), resource.id() + " staircase excavation");
        if (countItem(player, resource.output()) >= resource.successQuantity()) {
            completePrerequisiteAcquisition(client);
            return;
        }
        if (prerequisitePlan == null || prerequisitePlan.steps().size() != 1) {
            throw new IllegalStateException("PREREQUISITE_SAFETY_VETO: missing validated staircase transaction");
        }
        var step = prerequisitePlan.steps().getFirst();
        if (!atPrerequisiteFeet(player, step.fromFeet())) {
            if (!navigateMiningRoute(client, step.fromFeet(), "prerequisite-staircase-origin")) {
                rejectPrerequisiteStep(client, "origin-route-unavailable");
            }
            return;
        }
        stopMovement(client);
        if (prerequisitePhase == 1) {
            validatePostBreakPrerequisiteStep(client, step);
            return;
        }
        if (prerequisitePhase == 2) {
            moveIntoPrerequisiteStep(client, step);
            return;
        }
        if (prerequisiteBreakIndex >= step.breakTargets().size()) {
            prerequisitePhase = 1;
            prerequisiteLastBreakTarget = step.toFeet();
            waitTicks = 1;
            return;
        }
        var target = step.breakTargets().get(prerequisiteBreakIndex);
        var state = client.level.getBlockState(target);
        if (state.isAir()) {
            stopAttack(client);
            prerequisiteBlocksBroken += NeoForgePrerequisiteAcquisitionPlanner.committedBreakDelta(
                    prerequisiteSwingActive, true);
            prerequisiteSwingActive = false;
            prerequisiteBreakIndex++;
            prerequisitePhase = 1;
            prerequisiteLastBreakTarget = target;
            waitTicks = 1;
            return;
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        lookAt(player, Vec3.atCenterOf(target));
        var hit = player.pick(5.0F, 0.0F, false);
        var exactHit = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target);
        if (!exactHit || !admissiblePrerequisiteRouteBlock(client, target)
                || !snapshot.safeMiningSite(player.blockPosition(), target,
                player.getEyePosition(), Vec3.atCenterOf(target)) || !safeRetreatPath(client, player.blockPosition())) {
            rejectPrerequisiteStep(client, !exactHit ? "exact-ray-lost" : "per-swing-safety-veto");
            return;
        }
        prerequisiteGateOutcome = "swing-pass:" + target;
        prerequisiteSwingActive = true;
        clickAndHoldAttack(client, "prerequisite-staircase-" + resource.id());
        inputActions.add("attack:prerequisite-acquisition:staircase:" + resource.id() + ":" + target);
        if (stageTicks > 360) rejectPrerequisiteStep(client, "bounded-break-timeout");
    }

    private void validatePostBreakPrerequisiteStep(Minecraft client,
                                                    NeoForgePrerequisiteAcquisitionPlanner.StairStep step) {
        stopAttack(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var playerFeet = requirePlayer(client).blockPosition();
        if (!snapshot.bufferedWalkable(playerFeet)) {
            throw new IllegalStateException("PREREQUISITE_SAFETY_VETO: newly exposed hazard reached player at "
                    + playerFeet + ", target=" + prerequisiteLastBreakTarget);
        }
        if (prerequisiteLastBreakTarget != null && !snapshot.breakExposureSafe(prerequisiteLastBreakTarget)) {
            rejectPrerequisiteStep(client, "newly-exposed-hazard");
            return;
        }
        if (!safeRetreatPath(client, playerFeet)) {
            rejectPrerequisiteStep(client, "retreat-invalidated-after-break");
            return;
        }
        if (prerequisiteBreakIndex < step.breakTargets().size()) {
            prerequisitePhase = 0;
            prerequisiteGateOutcome = "post-break-pass:continue";
            return;
        }
        var supportState = client.level.getBlockState(step.support());
        if (!snapshot.bufferedWalkable(step.toFeet())
                || supportState.getBlock() instanceof FallingBlock
                || !supportState.isFaceSturdy(client.level, step.support(), Direction.UP)
                || !safeRetreatPath(client, step.toFeet())) {
            rejectPrerequisiteStep(client, "landing-or-retreat-invalid-after-break");
            return;
        }
        prerequisitePhase = 2;
        prerequisiteStepMovementTicks = 0;
        prerequisiteStepMovementCell = requirePlayer(client).blockPosition().immutable();
        prerequisiteGateOutcome = "post-break-pass:movement-authorized";
        waitTicks = 1;
    }

    private void moveIntoPrerequisiteStep(Minecraft client,
                                          NeoForgePrerequisiteAcquisitionPlanner.StairStep step) {
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        if (!snapshot.bufferedWalkable(step.toFeet()) || !safeRetreatPath(client, step.toFeet())) {
            rejectPrerequisiteStep(client, "movement-gate-retreat-or-landing-lost");
            return;
        }
        var player = requirePlayer(client);
        if (prerequisiteStepMovementCell == null
                || !prerequisiteStepMovementCell.equals(player.blockPosition())) {
            prerequisiteStepMovementCell = player.blockPosition().immutable();
            prerequisiteStepMovementTicks = 0;
        }
        if (++prerequisiteStepMovementTicks > 180) {
            rejectPrerequisiteStep(client, "movement-timeout");
            return;
        }
        try {
            // A false result means the safe route is still being traversed, not that it
            // failed.  Only typed route exceptions reject this bounded transaction.
            navigateTo(client, step.toFeet(), MINING_VANTAGE_TOLERANCE,
                    "prerequisite-staircase-step");
        } catch (IllegalStateException failure) {
            var message = String.valueOf(failure.getMessage());
            if (!message.startsWith("safe intelligent path unavailable before reaching ")
                    && !message.startsWith("safe intelligent navigation could not continue toward ")
                    && !message.startsWith("safe intelligent navigation remained blocked during ")) {
                throw failure;
            }
            rejectPrerequisiteStep(client, "movement-route-unavailable");
            return;
        }
        if (!atPrerequisiteFeet(player, step.toFeet())) return;
        prerequisiteCommittedSteps++;
        prerequisitePlan = null;
        prerequisiteBreakIndex = 0;
        prerequisitePhase = 0;
        prerequisiteLastBreakTarget = null;
        prerequisiteSwingActive = false;
        prerequisiteStepMovementTicks = 0;
        prerequisiteStepMovementCell = null;
        prerequisiteGateOutcome = "step-committed:rescan";
        exhaustedStoneScanOrigin = null;
        miningSearchKind = "";
        observedStone = List.of();
        resetNavigation();
        safetyDiagnostics.add("prerequisite-staircase:step-committed:" + prerequisiteResource.id()
                + ":step=" + prerequisiteCommittedSteps + ":position=" + step.toFeet()
                + ":retreat=" + prerequisiteRetreatOrigin);
        transition(prerequisiteResource.rescanStage(), 3);
    }

    private boolean safeRetreatPath(Minecraft client, BlockPos from) {
        if (prerequisiteRetreatOrigin == null) return false;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var path = NeoForgeSafePathPlanner.find(client.level, from, prerequisiteRetreatOrigin,
                policy, new NeoForgeSafePathPlanner.ArrivalSpec(0.45, 0.8));
        return snapshot.safeMiningPath(path);
    }

    /**
     * A Minecraft block cell is the meaningful arrival unit for a staircase transaction.
     * The player can validly occupy the cell while standing near a corner, where a strict
     * distance-to-centre check would incorrectly send the actor back through navigation.
     */
    private static boolean atPrerequisiteFeet(LocalPlayer player, BlockPos feet) {
        return player.blockPosition().equals(feet)
                || new NeoForgeSafePathPlanner.ArrivalSpec(0.45, 0.8)
                .reached(player.getX(), player.getY(), player.getZ(), feet);
    }

    private void rejectPrerequisiteStep(Minecraft client, String reason) {
        stopAttack(client);
        stopMovement(client);
        if (prerequisitePlan != null && !prerequisitePlan.steps().isEmpty()) {
            prerequisiteRejectedSteps.add(prerequisitePlan.steps().getFirst().toFeet().asLong());
        }
        prerequisitePlanRejections++;
        prerequisiteGateOutcome = "reject:" + reason;
        safetyDiagnostics.add("prerequisite-staircase:reject:" + reason + ":resource="
                + requirePrerequisiteResource().id() + ":rejections=" + prerequisitePlanRejections);
        prerequisitePlan = null;
        prerequisiteDirection = null;
        prerequisiteBreakIndex = 0;
        prerequisitePhase = 0;
        prerequisiteLastBreakTarget = null;
        prerequisiteSwingActive = false;
        prerequisiteStepMovementTicks = 0;
        prerequisiteStepMovementCell = null;
        resetNavigation();
        transition(Stage.PLAN_PREREQUISITE_ACQUISITION, 4);
    }

    private void completePrerequisiteAcquisition(Minecraft client) {
        stopAttack(client);
        prerequisiteGateOutcome = "resource-acquired";
        safetyDiagnostics.add("prerequisite-staircase:resource-acquired:" + requirePrerequisiteResource().id()
                + ":steps=" + prerequisiteCommittedSteps + ":blocks=" + prerequisiteBlocksBroken);
        prerequisitePlan = null;
        prerequisiteRetreatOrigin = null;
        prerequisiteDirection = null;
        prerequisiteRejectedSteps.clear();
        prerequisiteStepMovementTicks = 0;
        prerequisiteStepMovementCell = null;
        if ("stone".equals(prerequisiteResource.id())) {
            nextTableStage = Stage.CRAFT_STONE_PICK;
            resetRecipeState();
            prerequisiteResource = null;
            transition(Stage.OPEN_TABLE, 15);
            return;
        }
        throw new IllegalStateException("no completion route for prerequisite " + prerequisiteResource.id());
    }

    private PrerequisiteResource requirePrerequisiteResource() {
        if (prerequisiteResource == null) {
            throw new IllegalStateException("prerequisite acquisition stage has no resource descriptor");
        }
        return prerequisiteResource;
    }

    private void findIron(Minecraft client) {
        requirePrerequisiteTool(client, Items.STONE_PICKAXE, "iron mining");
        clearMiningVantage();
        if (exhaustedIronScanOrigin != null) {
            if (requirePlayer(client).blockPosition().distSqr(exhaustedIronScanOrigin) < 36.0) {
                exploreForObservation(client, "new iron exposure after exhausted scan");
                return;
            }
            exhaustedIronScanOrigin = null;
        }
        if (!"iron".equals(miningSearchKind)) {
            beginMiningSearch(client, "iron", scanVisibleBlocks(client,
                    state -> state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE), 72, 48).stream()
                    .limit(256).toList());
        }
        var search = pollMiningSearch(client);
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.SAFE_TARGET) {
            observedIron = List.of(search.target());
            ironMineIndex = 0;
            miningVantageTarget = search.target();
            miningVantage = search.vantage();
            transition(Stage.MINE_IRON, 12);
            return;
        }
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.LOCAL_SCOPE_EXHAUSTED) {
            exhaustedIronScanOrigin = requirePlayer(client).blockPosition().immutable();
            miningSearchKind = "";
            observedIron = List.of();
            scheduleLivenessRecovery(client);
        }
    }

    private void mineIron(Minecraft client) {
        requireEquippedTool(client, Items.STONE_PICKAXE, "iron mining");
        if (countItem(requirePlayer(client), Items.RAW_IRON) >= RAW_IRON_REQUIRED) {
            stopAttack(client);
            transition(Stage.FIND_GRAVEL, 15);
            return;
        }
        if (ironMineIndex >= observedIron.size()) {
            exhaustedIronScanOrigin = requirePlayer(client).blockPosition().immutable();
            observedIron = List.of();
            transition(Stage.FIND_IRON, 3);
            return;
        }
        var target = observedIron.get(ironMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.IRON_ORE)
                && !client.level.getBlockState(target).is(Blocks.DEEPSLATE_IRON_ORE)) {
            stopAttack(client);
            rawIronMined++;
            observedIron = List.of();
            clearMiningVantage();
            transition(Stage.FIND_IRON, 10);
            return;
        }
        var preparation = prepareMiningTarget(client, target, "iron ore");
        if (preparation != MiningTargetPreparation.READY) {
            if (preparation == MiningTargetPreparation.UNREACHABLE || stageTicks > 90) {
                stopAttack(client);
                safetyDiagnostics.add("mining-replan:skip-"
                        + (preparation == MiningTargetPreparation.UNREACHABLE ? "unreachable" : "movement-timeout")
                        + "-iron:" + target);
                observedIron = List.of();
                clearMiningVantage();
                transition(Stage.FIND_IRON, 1);
            }
            return;
        }
        clickAndHoldAttack(client, "stone-pickaxe");
        inputActions.add("attack:key.attack-held-with-stone-pickaxe");
        if (stageTicks > 520) throw new IllegalStateException("stone pickaxe failed to mine observed iron ore");
    }

    private void findGravel(Minecraft client) {
        requirePrerequisiteTool(client, Items.STONE_PICKAXE, "gravel mining");
        clearMiningVantage();
        if (exhaustedGravelScanOrigin != null) {
            if (requirePlayer(client).blockPosition().distSqr(exhaustedGravelScanOrigin) < 36.0) {
                exploreForObservation(client, "new gravel exposure after exhausted scan");
                return;
            }
            exhaustedGravelScanOrigin = null;
        }
        if (!"gravel".equals(miningSearchKind)) {
            beginMiningSearch(client, "gravel",
                    scanVisibleBlocks(client, state -> state.is(Blocks.GRAVEL), 64, 32).stream()
                            .limit(128).toList());
        }
        var search = pollMiningSearch(client);
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.SAFE_TARGET) {
            observedGravel = List.of(search.target());
            gravelMineIndex = 0;
            miningVantageTarget = search.target();
            miningVantage = search.vantage();
            transition(Stage.MINE_GRAVEL, 12);
            return;
        }
        if (search.outcome() == NeoForgeMiningTargetSearch.Outcome.LOCAL_SCOPE_EXHAUSTED) {
            exhaustedGravelScanOrigin = requirePlayer(client).blockPosition().immutable();
            miningSearchKind = "";
            observedGravel = List.of();
            scheduleLivenessRecovery(client);
        }
    }

    private void mineGravel(Minecraft client) {
        requireEquippedTool(client, Items.STONE_PICKAXE, "gravel mining");
        if (countItem(requirePlayer(client), Items.FLINT) > 0) {
            stopAttack(client);
            transition(Stage.PLACE_FURNACE, 15);
            return;
        }
        if (gravelMineIndex >= observedGravel.size()) {
            exhaustedGravelScanOrigin = requirePlayer(client).blockPosition().immutable();
            observedGravel = List.of();
            transition(Stage.FIND_GRAVEL, 3);
            return;
        }
        var target = observedGravel.get(gravelMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.GRAVEL)) {
            stopAttack(client);
            gravelMined++;
            observedGravel = List.of();
            clearMiningVantage();
            transition(Stage.FIND_GRAVEL, 8);
            return;
        }
        var preparation = prepareMiningTarget(client, target, "gravel");
        if (preparation != MiningTargetPreparation.READY) {
            if (preparation == MiningTargetPreparation.UNREACHABLE || stageTicks > 90) {
                stopAttack(client);
                safetyDiagnostics.add("mining-replan:skip-"
                        + (preparation == MiningTargetPreparation.UNREACHABLE ? "unreachable" : "movement-timeout")
                        + "-gravel:" + target);
                observedGravel = List.of();
                clearMiningVantage();
                transition(Stage.FIND_GRAVEL, 1);
            }
            return;
        }
        clickAndHoldAttack(client, "flint-gravel");
        inputActions.add("attack:key.attack-held-for-flint-gravel");
        if (stageTicks > 320) throw new IllegalStateException("gravel mining failed");
    }

    private void placeFurnace(Minecraft client) {
        var player = requirePlayer(client);
        if (furnacePosition == null) furnacePosition = findTablePlacement(client);
        if (client.level.getBlockState(furnacePosition).is(Blocks.FURNACE)) {
            transition(Stage.OPEN_FURNACE, 15);
            return;
        }
        selectHotbar(client, furnaceHotbarSlot);
        var support = furnacePosition.below();
        lookAt(player, facePoint(support, Direction.UP));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                && blockHit.getDirection() == Direction.UP && stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-place-furnace");
        }
        if (stageTicks > 240) throw new IllegalStateException("normal furnace placement did not complete");
    }

    private void openFurnace(Minecraft client) {
        if (client.screen instanceof FurnaceScreen) {
            smeltLoaded = false;
            smeltCollected = false;
            smeltTicks = 0;
            transition(Stage.SMELT_IRON, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening furnace");
        lookAt(requirePlayer(client), Vec3.atCenterOf(furnacePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-furnace");
        }
        if (stageTicks > 120) throw new IllegalStateException("normal furnace use did not open furnace screen");
    }

    private void smeltIron(Minecraft client) {
        var screen = requireContainer(client, FurnaceScreen.class);
        if (!smeltLoaded && !recipeStarted) {
            var rawIron = findPlayerItemSlot(screen, stack -> stack.is(Items.RAW_IRON));
            var fuel = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS));
            if (rawIron < 0 || fuel < 0) throw new IllegalStateException("raw iron or natural wood fuel missing at furnace");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(rawIron, 0, ClickType.QUICK_MOVE, "load-raw-iron-into-furnace"),
                    new ClickOp(fuel, 0, ClickType.QUICK_MOVE, "load-natural-wood-fuel")), () -> {
                recipeStarted = false;
                smeltLoaded = true;
                smeltTicks = 0;
            });
            return;
        }
        if (smeltLoaded && !smeltCollected) {
            smeltTicks++;
            if (smeltTicks < 420) return;
            if (!recipeStarted) {
                recipeStarted = true;
                startClicks(List.of(new ClickOp(2, 0, ClickType.QUICK_MOVE, "take-smelted-iron-ingots")), () -> {
                    recipeStarted = false;
                    smeltCollected = true;
                    if (countItem(requirePlayer(client), Items.IRON_INGOT) < RAW_IRON_REQUIRED) {
                        throw new IllegalStateException("furnace did not visibly produce four iron ingots");
                    }
                    client.screen.keyPressed(256, 0, 0);
                    inputActions.add("ui:key.escape-close-furnace");
                    resetRecipeState();
                    nextTableStage = Stage.CRAFT_BUCKET;
                    transition(Stage.OPEN_TABLE, 20);
                });
            }
        }
    }

    private void closeFurnace(Minecraft client) {
        if (client.screen != null) {
            client.screen.keyPressed(256, 0, 0);
            inputActions.add("ui:key.escape-close-furnace");
            return;
        }
        nextTableStage = Stage.CRAFT_BUCKET;
        transition(Stage.OPEN_TABLE, 20);
    }

    private void craftBucket(Minecraft client) {
        if (!(client.screen instanceof CraftingScreen)) {
            openTableForRecipe(client);
            return;
        }
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var iron = findPlayerItemSlot(screen, stack -> stack.is(Items.IRON_INGOT));
            if (iron < 0) throw new IllegalStateException("iron ingots missing before bucket recipe");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(iron, 0, ClickType.PICKUP, "pick-up-iron-for-bucket"),
                    new ClickOp(1, 1, ClickType.PICKUP, "bucket-iron-left"),
                    new ClickOp(3, 1, ClickType.PICKUP, "bucket-iron-right"),
                    new ClickOp(5, 1, ClickType.PICKUP, "bucket-iron-bottom"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "return-unused-bucket-iron"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-bucket")), () -> {
                if (countItem(requirePlayer(client), Items.BUCKET) < 1) throw new IllegalStateException("bucket recipe failed");
                resetRecipeState();
                transition(Stage.CRAFT_FLINT_STEEL, 12);
            });
        }
    }

    private void craftFlintSteel(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var flint = findPlayerItemSlot(screen, stack -> stack.is(Items.FLINT));
            var iron = findPlayerItemSlot(screen, stack -> stack.is(Items.IRON_INGOT));
            if (flint < 0 || iron < 0) throw new IllegalStateException("flint or iron missing for ignition tool");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(flint, 0, ClickType.PICKUP, "pick-up-natural-flint"),
                    new ClickOp(1, 1, ClickType.PICKUP, "flint-steel-flint"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "pick-up-iron-for-flint-steel"),
                    new ClickOp(2, 1, ClickType.PICKUP, "flint-steel-iron"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "return-unused-flint-steel-iron"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-flint-and-steel")), () -> {
                if (countItem(requirePlayer(client), Items.FLINT_AND_STEEL) < 1) {
                    throw new IllegalStateException("visible flint-and-steel recipe failed");
                }
                resetRecipeState();
                transition(Stage.MOVE_BUCKET_TO_HOTBAR, 12);
            });
        }
    }

    private void findWater(Minecraft client) {
        waterSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.WATER, 96, 48);
        if (waterSource != null) {
            announce(client, "Observed a natural water source; searching for surface lava");
            transition(Stage.FIND_LAVA, 15);
            return;
        }
        exploreForObservation(client, "natural water source");
    }

    private void findLava(Minecraft client) {
        lavaSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.LAVA, 96, 48);
        if (lavaSource != null) {
            announce(client, "Observed natural water and lava; building a portal by bucket mechanics");
            fallbackPortalBase = findBucketPortalBase(client);
            if (fallbackPortalBase == null) throw new IllegalStateException("no clear natural ground for bucket portal");
            portalScaffolds.clear();
            for (var dx : List.of(1, 2)) {
                for (var y = 1; y <= 3; y++) {
                    portalScaffolds.add(fallbackPortalBase.offset(dx, y, 0));
                }
            }
            portalTargetIndex = 0;
            portalScaffoldIndex = 0;
            portalPhase = 0;
            transition(Stage.BUILD_BUCKET_PORTAL, 15);
            return;
        }
        exploreForObservation(client, "natural lava pool");
    }

    private void fillWaterBucket(Minecraft client) {
        var player = requirePlayer(client);
        var empty = hotbarSlot(player, Items.BUCKET);
        if (empty < 0) throw new IllegalStateException("empty bucket is not in the hotbar");
        selectHotbar(client, empty);
        waterSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.WATER, 96, 48);
        if (waterSource == null) throw new IllegalStateException("natural water source disappeared before bucket fill");
        if (navigateTo(client, waterSource, 3.2, "water source")) {
            lookAt(player, Vec3.atCenterOf(waterSource));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(waterSource)
                    && stageTicks % 12 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-fill-water-bucket");
            }
        }
        if (player.getMainHandItem().is(Items.WATER_BUCKET)) {
            portalPhase = 3;
            transition(Stage.BUILD_BUCKET_PORTAL, 10);
        }
        if (stageTicks > 300) throw new IllegalStateException("normal water-bucket fill did not complete");
    }

    private void fillLavaBucket(Minecraft client) {
        var player = requirePlayer(client);
        var empty = hotbarSlot(player, Items.BUCKET);
        if (empty < 0) throw new IllegalStateException("empty bucket is not in the hotbar before lava fill");
        selectHotbar(client, empty);
        lavaSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.LAVA, 96, 48);
        if (lavaSource == null) throw new IllegalStateException("natural lava source disappeared before bucket fill");
        if (navigateTo(client, lavaSource, 3.2, "lava source")) {
            lookAt(player, Vec3.atCenterOf(lavaSource));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(lavaSource)
                    && stageTicks % 12 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-fill-lava-bucket");
            }
        }
        if (player.getMainHandItem().is(Items.LAVA_BUCKET)) {
            portalPhase = 1;
            transition(Stage.BUILD_BUCKET_PORTAL, 10);
        }
        if (stageTicks > 300) throw new IllegalStateException("normal lava-bucket fill did not complete");
    }

    private void buildBucketPortal(Minecraft client) {
        var player = requirePlayer(client);
        if (portalScaffoldIndex < portalScaffolds.size()) {
            var target = portalScaffolds.get(portalScaffoldIndex);
            if (client.level.getBlockState(target).is(Blocks.COBBLESTONE)) {
                portalScaffoldIndex++;
                stageTicks = 0;
                return;
            }
            var cobble = hotbarSlot(player, Items.COBBLESTONE);
            if (cobble < 0) throw new IllegalStateException("cobblestone scaffold material missing from hotbar");
            selectHotbar(client, cobble);
            var support = target.below();
            var vantage = findPlacementVantage(client, support, Direction.UP);
            if (vantage == null) throw new IllegalStateException("no normal-input vantage for portal scaffold " + target);
            if (!navigateTo(client, vantage, 1.0, "portal scaffold")) return;
            lookAt(player, facePoint(support, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                    && blockHit.getDirection() == Direction.UP && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("place:key.use:portal-scaffold:" + target);
            }
            if (stageTicks > 260) throw new IllegalStateException("normal portal scaffold placement failed at " + target);
            return;
        }
        var targets = framePlacements(fallbackPortalBase, Direction.Axis.Z);
        if (portalTargetIndex >= targets.size()) {
            frameBlocksPlaced = (int) targets.stream().filter(p -> client.level.getBlockState(p.target()).is(Blocks.OBSIDIAN)).count();
            manualPortalBuilt = frameBlocksPlaced == 10;
            if (!manualPortalBuilt) throw new IllegalStateException("bucket portal frame readback found " + frameBlocksPlaced + " obsidian blocks");
            clearIndex = 0;
            transition(Stage.CLEAR_PORTAL_SCAFFOLD, 12);
            return;
        }
        var target = targets.get(portalTargetIndex).target();
        if (client.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
            portalTargetIndex++;
            portalPhase = 0;
            stageTicks = 0;
            return;
        }
        if (portalPhase == 0) {
            transition(Stage.FILL_LAVA_BUCKET, 10);
            return;
        }
        if (portalPhase == 1) {
            var lava = hotbarSlot(player, Items.LAVA_BUCKET);
            if (lava < 0) throw new IllegalStateException("filled lava bucket missing after normal fill");
            selectHotbar(client, lava);
            var support = target.below();
            var vantage = findPlacementVantage(client, support, Direction.UP);
            if (vantage == null || !navigateTo(client, vantage, 1.0, "portal lava placement")) return;
            lookAt(player, facePoint(support, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                    && blockHit.getDirection() == Direction.UP && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-place-lava-for-obsidian:" + target);
            }
            if (client.level.getFluidState(target).is(net.minecraft.world.level.material.Fluids.LAVA)) {
                portalPhase = 2;
                transition(Stage.FILL_WATER_BUCKET, 10);
            }
            if (stageTicks > 260) throw new IllegalStateException("normal lava placement failed at " + target);
            return;
        }
        if (portalPhase == 3) {
            selectHotbar(client, hotbarSlot(player, Items.WATER_BUCKET));
            lookAt(player, facePoint(target, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)
                    && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-water-on-lava:" + target);
            }
            if (client.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
                portalTargetIndex++;
                portalPhase = 0;
                stageTicks = 0;
                waitTicks = 8;
            }
            if (stageTicks > 260) throw new IllegalStateException("water did not convert lava to obsidian at " + target);
        }
    }

    private void clearPortalScaffold(Minecraft client) {
        if (clearIndex >= portalScaffolds.size()) {
            var remaining = portalScaffolds.stream().filter(pos -> client.level.getBlockState(pos).is(Blocks.COBBLESTONE)).count();
            if (remaining != 0) throw new IllegalStateException("portal scaffold remained inside frame: " + remaining);
            placements.clear();
            placements.addAll(framePlacements(fallbackPortalBase, Direction.Axis.Z));
            site = new Site(fallbackPortalBase, requirePlayer(client).blockPosition(), Direction.Axis.Z);
            transition(Stage.LIGHT_PORTAL, 15);
            return;
        }
        var target = portalScaffolds.get(clearIndex);
        if (!client.level.getBlockState(target).is(Blocks.COBBLESTONE)) {
            clearIndex++;
            stageTicks = 0;
            return;
        }
        var player = requirePlayer(client);
        selectHotbar(client, toolHotbarSlot);
        lookAt(player, Vec3.atCenterOf(target));
        clickAndHoldAttack(client, "remove-portal-scaffold");
        inputActions.add("attack:key.attack-held-remove-portal-scaffold");
        if (stageTicks > 320) throw new IllegalStateException("could not remove portal scaffold " + target);
    }

    private void openTableForRecipe(Minecraft client) {
        if (client.screen instanceof CraftingScreen) return;
        if (client.screen != null) throw new IllegalStateException("unexpected screen before table recipe");
        lookAt(requirePlayer(client), Vec3.atCenterOf(tablePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-crafting-table");
        }
        if (stageTicks > 120) throw new IllegalStateException("table did not open for survival recipe");
    }

    private void exploreForObservation(Minecraft client, String label) {
        var player = requirePlayer(client);
        if (stageTicks == 1) {
            player.setYRot(player.getYRot() + 47.0F);
            player.setYHeadRot(player.getYRot());
            inputActions.add("look:visible-search-sweep:" + label.replace(' ', '-'));
        }
        if (policy.actionSegmentReplanningEnabled()) {
            exploreSafely(client, label);
        } else {
            setMovement(client, true, true, player.onGround() && stageTicks % 32 < 4);
        }
        inputActions.add("move:explore-for-" + label.replace(' ', '-'));
        if (stageTicks >= 150) {
            stopMovement(client);
            transition(stage, 15);
        }
    }

    /** Explore loaded terrain with normal movement while rejecting holes and fluids. */
    private void exploreSafely(Minecraft client, String label) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var areaStalled = false;
        if (stage == Stage.FIND_STONE) {
            if (explorationProgressAnchor == null) explorationProgressAnchor = player.position();
            var anchorDx = player.getX() - explorationProgressAnchor.x;
            var anchorDz = player.getZ() - explorationProgressAnchor.z;
            if (anchorDx * anchorDx + anchorDz * anchorDz >= 25.0) {
                explorationProgressAnchor = player.position();
                explorationProgressTicks = 0;
                explorationReplanCooldown = 0;
                explorationWaypoint = null;
            } else {
                explorationProgressTicks++;
                if (explorationReplanCooldown > 0) explorationReplanCooldown--;
                areaStalled = explorationProgressTicks >= 160;
            }
        } else {
            explorationProgressAnchor = null;
            explorationProgressTicks = 0;
            explorationReplanCooldown = 0;
        }
        if (explorationLastPosition != null
                && player.position().distanceToSqr(explorationLastPosition) < 0.0009) {
            explorationNoProgressTicks++;
        } else {
            explorationNoProgressTicks = 0;
        }
        explorationLastPosition = player.position();

        if (explorationWaypoint != null || explorationNoProgressTicks >= 45
                || areaStalled && explorationReplanCooldown == 0) {
            if (explorationWaypoint == null) {
                explorationWaypoint = findExplorationWaypoint(client, snapshot, explorationProgressAnchor);
                if (areaStalled) explorationReplanCooldown = 20;
                if (explorationWaypoint != null) {
                    resetNavigation();
                    safetyDiagnostics.add("exploration-replan:" + explorationWaypoint);
                }
            }
            if (explorationWaypoint != null) {
                if (navigateTo(client, explorationWaypoint, 1.2, "safe-observation-replan:" + label)) {
                    explorationWaypoint = null;
                    explorationNoProgressTicks = 0;
                    explorationLastPosition = player.position();
                    resetNavigation();
                }
                inputActions.add("move:safety-weighted-exploration-replan:" + label.replace(' ', '-'));
                return;
            }
            stopMovement(client);
            player.setYRot(player.getYRot() + 91.0F);
            player.setYHeadRot(player.getYRot());
            if (player.onGround() && explorationNoProgressTicks % 2 == 0) {
                client.options.keyJump.setDown(true);
            }
            explorationNoProgressTicks = 0;
            explorationLastPosition = player.position();
            inputActions.add("look:safe-observation-hard-detour:" + label.replace(' ', '-'));
            return;
        }

        var forward = Direction.fromYRot(player.getYRot());
        var front = player.blockPosition().relative(forward);
        if (snapshot.walkable(front)) {
            setMovement(client, true, false, false);
            inputActions.add("move:safe-observation-step:" + label.replace(' ', '-'));
            return;
        }
        if (player.onGround() && snapshot.walkable(front.above())) {
            setMovement(client, true, false, true);
            inputActions.add("move:safe-observation-step-up:" + label.replace(' ', '-'));
            return;
        }
        stopMovement(client);
        player.setYRot(player.getYRot() + 43.0F);
        player.setYHeadRot(player.getYRot());
        inputActions.add("look:safe-observation-detour:" + label.replace(' ', '-'));
    }

    private BlockPos findExplorationWaypoint(Minecraft client, NeoForgeWorldSnapshot snapshot,
                                             Vec3 progressAnchor) {
        var origin = requirePlayer(client).blockPosition();
        var candidates = new ArrayList<BlockPos>();
        for (var offset : List.of(
                new int[]{1, 0}, new int[]{1, 1}, new int[]{0, 1}, new int[]{-1, 1},
                new int[]{-1, 0}, new int[]{-1, -1}, new int[]{0, -1}, new int[]{1, -1})) {
            for (int distance : List.of(8, 12, 16)) {
                for (int yOffset : List.of(0, 1, -1, 2, -2)) {
                    var candidate = origin.offset(offset[0] * distance, yOffset, offset[1] * distance);
                    if (!snapshot.walkable(candidate) || candidates.contains(candidate)) continue;
                    if (progressAnchor != null) {
                        var dx = candidate.getX() + 0.5 - progressAnchor.x;
                        var dz = candidate.getZ() + 0.5 - progressAnchor.z;
                        if (dx * dx + dz * dz < 36.0) continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }
        var anchor = progressAnchor == null ? requirePlayer(client).position() : progressAnchor;
        candidates.sort(Comparator.comparingDouble((BlockPos candidate) -> {
            var dx = candidate.getX() + 0.5 - anchor.x;
            var dz = candidate.getZ() + 0.5 - anchor.z;
            return dx * dx + dz * dz;
        }).reversed());
        for (var candidate : candidates.stream().limit(24).toList()) {
            var path = NeoForgeSafePathPlanner.find(client.level, origin, candidate, policy);
            if (!path.isEmpty() && path.size() > 1) return candidate;
        }
        return null;
    }

    private NeoForgeLivenessWatchdog.TickResult tickLiveness(Minecraft client) {
        var sample = livenessSample(client);
        return sample == null ? null : livenessWatchdog.tick(sample);
    }

    private NeoForgeLivenessWatchdog.Sample livenessSample(Minecraft client) {
        if (client.player == null || client.level == null) return null;
        var player = client.player;
        return new NeoForgeLivenessWatchdog.Sample(NeoForgeLivenessWatchdog.activityKey(stage.name()),
                player.getX(), player.getY(), player.getZ(),
                inventoryRevision(player), localWorldRevision(client, player.blockPosition(), 2),
                movementExpected() && genericLivenessEnabled(stage.name(), prerequisitePhase)
                        && !smartNavigationOwnsLiveness());
    }

    /** Smart A* routes have their own waypoint timeout and route rejection contract. */
    private boolean smartNavigationOwnsLiveness() {
        if (!policy.smartNavigation() || navigationDestination == null) return false;
        return switch (stage) {
            case NAVIGATE_LOOT, NAVIGATE_STARTER_RESOURCE -> true;
            case MINE_STARTER_RESOURCE -> resourceMiningVantage != null;
            case MINE_STONE, MINE_IRON, MINE_GRAVEL -> miningVantage != null;
            default -> false;
        };
    }

    static boolean genericLivenessEnabled(String stageName) {
        return !stageName.equals("PLAN_PREREQUISITE_ACQUISITION")
                && !stageName.equals("EXCAVATE_PREREQUISITE_ROUTE");
    }

    static boolean genericLivenessEnabled(String stageName, int prerequisitePhase) {
        return !stageName.equals("PLAN_PREREQUISITE_ACQUISITION")
                && !stageName.equals("EXCAVATE_PREREQUISITE_ROUTE");
    }

    private void scheduleLivenessRecovery(Minecraft client) {
        if (client.player == null || client.level == null || livenessFrontier != null) return;
        stopAttack(client);
        stopMovement(client);
        resetNavigation();
        livenessFrontier = findSafeFrontier(client);
        if (livenessFrontier == null) {
            var action = frontierWatchdog.noCandidate();
            safetyDiagnostics.add("liveness-recovery:no-verified-safe-frontier:stage=" + stage
                    + ":recovery=" + livenessWatchdog.recoveryCount()
                    + ":frontierFailures=" + frontierWatchdog.failures());
            if (action == NeoForgeFrontierWatchdog.Action.EXHAUSTED) {
                throw new IllegalStateException("LIVENESS_EXHAUSTED: no verified safe frontier after "
                        + frontierWatchdog.failures() + " bounded frontier failures");
            }
            return;
        }
        frontierWatchdog.begin(livenessFrontier.waypoint().asLong(),
                Math.sqrt(requirePlayer(client).distanceToSqr(Vec3.atCenterOf(livenessFrontier.waypoint()))));
        safetyDiagnostics.add("liveness-recovery:frontier=" + livenessFrontier.waypoint()
                + ":provenance=" + livenessFrontier.provenance()
                + ":path=" + livenessFrontier.pathLength()
                + ":retreat=" + livenessFrontier.retreatLength()
                + ":recovery=" + livenessWatchdog.recoveryCount());
        inputActions.add("move:liveness-verified-safe-frontier:" + stage);
    }

    private void rejectLivenessFrontier(Minecraft client, NeoForgeFrontierWatchdog.Action action) {
        var failed = livenessFrontier;
        rejectedLivenessFrontiers.add(failed.waypoint().asLong());
        safetyDiagnostics.add("liveness-frontier:reject:" + failed.waypoint()
                + ":failures=" + frontierWatchdog.failures() + ":action=" + action);
        livenessFrontier = null;
        stopMovement(client);
        resetNavigation();
        var sample = livenessSample(client);
        if (sample != null) livenessWatchdog.restartAfterFrontier(sample);
        if (action == NeoForgeFrontierWatchdog.Action.EXHAUSTED) {
            throw new IllegalStateException("LIVENESS_EXHAUSTED: three verified safe frontiers made no positional progress");
        }
        scheduleLivenessRecovery(client);
    }

    private SafeFrontier findSafeFrontier(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var candidates = new ArrayList<BlockPos>();
        for (var offset : List.of(
                new int[]{1, 0}, new int[]{1, 1}, new int[]{0, 1}, new int[]{-1, 1},
                new int[]{-1, 0}, new int[]{-1, -1}, new int[]{0, -1}, new int[]{1, -1})) {
            for (int distance : List.of(6, 9, 12, 16)) {
                for (int yOffset : List.of(0, 1, -1, 2, -2)) {
                    var candidate = origin.offset(offset[0] * distance, yOffset, offset[1] * distance);
                    if (rejectedLivenessFrontiers.contains(candidate.asLong())
                            || !snapshot.bufferedWalkable(candidate) || candidates.contains(candidate)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> player.distanceToSqr(Vec3.atCenterOf(candidate))));
        for (var candidate : candidates.stream().limit(32).toList()) {
            var path = NeoForgeSafePathPlanner.find(client.level, origin, candidate, policy);
            var retreat = NeoForgeSafePathPlanner.find(client.level, candidate, origin, policy);
            if (path.size() <= 1 || retreat.size() <= 1
                    || !snapshot.safeMiningPath(path) || !snapshot.safeMiningPath(retreat)) continue;
            return new SafeFrontier(candidate, path.size(), retreat.size(),
                    "buffered-walkable+a-star+reverse-retreat");
        }
        return null;
    }

    private static int inventoryRevision(LocalPlayer player) {
        var revision = 1;
        for (var stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            revision = 31 * revision + BuiltInRegistries.ITEM.getId(stack.getItem());
            revision = 31 * revision + stack.getCount();
        }
        return revision;
    }

    private static long localWorldRevision(Minecraft client, BlockPos center, int radius) {
        var revision = 1_469_598_103_934_665_603L;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var probe = center.offset(dx, dy, dz);
                    var state = client.level.getBlockState(probe);
                    revision ^= state.hashCode();
                    revision *= 1_099_511_628_211L;
                }
            }
        }
        return revision;
    }

    private List<BlockPos> scanVisibleBlocks(Minecraft client,
                                              java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> predicate,
                                              int radius, int vertical) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var output = new ArrayList<BlockPos>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - vertical);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + vertical);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    var state = client.level.getBlockState(pos);
                    if (predicate.test(state) && isVisibleBlock(client, pos)) output.add(pos.immutable());
                }
            }
        }
        output.sort(Comparator.comparingDouble(pos -> player.distanceToSqr(Vec3.atCenterOf(pos))));
        return List.copyOf(output);
    }

    private static boolean isVisibleBlock(Minecraft client, BlockPos position) {
        for (var direction : Direction.values()) {
            var neighbor = position.relative(direction);
            if (client.level.getBlockState(neighbor).isAir()
                    || !client.level.getBlockState(neighbor).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private static BlockPos findFluidSource(Minecraft client,
                                             net.minecraft.world.level.material.Fluid fluid,
                                             int radius, int vertical) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - vertical);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + vertical);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    var state = client.level.getFluidState(pos);
                    if (!state.is(fluid) || !state.isSource()) continue;
                    var distance = player.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private List<NeoForgeStarterResourceCatalog.Source> scanStarterResourceSources(Minecraft client) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var logs = new ArrayList<BlockPos>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - STARTER_RESOURCE_SCAN_BELOW);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + STARTER_RESOURCE_SCAN_ABOVE);
        for (int x = center.getX() - STARTER_RESOURCE_SCAN_HORIZONTAL_RADIUS;
             x <= center.getX() + STARTER_RESOURCE_SCAN_HORIZONTAL_RADIUS; x++) {
            for (int z = center.getZ() - STARTER_RESOURCE_SCAN_HORIZONTAL_RADIUS;
                 z <= center.getZ() + STARTER_RESOURCE_SCAN_HORIZONTAL_RADIUS; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    if (client.level.getBlockState(pos).is(BlockTags.LOGS)) logs.add(pos.immutable());
                }
            }
        }
        return NeoForgeStarterResourceCatalog.group(logs, center, rejectedResourceSources);
    }

    private BlockPos findBucketPortalBase(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        for (int radius = 2; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    var base = origin.offset(dx, 0, dz);
                    if (!standable(client, base)) continue;
                    var valid = true;
                    for (var placement : framePlacements(base, Direction.Axis.Z)) {
                        var target = placement.target();
                        if (!client.level.getBlockState(target).canBeReplaced()
                                || !client.level.getBlockState(target).getFluidState().isEmpty()) valid = false;
                    }
                    for (int along = 1; along <= 2; along++) {
                        for (int y = 2; y <= 3; y++) {
                            if (!client.level.getBlockState(frameOffset(base, Direction.Axis.Z, along, y, 0)).canBeReplaced()) valid = false;
                        }
                    }
                    if (valid) return base.immutable();
                }
            }
        }
        return null;
    }

    private static BlockPos findNearestChest(Minecraft client, int radius) {
        var player = requirePlayer(client);
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        var center = player.blockPosition();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - 32);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + 32);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!client.level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    var candidate = new BlockPos(x, y, z);
                    if (!client.level.getBlockState(candidate).is(Blocks.CHEST)) continue;
                    var distance = player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private void findSite(Minecraft client) {
        site = findPortalSite(client);
        if (site == null) {
            if (stageTicks > 360) throw new IllegalStateException(
                    "no natural ruined-portal frame was observed after looting the portal chest");
            return;
        }
        placements.clear();
        placements.addAll(framePlacements(site.base(), site.axis()));
        announce(client, "Natural ruined-portal frame observed; completing missing blocks with normal placement input");
        transition(Stage.BUILD_PORTAL, 12);
    }

    private void buildPortal(Minecraft client) {
        var player = requirePlayer(client);
        if (placementIndex >= placements.size()) {
            frameBlocksPlaced = (int) placements.stream()
                    .filter(placement -> client.level.getBlockState(placement.target()).is(Blocks.OBSIDIAN)).count();
            manualPortalBuilt = frameBlocksPlaced == 10;
            if (!manualPortalBuilt) throw new IllegalStateException("portal frame readback did not find ten obsidian blocks");
            announce(client, "Portal frame complete; lighting it with normal flint-and-steel input");
            transition(Stage.LIGHT_PORTAL, 12);
            return;
        }

        var placement = placements.get(placementIndex);
        if (client.level.getBlockState(placement.target()).is(Blocks.OBSIDIAN)) {
            placementIndex++;
            placementAttempts = 0;
            placementVantage = null;
            navigationDestination = null;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            waitTicks = 3;
            return;
        }
        if (!client.level.getBlockState(placement.target()).isAir()) {
            throw new IllegalStateException("portal target became obstructed: " + placement.target());
        }
        if (!NeoForgeWorldSnapshot.capture(client.level, policy).safePlacementSite(placement.target())) {
            throw new IllegalStateException("portal placement hazard gate rejected " + placement.target());
        }
        var slot = hotbarSlot(player, Items.OBSIDIAN);
        if (slot < 0) throw new IllegalStateException("naturally obtained obsidian is not in the hotbar");
        selectHotbar(client, slot);
        if (placementVantage == null) {
            placementVantage = findPlacementVantage(client, placement.support(), Direction.UP);
            if (placementVantage == null) throw new IllegalStateException(
                    "no standable normal-input vantage can see portal support " + placement.support());
        }
        if (!navigateTo(client, placementVantage, 0.85, "portal placement")) return;
        lookAt(player, facePoint(placement.support(), Direction.UP));
        var hit = player.pick(5.0, 1.0F, false);
        var aimed = hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(placement.support());
        var correctlyFaced = hit instanceof BlockHitResult blockHit
                && aimed && blockHit.getDirection() == Direction.UP;
        if (correctlyFaced && stageTicks % 8 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("place:key.use:obsidian:" + placement.target());
            placementAttempts++;
        }
        if (placementAttempts > 20 || stageTicks > 1_200) {
            var hitDescription = hit instanceof BlockHitResult blockHit
                    ? blockHit.getBlockPos() + "/" + blockHit.getDirection() : hit.getType().toString();
            throw new IllegalStateException("normal use input could not place portal obsidian at "
                    + placement.target() + "; aimed=" + correctlyFaced + ", hit=" + hitDescription
                    + ", player=" + player.blockPosition() + ", vantage=" + placementVantage);
        }
    }

    private void lightPortal(Minecraft client) {
        var player = requirePlayer(client);
        var flintSlot = hotbarSlot(player, Items.FLINT_AND_STEEL);
        if (flintSlot < 0) throw new IllegalStateException("naturally obtained flint and steel is not in the hotbar");
        selectHotbar(client, flintSlot);
        var ignitionSupport = frameOffset(site.base(), site.axis(), 1, 1, 0);
        if (placementVantage == null) placementVantage = findPlacementVantage(client, ignitionSupport, Direction.UP);
        if (placementVantage == null || !navigateTo(client, placementVantage, 0.85, "portal ignition")) return;
        lookAt(player, facePoint(ignitionSupport, Direction.UP));
        if (stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("portal:key.use:flint-and-steel");
            lightAttempts++;
        }
        portalBlocksObserved = hasPortalBlocks(client);
        if (portalBlocksObserved) {
            portalLit = true;
            announce(client, "Portal lit; entering through normal forward movement");
            transition(Stage.ENTER_PORTAL, 12);
            return;
        }
        if (lightAttempts > 20 || stageTicks > 300) {
            throw new IllegalStateException("flint-and-steel input did not produce Nether portal blocks");
        }
    }

    private void enterPortal(Minecraft client) {
        if (client.level != null && Level.NETHER.equals(client.level.dimension())) {
            reachedNether = true;
            enteredPortal = true;
            finalDimension = client.level.dimension().location().toString();
            releaseInput(client);
            transition(Stage.COMPLETE_DELAY, 50);
            return;
        }
        if (!portalBlocksObserved) portalBlocksObserved = hasPortalBlocks(client);
        var center = frameOffset(site.base(), site.axis(), 1, 2, 0);
        lookAt(requirePlayer(client), Vec3.atCenterOf(center));
        client.options.keyUp.setDown(true);
        if (stageTicks % 30 == 1) inputActions.add("move:key.forward-into-portal");
        if (stageTicks > 1_000) {
            throw new IllegalStateException("player did not transition through the lit portal");
        }
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        if (!reachedNether) throw new IllegalStateException("Nether terminal predicate was not observed");
        finalDimension = client.level == null ? finalDimension : client.level.dimension().location().toString();
        finished = true;
        result.complete(output(client, "complete", true));
    }

    private void pauseAtCheckpoint(Minecraft client, String checkpoint) {
        if (!requestedCheckpoint.equals(checkpoint)) return;
        releaseInput(client);
        paused = true;
        result.complete(output(client, checkpoint, true));
    }

    private Map<String, Object> output(Minecraft client, String checkpoint, boolean checkpointComplete) {
        var player = requirePlayer(client);
        var portalPosition = site == null ? player.blockPosition() : site.base();
        return Map.ofEntries(
                Map.entry("checkpoint", checkpoint),
                Map.entry("checkpointComplete", checkpointComplete),
                Map.entry("continuationToken", continuationToken),
                Map.entry("worldObservation", NeoForgeGoalObservation.capture(client, policy)),
                Map.entry("freshWorld", freshWorld),
                Map.entry("survival", survival),
                Map.entry("worldName", worldName),
                Map.entry("worldGameTimeAtStart", worldGameTimeAtStart),
                Map.entry("initialDimension", initialDimension),
                Map.entry("finalDimension", finalDimension),
                Map.entry("portalBase", position(portalPosition)),
                Map.entry("teleportedToBuildSite", teleportedToBuildSite),
                // Retain legacy output fields as an explicit empty no-command proof for clients
                // that still consume the original schema.
                Map.entry("setupCommandsUsed", false),
                Map.entry("setupCommandCount", 0),
                Map.entry("setupCommands", List.of()),
                Map.entry("commandFeedbackSuppressed", true),
                Map.entry("manualPortalBuilt", manualPortalBuilt),
                Map.entry("portalFrameBlocksPlaced", frameBlocksPlaced),
                Map.entry("portalLit", portalLit),
                Map.entry("portalBlocksObserved", portalBlocksObserved),
                Map.entry("enteredPortal", enteredPortal),
                Map.entry("reachedNether", reachedNether),
                Map.entry("playerAlive", player.isAlive() && player.getHealth() > 0.0F),
                Map.entry("healthAtEnd", player.getHealth()),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("prerequisitePlanning", policy.prerequisitePlanningEnabled()),
                Map.entry("actionSegmentReplanning", policy.actionSegmentReplanningEnabled()),
                Map.entry("safetyInterventions", List.copyOf(safetyDiagnostics)),
                Map.entry("observation", policy.observation()), Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowBlockBreaking", policy.allowBlockBreaking()),
                Map.entry("allowBlockPlacing", policy.allowBlockPlacing()),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("suppressInGameMessages", suppressInGameMessages),
                Map.entry("inGameMessagesEmitted", inGameMessagesEmitted),
                Map.entry("directMutationUsed", false),
                Map.entry("inputActions", List.copyOf(inputActions)));
    }

    private static String checkpoint(Map<String, Object> input) {
        var value = input.get("checkpoint");
        var checkpoint = value == null || String.valueOf(value).isBlank()
                ? "complete" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (checkpoint) {
            case "starter-tools", "portal-tools", "complete" -> checkpoint;
            default -> throw new IllegalArgumentException(
                    "Nether checkpoint must be starter-tools, portal-tools, or complete");
        };
    }

    private Site findPortalSite(Minecraft client) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var observedObsidian = new ArrayList<BlockPos>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - 40);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + 40);
        for (int x = center.getX() - 96; x <= center.getX() + 96; x++) {
            for (int z = center.getZ() - 96; z <= center.getZ() + 96; z++) {
                if (!client.level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    var position = new BlockPos(x, y, z);
                    if (client.level.getBlockState(position).is(Blocks.OBSIDIAN)) {
                        observedObsidian.add(position.immutable());
                    }
                }
            }
        }
        Site best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (var anchor : observedObsidian) {
            for (var axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
                for (int along = -3; along <= 0; along++) {
                    for (int yOffset = -4; yOffset <= -1; yOffset++) {
                        var base = axis == Direction.Axis.Z
                                ? anchor.offset(along, yOffset, 0)
                                : anchor.offset(0, yOffset, along);
                        var candidatePlacements = framePlacements(base, axis);
                        var existing = 0;
                        var valid = true;
                        for (var placement : candidatePlacements) {
                            var state = client.level.getBlockState(placement.target());
                            if (state.is(Blocks.OBSIDIAN)) existing++;
                            else if (!state.isAir() && !state.canBeReplaced()) valid = false;
                        }
                        if (!valid || existing < 5) continue;
                        var missing = candidatePlacements.size() - existing;
                        if (missing > countItem(player, Items.OBSIDIAN)) continue;
                        for (int alongInterior = 1; alongInterior <= 2 && valid; alongInterior++) {
                            for (int y = 2; y <= 3; y++) {
                                var interior = frameOffset(base, axis, alongInterior, y, 0);
                                if (!client.level.getBlockState(interior).isAir()) valid = false;
                            }
                        }
                        if (!valid) continue;
                        var stand = findNearestStandable(client, base, axis);
                        if (stand == null) continue;
                        var distance = player.distanceToSqr(Vec3.atCenterOf(stand));
                        if (distance < bestDistance) {
                            best = new Site(base.immutable(), stand.immutable(), axis);
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearestStandable(Minecraft client, BlockPos base, Direction.Axis axis) {
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    var candidate = base.offset(dx, dy, dz);
                    if (!standable(client, candidate)) continue;
                    var distance = candidate.distSqr(base);
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static List<Placement> framePlacements(BlockPos base, Direction.Axis axis) {
        var output = new ArrayList<Placement>(10);
        for (int along = 0; along < 4; along++) {
            var target = frameOffset(base, axis, along, 1, 0);
            output.add(new Placement(target, target.below()));
        }
        for (int y = 2; y <= 3; y++) {
            var left = frameOffset(base, axis, 0, y, 0);
            var right = frameOffset(base, axis, 3, y, 0);
            output.add(new Placement(left, left.below()));
            output.add(new Placement(right, right.below()));
        }
        for (int along = 0; along < 4; along++) {
            var target = frameOffset(base, axis, along, 4, 0);
            output.add(new Placement(target, target.below()));
        }
        return output;
    }

    private static BlockPos frameOffset(BlockPos base, Direction.Axis axis, int along, int y, int normal) {
        return axis == Direction.Axis.Z ? base.offset(along, y, normal) : base.offset(normal, y, along);
    }

    private boolean hasPortalBlocks(Minecraft client) {
        if (client.level == null || site == null) return false;
        for (int along = 1; along <= 2; along++) {
            for (int y = 2; y <= 3; y++) {
                if (client.level.getBlockState(frameOffset(site.base(), site.axis(), along, y, 0))
                        .is(Blocks.NETHER_PORTAL)) return true;
            }
        }
        return false;
    }

    private static boolean solid(Minecraft client, BlockPos position) {
        return !client.level.getBlockState(position).getCollisionShape(client.level, position).isEmpty();
    }

    private BlockPos findPlacementVantage(Minecraft client, BlockPos support, Direction face) {
        var aim = facePoint(support, face);
        for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            for (var distance : List.of(1, 2, 3)) {
                var horizontal = support.relative(direction, distance);
                for (var yOffset : List.of(1, 0, 2, -1, 3)) {
                    var candidate = new BlockPos(horizontal.getX(), support.getY() + yOffset,
                            horizontal.getZ());
                    if (!standable(client, candidate)) continue;
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    if (eye.distanceTo(aim) > 4.8) continue;
                    var clip = client.level.clip(new ClipContext(eye, aim,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, requirePlayer(client)));
                    if (clip instanceof BlockHitResult blockHit
                            && blockHit.getBlockPos().equals(support)
                            && blockHit.getDirection() == face) return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean navigateTo(Minecraft client, BlockPos target, double tolerance, String label) {
        if (policy.smartNavigation()) return navigateWithSafePath(client, target, tolerance, label);
        var player = requirePlayer(client);
        if (!target.equals(navigationDestination)) {
            navigationDestination = target.immutable();
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
        }
        var dx = target.getX() + 0.5 - player.getX();
        var dy = target.getY() + 0.1 - player.getY();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (horizontalDistance <= tolerance && Math.abs(dy) <= 0.4) {
            stopMovement(client);
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            return true;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(8.0F);
        var detouring = navigationStuckTicks > 18;
        var detourLeft = detouring && (navigationStuckTicks / 30) % 2 == 0;
        client.options.keyUp.setDown(horizontalDistance > tolerance);
        client.options.keySprint.setDown(horizontalDistance > 4.0);
        client.options.keyLeft.setDown(detouring && detourLeft);
        client.options.keyRight.setDown(detouring && !detourLeft);
        client.options.keyJump.setDown(player.onGround() && (detouring || stageTicks % 30 < 3));
        inputActions.add("move:key.forward:" + label);
        if (detouring) inputActions.add("move:key.strafe-detour:" + label);
        if (distance < navigationLastDistance - 0.04) navigationStuckTicks = 0;
        else navigationStuckTicks++;
        navigationLastDistance = distance;
        if (navigationStuckTicks > 240) throw new IllegalStateException(
                "normal-input navigation stuck during " + label + " at " + player.blockPosition()
                        + "; target=" + target + ", distance=" + rounded(distance));
        return false;
    }

    private boolean navigateWithSafePath(Minecraft client, BlockPos target, double tolerance, String label) {
        var player = requirePlayer(client);
        var recoveringVertically = verticalRecoveryWaypoint != null && target.equals(verticalRecoveryGoal);
        var routeTarget = recoveringVertically ? verticalRecoveryWaypoint : target;
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(recoveringVertically ? 0.8 : tolerance, 0.8);
        if (!routeTarget.equals(navigationDestination) || !arrival.equals(navigationArrival)) {
            navigationDestination = routeTarget.immutable();
            navigationArrival = arrival;
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), routeTarget,
                    policy, arrival);
            navigationIndex = navigationStartIndex(player, navigationPath);
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            inputActions.add("observe:safety-weighted-route:" + label);
        }
        var dx = routeTarget.getX() + 0.5 - player.getX();
        var dz = routeTarget.getZ() + 0.5 - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        if (arrival.reached(player.getX(), player.getY(), player.getZ(), routeTarget)) {
            stopMovement(client);
            if (recoveringVertically) {
                safetyDiagnostics.add("navigation-replan:completed-safe-vertical-progress:"
                        + verticalRecoveryWaypoint);
                clearVerticalRecovery(true);
                resetNavigation();
                return false;
            }
            return true;
        }
        if (navigationPath.isEmpty()) {
            if (!recoveringVertically && beginVerticalRecovery(client, target, label)) return false;
            if (policy.smartNavigation()) throw new IllegalStateException(
                    "safe intelligent path unavailable before reaching " + label);
            return false;
        }
        if (navigationPoseDrifted(player)) {
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), routeTarget,
                    policy, arrival);
            navigationIndex = navigationStartIndex(player, navigationPath);
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            safetyDiagnostics.add("navigation-replan:pose-drift:" + label + ":from="
                    + player.blockPosition() + ":target=" + routeTarget);
            inputActions.add("observe:replan-from-current-pose:" + label);
            if (navigationPath.isEmpty() && policy.smartNavigation()) {
                throw new IllegalStateException("safe intelligent navigation could not continue toward " + label);
            }
            if (navigationPath.isEmpty()) return false;
        }
        while (navigationIndex < navigationPath.size()) {
            var waypoint = navigationPath.get(navigationIndex);
            if (player.distanceToSqr(Vec3.atCenterOf(waypoint)) <= 1.0) navigationIndex++;
            else break;
        }
        if (navigationIndex >= navigationPath.size()) {
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), routeTarget,
                    policy, arrival);
            navigationIndex = navigationStartIndex(player, navigationPath);
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            if (navigationPath.isEmpty()) {
                throw new IllegalStateException("safe intelligent navigation could not continue toward " + label);
            }
            return false;
        }
        var waypoint = navigationPath.get(navigationIndex);
        var waypointDx = waypoint.getX() + 0.5 - player.getX();
        var waypointDz = waypoint.getZ() + 0.5 - player.getZ();
        var waypointDistance = Math.sqrt(waypointDx * waypointDx + waypointDz * waypointDz);
        if (waypointDistance < navigationLastDistance - 0.02) navigationStuckTicks = 0;
        else navigationStuckTicks++;
        navigationLastDistance = waypointDistance;
        if (navigationStuckTicks > 90) {
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), routeTarget,
                    policy, arrival);
            navigationIndex = navigationStartIndex(player, navigationPath);
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            safetyDiagnostics.add("navigation-replan:" + label);
            if (navigationPath.isEmpty() && policy.smartNavigation()) {
                throw new IllegalStateException("safe intelligent navigation remained blocked during " + label);
            }
            return false;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(waypointDz, waypointDx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(!policy.highSafety() && horizontal > 7.0);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(player.onGround() && waypoint.getY() > player.getY() + 0.35);
        inputActions.add("move:safety-weighted-route:" + label);
        return false;
    }

    /** Replan when movement or a terrain edge leaves the actor outside the validated route envelope. */
    private boolean navigationPoseDrifted(LocalPlayer player) {
        var current = player.blockPosition();
        return navigationPath.stream().noneMatch(waypoint ->
                Math.abs(waypoint.getX() - current.getX()) <= 1
                        && Math.abs(waypoint.getY() - current.getY()) <= 1
                        && Math.abs(waypoint.getZ() - current.getZ()) <= 1);
    }

    private int navigationStartIndex(LocalPlayer player, List<BlockPos> path) {
        if (path.size() <= 1) return 0;
        return player.distanceToSqr(Vec3.atCenterOf(path.getFirst())) <= 1.0 ? 1 : 0;
    }

    private boolean beginVerticalRecovery(Minecraft client, BlockPos blockedTarget, String label) {
        var player = requirePlayer(client);
        if (player.blockPosition().getY() - blockedTarget.getY() < 3) return false;
        var path = NeoForgeSafePathPlanner.findSafeDescent(client.level, player.blockPosition(),
                blockedTarget, policy, rejectedVerticalRecoveryWaypoints);
        if (path.size() < 2) return false;
        verticalRecoveryGoal = blockedTarget.immutable();
        verticalRecoveryWaypoint = path.getLast().immutable();
        navigationDestination = verticalRecoveryWaypoint;
        navigationArrival = new NeoForgeSafePathPlanner.ArrivalSpec(0.8, 0.8);
        navigationPath = path;
        navigationIndex = navigationStartIndex(player, path);
        navigationStuckTicks = 0;
        navigationLastDistance = Double.POSITIVE_INFINITY;
        safetyDiagnostics.add("navigation-replan:safe-vertical-progress:" + player.blockPosition()
                + "->" + verticalRecoveryWaypoint + ":goal=" + blockedTarget);
        inputActions.add("observe:safe-vertical-route:" + label.replace(' ', '-'));
        return true;
    }

    private void clearVerticalRecovery(boolean clearRejected) {
        verticalRecoveryGoal = null;
        verticalRecoveryWaypoint = null;
        if (clearRejected) rejectedVerticalRecoveryWaypoints.clear();
    }

    private boolean movementExpected() {
        return switch (stage) {
            case FIND_LOOT, NAVIGATE_LOOT, FIND_STARTER_RESOURCE, NAVIGATE_STARTER_RESOURCE,
                    COLLECT_STARTER_RESOURCE,
                    FIND_STONE, MINE_STONE, PLAN_PREREQUISITE_ACQUISITION,
                    EXCAVATE_PREREQUISITE_ROUTE, FIND_IRON, MINE_IRON, FIND_GRAVEL, MINE_GRAVEL,
                    FIND_WATER, FIND_LAVA,
                    BUILD_BUCKET_PORTAL, FIND_SITE, ENTER_PORTAL -> true;
            default -> false;
        };
    }

    /**
     * The safety supervisor must distinguish deliberate stationary work from navigation.
     * In particular, mining a staircase block is progress even when the player does not
     * move for dozens of ticks.  Movement is only expected during the route-arrival and
     * post-break step-transition phases.
     */
    private boolean supervisorMovementExpected(Minecraft client) {
        if (stage == Stage.PLAN_PREREQUISITE_ACQUISITION) return false;
        if (stage != Stage.EXCAVATE_PREREQUISITE_ROUTE) {
            // Adaptive navigation has a validated A* route and its own bounded replan
            // contract.  Do not let the generic obstruction escape rewrite that route
            // while the safety supervisor still handles hazards, falls, threats, and
            // unsafe tool use below the movement contract.
            return policy.smartNavigation() ? false : movementExpected();
        }
        if (prerequisitePlan == null || prerequisitePlan.steps().isEmpty()) return false;
        var step = prerequisitePlan.steps().getFirst();
        // Step movement has its own bounded transaction and liveness sample.  Keep it
        // out of the generic movement supervisor so the supervisor cannot interrupt
        // normal forward input while the safe route is traversed.
        if (prerequisitePhase == 2) return false;
        return client.player != null && !atPrerequisiteFeet(client.player, step.fromFeet());
    }

    private String movementIntent() {
        var intentTarget = verticalRecoveryWaypoint != null ? verticalRecoveryWaypoint : switch (stage) {
            case NAVIGATE_STARTER_RESOURCE -> resourceMiningVantage != null ? resourceMiningVantage
                    : resourceSource == null ? null : resourceSource.anchor();
            case NAVIGATE_LOOT -> lootChest;
            default -> navigationDestination;
        };
        var target = intentTarget == null ? "pending" : intentTarget.toShortString();
        return stage + ":" + target;
    }

    private void resetNavigation() {
        navigationDestination = null;
        navigationArrival = null;
        navigationLastDistance = Double.POSITIVE_INFINITY;
        navigationStuckTicks = 0;
        navigationPath = List.of();
        navigationIndex = 0;
    }

    private BlockPos findTablePlacement(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        var candidate = origin.offset(dx, dy, dz);
                        var support = candidate.below();
                        if (!client.level.getBlockState(candidate).canBeReplaced()
                                || !client.level.getBlockState(candidate).getFluidState().isEmpty()
                                || client.level.getBlockState(support).getCollisionShape(client.level, support).isEmpty()
                                || !snapshot.safePlacementSite(candidate)) continue;
                        if (player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(candidate))) continue;
                        var eye = new Vec3(player.getX(), player.getEyeY(), player.getZ());
                        var aim = facePoint(support, Direction.UP);
                        if (eye.distanceTo(aim) > 4.5) continue;
                        var clip = client.level.clip(new ClipContext(eye, aim,
                                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                        if (clip instanceof BlockHitResult blockHit
                                && blockHit.getBlockPos().equals(support)
                                && blockHit.getDirection() == Direction.UP) return candidate.immutable();
                    }
                }
            }
        }
        throw new IllegalStateException("no clear normal-input placement position observed");
    }

    private boolean standable(Minecraft client, BlockPos feet) {
        return NeoForgeWorldSnapshot.capture(client.level, policy).bufferedWalkable(feet);
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    /** Start a vanilla destroy session, then keep the ordinary attack mapping held. */
    private void clickAndHoldAttack(Minecraft client, String label) {
        if (stageTicks % 10 == 1) {
            KeyMapping.click(client.options.keyAttack.getKey());
            inputActions.add("attack:key.attack-click-start:" + label);
        }
        client.options.keyAttack.setDown(true);
    }

    private void logStage(Minecraft client) {
        var player = client.player;
        var position = player == null ? "none" : player.blockPosition().toString();
        var inventoryLogs = player == null ? 0 : countLogs(player);
        var resourceBlocks = resourceSource == null ? 0 : resourceSource.blocks().size();
        var search = miningSearchTelemetry;
        var liveness = livenessTelemetry;
        System.out.println("[Lodestone Goal] nether stage=" + stage
                + " ticks=" + totalTicks + " stageTicks=" + stageTicks
                + " pos=" + position + " mineIndex=" + mineIndex
                + " inventoryLogs=" + inventoryLogs + " resourceBlocks=" + resourceBlocks
                + " meaningfulProgressTicks=" + (liveness == null ? 0 : liveness.ticksSinceMeaningfulProgress())
                + " displacement=" + (liveness == null ? 0.0 : rounded(liveness.displacement()))
                + " yawOnlyProgress=false"
                + " worldRevision=" + (liveness == null ? 0 : liveness.worldRevision())
                + " recoveryCount=" + (liveness == null ? 0 : liveness.recoveryCount())
                + " frontier=" + (livenessFrontier == null ? "none" : livenessFrontier.waypoint())
                + " candidateOutcome=" + (search == null ? "none" : search.outcome())
                + " candidateCursor=" + (search == null ? "0/0" : search.cursor() + "/" + search.candidateCount())
                + " candidatesEnumerated=" + (search == null ? 0 : search.enumerated())
                + " prefilterRejected=" + (search == null ? 0 : search.prefilterRejected())
                + " candidatesValidated=" + (search == null ? 0 : search.validated())
                + " candidateCacheHits=" + (search == null ? 0 : search.cacheHits())
                + " rejectionHistogram=" + (search == null ? Map.of() : search.rejectionHistogram())
                + " intelligenceLayer=" + policy.intelligenceLayer()
                + " acquisitionStrategy=" + prerequisiteAcquisitionStrategy
                + " acquisitionWhy=" + prerequisiteAcquisitionReason
                + " acquisitionResource=" + (prerequisiteResource == null ? "none" : prerequisiteResource.id())
                + " acquisitionStep=" + prerequisiteCommittedSteps + "/" + MAX_PREREQUISITE_STAIR_STEPS
                + " acquisitionBlocks=" + prerequisiteBlocksBroken + "/" + MAX_PREREQUISITE_BLOCKS_BROKEN
                + " acquisitionRejections=" + prerequisitePlanRejections
                + " acquisitionPhase=" + prerequisitePhase
                + " acquisitionStepMoveTicks=" + prerequisiteStepMovementTicks
                + " navigationDestination=" + (navigationDestination == null ? "none" : navigationDestination)
                + " navigationIndex=" + navigationIndex + "/" + navigationPath.size()
                + " resourceVantage=" + (resourceMiningVantage == null ? "none" : resourceMiningVantage)
                + " resourceApproachOnly=" + resourceApproachOnly
                + " rejectedResourceVantages=" + rejectedResourceVantages.size()
                + " acquisitionRetreat=" + (prerequisiteRetreatOrigin == null
                ? "none" : prerequisiteRetreatOrigin)
                + " acquisitionGate=" + prerequisiteGateOutcome);
    }

    private void tickClicks(Minecraft client) {
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        if (!clicks.isEmpty()) {
            var click = clicks.removeFirst();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
                throw new IllegalStateException("container closed during natural portal chest loot");
            }
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, click.slot(), click.button(),
                    click.type(), requirePlayer(client));
            inputActions.add("container-click:" + click.label());
            clickDelay = 7;
            return;
        }
        var complete = clicksComplete;
        clicksComplete = null;
        if (complete != null) complete.run();
    }

    private void startClicks(List<ClickOp> sequence, Runnable complete) {
        if (!clicks.isEmpty() || clicksComplete != null) {
            throw new IllegalStateException("natural portal chest click sequence already active");
        }
        clicks.addAll(sequence);
        clicksComplete = complete;
        clickDelay = 2;
    }

    private void closeContainer(Minecraft client) {
        if (client.screen != null) {
            client.screen.keyPressed(256, 0, 0);
            inputActions.add("ui:key.escape-close-portal-chest");
        }
    }

    private static int countItem(LocalPlayer player, Item item) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countLogs(LocalPlayer player) {
        var count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isLogStack(player.getInventory().getItem(slot))) count += player.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private static int countPlanks(LocalPlayer player) {
        var count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(ItemTags.PLANKS)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isLogStack(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS);
    }

    private static int findPlayerItemSlot(AbstractContainerScreen<?> screen,
                                          java.util.function.Predicate<ItemStack> predicate) {
        var slots = screen.getMenu().slots;
        var start = screen instanceof InventoryScreen ? 9 : 10;
        for (int index = start; index < slots.size(); index++) {
            var stack = slots.get(index).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) return index;
        }
        return -1;
    }

    private static AbstractContainerScreen<?> requireAnyContainer(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            throw new IllegalStateException("expected a visible container screen");
        }
        return screen;
    }

    private static <T extends AbstractContainerScreen<?>> T requireContainer(Minecraft client, Class<T> type) {
        if (!type.isInstance(client.screen)) {
            throw new IllegalStateException("expected " + type.getSimpleName() + " but saw "
                    + (client.screen == null ? "no screen" : client.screen.getClass().getSimpleName()));
        }
        return type.cast(client.screen);
    }

    private void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint && forward);
        client.options.keyJump.setDown(jump);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int countNonEmpty(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) count++;
        }
        return count;
    }

    private void requirePrerequisiteTool(Minecraft client, Item item, String action) {
        if (!policy.prerequisitePlanningEnabled()) return;
        var player = requirePlayer(client);
        var visiblyEquipped = player.getMainHandItem().is(item);
        if (countItem(player, item) < 1 && !visiblyEquipped) {
            throw new IllegalStateException("intelligent prerequisite missing before " + action + ": " + item);
        }
        if (hotbarSlot(player, item) < 0 && !visiblyEquipped) {
            throw new IllegalStateException("intelligent prerequisite was not promoted to the hotbar before "
                    + action + ": " + item);
        }
        if (visiblyEquipped && countItem(player, item) < 1) {
            safetyDiagnostics.add("prerequisite-readback:accepted-visible-main-hand:" + item);
        }
    }

    private void requireEquippedTool(Minecraft client, Item item, String action) {
        if (!policy.prerequisitePlanningEnabled()) return;
        requirePrerequisiteTool(client, item, action);
        if (!requirePlayer(client).getMainHandItem().is(item)) {
            throw new IllegalStateException("intelligent prerequisite tool was not equipped before "
                    + action + ": " + item);
        }
    }

    private static int hotbarSlot(LocalPlayer player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static int emptyHotbarSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private void selectHotbar(Minecraft client, int slot) {
        if (client.player.getInventory().selected == slot) return;
        KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
        inputActions.add("select:hotbar-key-" + (slot + 1));
    }

    private static Vec3 facePoint(BlockPos support, Direction face) {
        return Vec3.atCenterOf(support).add(face.getStepX() * 0.49,
                face.getStepY() * 0.49, face.getStepZ() * 0.49);
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        var eye = new Vec3(player.getX(), player.getEyeY(), player.getZ());
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private void transition(Stage next, int delay) {
        if (next != stage) clearVerticalRecovery(true);
        if (next != stage) livenessFrontier = null;
        stage = next;
        stageTicks = 0;
        waitTicks = delay;
        recipeStarted = false;
        explorationLastPosition = null;
        explorationNoProgressTicks = 0;
        explorationWaypoint = null;
    }

    private void resetRecipeState() {
        recipeStarted = false;
    }

    private void announce(Minecraft client, String message) {
        if (suppressInGameMessages) return;
        var text = Component.literal("[Lodestone Goal] " + message);
        if (client.player != null) client.player.displayClientMessage(text, true);
        client.gui.getChat().addMessage(text);
        inGameMessagesEmitted += 2;
    }

    private static void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }

    private static LocalPlayer requirePlayer(Minecraft client) {
        if (client.player == null || client.level == null) {
            throw new IllegalStateException("client player/world unavailable");
        }
        return client.player;
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private record Placement(BlockPos target, BlockPos support) {
    }

    private record ClickOp(int slot, int button, ClickType type, String label) {
    }

    private record Site(BlockPos base, BlockPos standPosition, Direction.Axis axis) {
    }

    private record SafeFrontier(BlockPos waypoint, int pathLength, int retreatLength, String provenance) {
    }

    private record PrerequisiteResource(String id, Item tool, Item output, int successQuantity,
                                        Predicate<BlockState> discovery, Predicate<BlockState> harvest,
                                        Predicate<BlockState> routeMaterial, Stage rescanStage) {
    }

    private enum MiningTargetPreparation {
        READY,
        MOVING,
        UNREACHABLE
    }

    private enum Stage {
        WAIT_WORLD,
        FIND_LOOT,
        NAVIGATE_LOOT,
        LOOT_CHEST,
        FIND_STARTER_RESOURCE,
        NAVIGATE_STARTER_RESOURCE,
        MINE_STARTER_RESOURCE,
        COLLECT_STARTER_RESOURCE,
        OPEN_INVENTORY,
        CRAFT_PLANKS,
        CRAFT_TABLE,
        CRAFT_STICKS,
        CRAFT_WOOD_PICK,
        MOVE_TABLE_TO_HOTBAR,
        MOVE_WOOD_PICK_TO_HOTBAR,
        CLOSE_INVENTORY,
        PLACE_TABLE,
        OPEN_TABLE,
        CRAFT_STONE_PICK,
        CRAFT_FURNACE,
        MOVE_TOOL_TO_HOTBAR,
        MOVE_FURNACE_TO_HOTBAR,
        CLOSE_TABLE,
        EQUIP_WOOD_PICK,
        EQUIP_STONE_PICK,
        FIND_STONE,
        MINE_STONE,
        PLAN_PREREQUISITE_ACQUISITION,
        EXCAVATE_PREREQUISITE_ROUTE,
        FIND_IRON,
        MINE_IRON,
        FIND_GRAVEL,
        MINE_GRAVEL,
        PLACE_FURNACE,
        OPEN_FURNACE,
        SMELT_IRON,
        CLOSE_FURNACE,
        CRAFT_BUCKET,
        CRAFT_FLINT_STEEL,
        MOVE_BUCKET_TO_HOTBAR,
        MOVE_FLINT_STEEL_TO_HOTBAR,
        FIND_WATER,
        FIND_LAVA,
        FILL_WATER_BUCKET,
        FILL_LAVA_BUCKET,
        BUILD_BUCKET_PORTAL,
        CLEAR_PORTAL_SCAFFOLD,
        FIND_SITE,
        BUILD_PORTAL,
        LIGHT_PORTAL,
        ENTER_PORTAL,
        COMPLETE_DELAY
    }
}
