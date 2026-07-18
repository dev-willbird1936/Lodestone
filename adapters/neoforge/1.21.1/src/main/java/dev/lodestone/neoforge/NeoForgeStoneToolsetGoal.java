// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded client-input actor for the registered B3 "Stone Age" survival goal.
 *
 * <p>Like {@link NeoForgeSurvivalTreeGoal} (a read-only pattern reference for this class, never
 * edited by it), this actor has no server/world mutation API: logs are hand-mined by holding the
 * ordinary attack mapping, recipes are assembled in visible vanilla menus through the same
 * container-click path the adapter already exposes, and the crafting table and furnace are placed
 * with the normal use mapping. It goes one step further than the registered wooden-axe-tree goal by
 * also crafting a wooden pickaxe, mining cobblestone with it through a self-dug and self-reversed
 * staircase, crafting a full stone toolset, crafting and placing a furnace, and proving the run
 * ends back on the surface rather than left in its own mined staircase.</p>
 *
 * <p>Every mining loop in this class verifies the intended target via {@link LocalPlayer#pick} on
 * every tick before holding the attack mapping, and fails with a bounded, typed error rather than
 * holding attack blindly for the whole stall timeout. Every block-placement candidate search
 * requires the support block's collision shape to be a full cube ({@link Block#isShapeFullBlock})
 * before aiming at its nominal top-face point, since a partial-height support block (a slab, a
 * snow layer, and so on) would put that nominal point outside the block's real collision shape.</p>
 */
final class NeoForgeStoneToolsetGoal implements NeoForgeResumableGoal {
    private static final int HAND_LOGS_REQUIRED = 4;
    private static final int COBBLESTONE_TARGET = 18;
    private static final int MAX_TOTAL_TICKS = 9_200;
    private static final int MAX_SEARCH_ATTEMPTS = 16;
    private static final int MAX_TREE_RE_ELECTIONS = 6;
    private static final int MAX_SHAFT_STEPS = 48;
    private static final int MAX_SHAFT_DIRECTION_RETRIES = 4;
    private static final int MAX_MINING_STALL_TICKS = 320;

    private InvocationContext invocation;
    private CompletableFuture<Map<String, Object>> result;
    private final boolean suppressInGameMessages;
    private final NeoForgeGoalPolicy policy;
    private final LinkedHashSet<String> inputActions = new LinkedHashSet<>();
    private final List<String> navigationDiagnostics = new ArrayList<>();
    private final List<String> safetyDiagnostics = new ArrayList<>();
    private final ArrayDeque<ClickOp> clicks = new ArrayDeque<>();
    private final NeoForgeGoalSupervisor supervisor;
    private final String continuationToken = UUID.randomUUID().toString();

    private Stage stage = Stage.WAIT_WORLD;
    private String requestedCheckpoint;
    private boolean paused;
    private boolean finished;
    private Runnable clicksComplete;
    private int clickDelay;
    private int waitTicks;
    private int totalTicks;
    private int stageTicks;
    private Stage afterTableOpen;
    private Stage afterTableClose;

    // World setup and terminal accounting.
    private long worldGameTimeAtStart;
    private String worldName = "fresh-survival-world";
    private boolean survival;
    private boolean freshWorld;
    private int initialSurfaceY;
    private int inGameMessagesEmitted;
    private int obstructionBreaks;
    private double distanceTraveled;
    private double lastTickX = Double.NaN;
    private double lastTickZ = Double.NaN;

    // Hand-mined logs and wooden tools.
    private int searchAttempts;
    private int treeReElections;
    private int mineIndex;
    private int handMinedLogs;
    private int planksCrafted;
    private int sticksCrafted;
    private boolean craftingTableCrafted;
    private boolean woodenPickaxeCrafted;
    private boolean woodenPickaxeEquipped;
    private boolean woodenAxeCrafted;
    private TreePlan resourceTree;
    private final Set<Long> blacklistedTrees = new HashSet<>();
    private BlockPos tablePosition;
    private BlockPos tableInteractionVantage;
    private int tableHotbarSlot = -1;
    private int woodenPickaxeHotbarSlot = -1;
    private int woodenAxeHotbarSlot = -1;
    private int stonePickaxeHotbarSlot = -1;
    private int stoneAxeHotbarSlot = -1;
    private int stoneSwordHotbarSlot = -1;
    private int stoneShovelHotbarSlot = -1;
    private int furnaceHotbarSlot = -1;

    // Staircase digging and cobblestone mining.
    private Direction shaftFacing;
    private final List<BlockPos> shaftPath = new ArrayList<>();
    private int shaftIndex;
    private int shaftDirectionRetries;
    private BlockPos pendingShaftTarget;
    private boolean pendingShaftWasStone;
    private int cobblestoneMinedCount;
    private boolean stonePickaxeCrafted;
    private boolean stoneAxeCrafted;
    private boolean stoneSwordCrafted;
    private boolean stoneShovelCrafted;
    private boolean furnaceCrafted;
    private boolean furnacePlaced;
    private BlockPos furnacePosition;
    private boolean endedOnSurface;
    private boolean canSeeSkyAtEnd;
    private BlockPos activeMiningTarget;
    private int miningTargetTicks;

    // Shared navigation state (duplicated from NeoForgeSurvivalTreeGoal's own copy - this actor is
    // self-contained on purpose; see the class documentation above).
    private BlockPos navigationDestination;
    private List<BlockPos> navigationPath = List.of();
    private int navigationIndex;
    private int navigationStuckTicks;
    private int navigationReplans;
    private int totalNavigationReplans;
    private int navigationDetourTicks;
    private int navigationDetourSign = 1;
    private boolean navigationDirectFallback;
    private double navigationLastDistance = Double.POSITIVE_INFINITY;

    NeoForgeStoneToolsetGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
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
        if (!paused || finished) throw new IllegalStateException("stone toolset goal is not resumable");
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
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("TIMEOUT_BUDGET: cause=timeout:" + stageCause()
                        + "; stone toolset goal exceeded its bounded input budget" + telemetrySuffix());
            }
            failFastOnDeath(client);
            trackDistance(client);

            if (supervisor.tick(client)) return;

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
                case SEARCH_TREE -> searchTree(client);
                case NAVIGATE_TREE -> navigateTree(client);
                case MINE_LOGS -> mineLogs(client);
                case COLLECT_LOGS -> collectLogs(client);
                case OPEN_INVENTORY -> openInventory(client);
                case CRAFT_PLANKS -> craftPlanks(client);
                case CRAFT_TABLE -> craftTable(client);
                case CRAFT_STICKS -> craftSticks(client);
                case MOVE_TABLE_TO_HOTBAR -> moveItemToHotbar(client, Items.CRAFTING_TABLE, slot -> tableHotbarSlot = slot,
                        Stage.CLOSE_INVENTORY);
                case CLOSE_INVENTORY -> closeInventory(client);
                case PLACE_TABLE -> placeTable(client);
                case OPEN_TABLE -> openTable(client);
                case CRAFT_WOODEN_PICKAXE -> craftWoodenPickaxe(client);
                case MOVE_WOODEN_PICKAXE_TO_HOTBAR -> moveItemToHotbar(client, Items.WOODEN_PICKAXE,
                        slot -> woodenPickaxeHotbarSlot = slot, Stage.CRAFT_WOODEN_AXE);
                case CRAFT_WOODEN_AXE -> craftWoodenAxe(client);
                case MOVE_WOODEN_AXE_TO_HOTBAR -> moveItemToHotbar(client, Items.WOODEN_AXE,
                        slot -> woodenAxeHotbarSlot = slot, Stage.CLOSE_TABLE);
                case CLOSE_TABLE -> closeTable(client);
                case EQUIP_WOODEN_PICKAXE -> equipWoodenPickaxe(client);
                case BEGIN_DESCENT -> beginDescent(client);
                case DESCEND_AND_MINE_STONE -> descendAndMineStone(client);
                case COLLECT_STONE_DROPS -> collectStoneDrops(client);
                case ASCEND_SHAFT -> ascendShaft(client);
                case NAVIGATE_TO_TABLE -> navigateToTable(client);
                case CRAFT_STONE_PICKAXE -> craftStonePickaxe(client);
                case MOVE_STONE_PICKAXE_TO_HOTBAR -> moveItemToHotbar(client, Items.STONE_PICKAXE,
                        slot -> stonePickaxeHotbarSlot = slot, Stage.CRAFT_STONE_AXE);
                case CRAFT_STONE_AXE -> craftStoneAxe(client);
                case MOVE_STONE_AXE_TO_HOTBAR -> moveItemToHotbar(client, Items.STONE_AXE,
                        slot -> stoneAxeHotbarSlot = slot, Stage.CRAFT_STONE_SWORD);
                case CRAFT_STONE_SWORD -> craftStoneSword(client);
                case MOVE_STONE_SWORD_TO_HOTBAR -> moveItemToHotbar(client, Items.STONE_SWORD,
                        slot -> stoneSwordHotbarSlot = slot, Stage.CRAFT_STONE_SHOVEL);
                case CRAFT_STONE_SHOVEL -> craftStoneShovel(client);
                case MOVE_STONE_SHOVEL_TO_HOTBAR -> moveItemToHotbar(client, Items.STONE_SHOVEL,
                        slot -> stoneShovelHotbarSlot = slot, Stage.CRAFT_FURNACE_ITEM);
                case CRAFT_FURNACE_ITEM -> craftFurnaceItem(client);
                case MOVE_FURNACE_TO_HOTBAR -> moveItemToHotbar(client, Items.FURNACE,
                        slot -> furnaceHotbarSlot = slot, Stage.CLOSE_TABLE);
                case EQUIP_STONE_PICKAXE -> equipStonePickaxe(client);
                case PLACE_FURNACE -> placeFurnace(client);
                case VERIFY -> verify(client);
                case COMPLETE_DELAY -> completeAfterVisibleDelay(client);
            }
        } catch (Throwable failure) {
            releaseInput(client);
            finished = true;
            paused = false;
            result.completeExceptionally(failure);
        }
    }

    // ---------------------------------------------------------------------------------------
    // World setup
    // ---------------------------------------------------------------------------------------

    private void waitForFreshWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (stageTicks < 40) return;

        var player = client.player;
        survival = client.gameMode.getPlayerMode() == GameType.SURVIVAL;
        worldGameTimeAtStart = client.level.getGameTime();
        var inventoryEmpty = countNonEmpty(player) == 0;
        freshWorld = survival && inventoryEmpty && worldGameTimeAtStart <= 6_000;
        var server = client.getSingleplayerServer();
        if (server != null && server.getWorldData() != null) worldName = server.getWorldData().getLevelName();
        if (!survival) throw new IllegalStateException("goal world is not survival");
        if (policy.allowCommands()) {
            throw new IllegalStateException("survival stone-toolset workflow refuses allowCommands=true");
        }
        if (!policy.allowBlockBreaking() || !policy.allowBlockPlacing()) {
            throw new IllegalStateException("survival stone-toolset workflow requires block breaking and placing permissions");
        }
        if (!freshWorld) {
            throw new IllegalStateException("goal requires a fresh empty world; gameTime=" + worldGameTimeAtStart
                    + ", nonEmptySlots=" + countNonEmpty(player));
        }
        initialSurfaceY = player.blockPosition().getY();
        announce(client, "SURVIVAL READY - fresh world, empty inventory, surfaceY=" + initialSurfaceY);
        transition(Stage.SEARCH_TREE, 40);
    }

    private void failFastOnDeath(Minecraft client) {
        var player = client.player;
        if (player == null || (!player.isDeadOrDying() && player.getHealth() > 0.0F)) return;
        var source = player.getLastDamageSource();
        var cause = source == null ? "unknown" : source.type().msgId();
        throw new IllegalStateException("PLAYER_DIED: cause=died:" + cause
                + "; player died during " + stage + " at " + player.blockPosition() + telemetrySuffix());
    }

    private void trackDistance(Minecraft client) {
        var player = client.player;
        if (player == null) return;
        if (!Double.isNaN(lastTickX)) {
            var dx = player.getX() - lastTickX;
            var dz = player.getZ() - lastTickZ;
            var step = Math.sqrt(dx * dx + dz * dz);
            if (step < 1.5) distanceTraveled += step;
        }
        lastTickX = player.getX();
        lastTickZ = player.getZ();
    }

    private String stageCause() {
        return switch (stage) {
            case WAIT_WORLD -> "world-setup";
            case SEARCH_TREE, NAVIGATE_TREE -> "search";
            case NAVIGATE_TO_TABLE -> "navigation";
            case MINE_LOGS, COLLECT_LOGS, DESCEND_AND_MINE_STONE, COLLECT_STONE_DROPS, ASCEND_SHAFT -> "mining";
            case VERIFY, COMPLETE_DELAY -> "verify";
            default -> "crafting";
        };
    }

    private String telemetrySuffix() {
        return " | telemetry{stage=" + stage
                + ",ticks=" + totalTicks
                + ",replans=" + totalNavigationReplans
                + ",obstructionBreaks=" + obstructionBreaks
                + ",safetyInterventions=" + safetyDiagnostics.size()
                + ",distance=" + rounded(distanceTraveled)
                + ",handMinedLogs=" + handMinedLogs
                + ",cobblestoneMined=" + cobblestoneMinedCount
                + ",shaftDepth=" + Math.max(0, shaftPath.size() - 1)
                + "}";
    }

    // ---------------------------------------------------------------------------------------
    // Tree search, navigation, and hand-mining
    // ---------------------------------------------------------------------------------------

    private void searchTree(Minecraft client) {
        var plans = scanTrees(client);
        // scanTrees() itself accepts trees with as few as 3 logs (NeoForgeSurvivalTreeGoal's own
        // hand-mining requirement), but this actor needs HAND_LOGS_REQUIRED=4 to leave enough wood
        // for the whole run's planks/sticks budget - a smaller elected tree would run out mid-mine.
        var reachableCandidates = plans.stream()
                .filter(plan -> !blacklistedTrees.contains(plan.base().asLong())
                        && plan.logs().size() >= HAND_LOGS_REQUIRED).toList();
        var scanDiagnostic = "tree scan: observed=" + plans.size()
                + ", candidates=" + reachableCandidates.size()
                + ", blacklisted=" + blacklistedTrees.size()
                + ", player=" + requirePlayer(client).blockPosition() + ", pass=" + (searchAttempts + 1);
        navigationDiagnostics.add(scanDiagnostic);
        announce(client, scanDiagnostic);
        if (!reachableCandidates.isEmpty()) {
            resourceTree = reachableCandidates.get(0);
            resetNavigation();
            announce(client, "Found a reachable natural tree - walking to gather wood by hand");
            transition(Stage.NAVIGATE_TREE, 20);
            return;
        }
        if (searchAttempts >= MAX_SEARCH_ATTEMPTS) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=resource:exhausted; "
                    + "no reachable small tree observed after bounded visible exploration; blacklisted="
                    + blacklistedTrees.size() + telemetrySuffix());
        }
        searchAttempts++;
        var player = requirePlayer(client);
        player.setYRot(player.getYRot() + 55.0F);
        player.setYHeadRot(player.getYRot());
        inputActions.add("look:visible-search-sweep");
        announce(client, "Searching for nearby trees - exploration pass " + searchAttempts);
        setMovement(client, true, true, player.isInWater() || (player.onGround() && (stageTicks % 30 < 5)));
        if (stageTicks >= 120) transition(Stage.SEARCH_TREE, 20);
    }

    private void navigateTree(Minecraft client) {
        try {
            if (navigate(client, resourceTree.base(), "resource tree")) {
                stopMovement(client);
                mineIndex = 0;
                announce(client, "Gathering wood by HAND - 0/" + HAND_LOGS_REQUIRED + " logs");
                transition(Stage.MINE_LOGS, 20);
            }
        } catch (IllegalStateException failure) {
            if (!blacklistAndReElect(client, failure)) throw failure;
        }
    }

    private boolean blacklistAndReElect(Minecraft client, IllegalStateException failure) {
        if (resourceTree == null || !isRecoverableNavigationFailure(failure.getMessage())) return false;
        if (++treeReElections > MAX_TREE_RE_ELECTIONS) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                    + "resource tree re-election budget exhausted after " + MAX_TREE_RE_ELECTIONS
                    + " blacklisted trees; last=" + resourceTree.base() + "; " + failure.getMessage()
                    + telemetrySuffix());
        }
        blacklistedTrees.add(resourceTree.base().asLong());
        stopMovement(client);
        resetNavigation();
        var diagnostic = "navigation failure toward resource tree at " + resourceTree.base()
                + " - blacklisting and re-electing (" + treeReElections + "/" + MAX_TREE_RE_ELECTIONS
                + "): " + failure.getMessage();
        navigationDiagnostics.add(diagnostic);
        safetyDiagnostics.add("tree-re-election:" + resourceTree.base());
        announce(client, "Route to resource tree failed - selecting a different tree");
        transition(Stage.SEARCH_TREE, 10);
        return true;
    }

    static boolean isRecoverableNavigationFailure(String message) {
        if (message == null) return false;
        return message.contains("route unavailable to")
                || message.contains("route remained unavailable to")
                || message.contains("remained obstructed before reaching")
                || message.contains("navigation timed out before reaching")
                || message.contains("detours remained obstructed before reaching");
    }

    private void mineLogs(Minecraft client) {
        if (handMinedLogs >= HAND_LOGS_REQUIRED) {
            stopAttack(client);
            transition(Stage.COLLECT_LOGS, 0);
            return;
        }
        var player = requirePlayer(client);
        var logs = resourceTree.logs();
        if (mineIndex >= logs.size()) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=resource:exhausted; "
                    + "resource tree ran out of logs before " + HAND_LOGS_REQUIRED + " were hand-mined"
                    + telemetrySuffix());
        }
        var target = logs.get(mineIndex);
        var state = client.level.getBlockState(target);
        if (!state.is(BlockTags.LOGS) && !state.isAir()) {
            stopAttack(client);
            if (policy.obstructionMiningEnabled() && breakVisibleObstruction(client, target)) return;
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                    + "resource log target is occupied by non-log block " + target
                    + ": " + state.getBlock().getName().getString() + telemetrySuffix());
        }
        if (state.isAir()) {
            handMinedLogs++;
            mineIndex++;
            activeMiningTarget = null;
            announce(client, "Hand-mined log " + handMinedLogs + "/" + HAND_LOGS_REQUIRED);
            waitTicks = 10;
            return;
        }
        mineVerified(client, target, "hand", MAX_MINING_STALL_TICKS);
    }

    private void collectLogs(Minecraft client) {
        var player = requirePlayer(client);
        var logs = countLogs(player);
        if (logs >= HAND_LOGS_REQUIRED) {
            stopMovement(client);
            announce(client, "Collected " + logs + " hand-mined logs - opening inventory");
            transition(Stage.OPEN_INVENTORY, 20);
            return;
        }
        var drops = client.level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(20.0),
                        entity -> entity.isAlive() && isLogStack(entity.getItem()))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        if (!drops.isEmpty()) {
            walkToward(client, drops.getFirst().position(), drops.getFirst().getY() > player.getY() + 0.5);
            inputActions.add("move:walk-to-observed-log-drop");
        } else {
            lookAt(player, resourceTree.base());
            setMovement(client, true, false, player.isInWater() || (player.onGround() && stageTicks % 40 < 5));
            inputActions.add("move:search-near-resource-tree-for-drops");
        }
        if (stageTicks > 600) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:collection; "
                + "hand-mined log drops were not collected through movement; inventory=" + logs
                + telemetrySuffix());
    }

    // ---------------------------------------------------------------------------------------
    // Crafting: planks, table, sticks, wooden tools
    // ---------------------------------------------------------------------------------------

    private void openInventory(Minecraft client) {
        if (client.screen instanceof InventoryScreen) {
            announce(client, "Crafting planks in visible 2x2 inventory grid");
            transition(Stage.CRAFT_PLANKS, 15);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen before inventory crafting: " + client.screen.getClass().getName());
        KeyMapping.click(client.options.keyInventory.getKey());
        inputActions.add("ui:key.inventory");
        if (stageTicks > 80) throw new IllegalStateException("inventory screen did not open from normal key input");
    }

    private void craftPlanks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        var logSlot = findPlayerItemSlot(screen, NeoForgeStoneToolsetGoal::isLogStack);
        if (logSlot < 0) throw new IllegalStateException("no collected log is present in player inventory");
        startClicks(List.of(
                new ClickOp(logSlot, 0, ClickType.PICKUP, "pick-up-hand-mined-logs"),
                new ClickOp(1, 0, ClickType.PICKUP, "place-logs-in-2x2-grid"),
                new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-planks")), () -> {
            planksCrafted = countPlanks(requirePlayer(client));
            if (planksCrafted < 16) throw new IllegalStateException("visible plank recipe produced only " + planksCrafted);
            announce(client, "Crafted " + planksCrafted + " planks - crafting table next");
            transition(Stage.CRAFT_TABLE, 20);
        });
    }

    /**
     * Crafting-table recipe: any full contiguous 2x2 of planks. Deliberately uses slots 1,2,4,5
     * (the top-left 2x2 sub-square of the 3x3 grid, row-major slot numbering confirmed against this
     * codebase's own already-shipped stone-pickaxe recipe in {@code NeoForgeNetherGoal} - top row
     * slots 1,2,3 plus center-column slots 5,8, which only matches real Minecraft's pickaxe shape
     * under row-major numbering) rather than a non-adjacent run of slots.
     */
    private void craftTable(Minecraft client) {
        craftGeneric(client, List.of(planksIngredient(new int[]{1, 2, 4, 5})),
                Items.CRAFTING_TABLE, 1, () -> {
                    craftingTableCrafted = true;
                    announce(client, "Crafting table complete - crafting sticks in visible grid");
                    transition(Stage.CRAFT_STICKS, 20);
                });
    }

    /** Sticks: two planks stacked in one column (slots 1 and 4), batched three-high for a surplus. */
    private void craftSticks(Minecraft client) {
        craftGeneric(client, List.of(planksIngredient(new int[]{1, 1, 1, 4, 4, 4})),
                Items.STICK, 10, () -> {
                    sticksCrafted = countItem(requirePlayer(client), Items.STICK);
                    announce(client, "Crafted " + sticksCrafted + " sticks - moving table to hotbar");
                    transition(Stage.MOVE_TABLE_TO_HOTBAR, 20);
                });
    }

    private void closeInventory(Minecraft client) {
        if (client.screen == null) {
            var preferred = resourceTree.base();
            tablePosition = isClearPlacement(client, preferred) ? preferred : findClearPlacement(client, preferred, 4);
            announce(client, "Selected clear crafting-table placement at " + tablePosition
                    + " using read-only collision checks");
            transition(Stage.PLACE_TABLE, 15);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-inventory");
        if (stageTicks > 80) throw new IllegalStateException("inventory screen did not close");
    }

    private void placeTable(Minecraft client) {
        var player = requirePlayer(client);
        if (client.level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
            announce(client, "Crafting table placed with normal use input - opening 3x3 grid");
            afterTableOpen = Stage.CRAFT_WOODEN_PICKAXE;
            transition(Stage.OPEN_TABLE, 25);
            return;
        }
        if (tableHotbarSlot < 0) throw new IllegalStateException("crafting table hotbar slot is unknown");
        selectHotbar(client, tableHotbarSlot);
        if (!player.getMainHandItem().is(Items.CRAFTING_TABLE)) {
            if (stageTicks > 240) throw new IllegalStateException("normal hotbar selection did not hold the crafting table");
            return;
        }
        placeBlockAgainstSupport(client, tablePosition, "table placement", 1_200);
    }

    private void openTable(Minecraft client) {
        if (client.screen instanceof CraftingScreen) {
            tableInteractionVantage = null;
            announce(client, "3x3 crafting table open");
            transition(afterTableOpen, 20);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening crafting table");
        var player = requirePlayer(client);
        if (tableInteractionVantage != null) {
            if (navigate(client, tableInteractionVantage, "crafting-table interaction vantage")) {
                tableInteractionVantage = null;
                stageTicks = 0;
            }
            return;
        }
        lookAt(player, tablePosition);
        var hit = player.pick(5.0, 0.0F, false);
        var sightBlocked = !(hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(tablePosition));
        if (sightBlocked && stageTicks > 20) {
            var relocated = findInteractionVantage(client, tablePosition);
            if (relocated == null) throw new IllegalStateException("no unobstructed crafting-table interaction vantage");
            tableInteractionVantage = relocated;
            stageTicks = 0;
            return;
        }
        if (!sightBlocked && stageTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-crafting-table");
        }
        if (stageTicks > 120) throw new IllegalStateException("normal use input did not open crafting table screen");
    }

    /**
     * Pickaxe shape: three planks/cobble across the top row (slots 1,2,3) plus two sticks down the
     * center column (slots 5,8) - matches this codebase's already-shipped stone-pickaxe recipe in
     * {@code NeoForgeNetherGoal}.
     */
    private void craftWoodenPickaxe(Minecraft client) {
        craftGeneric(client, List.of(
                        planksIngredient(new int[]{1, 2, 3}),
                        stickIngredient(new int[]{5, 8})),
                Items.WOODEN_PICKAXE, 1, () -> {
                    woodenPickaxeCrafted = true;
                    announce(client, "WOODEN PICKAXE crafted - moving it to hotbar");
                    transition(Stage.MOVE_WOODEN_PICKAXE_TO_HOTBAR, 25);
                });
    }

    /** Axe shape: planks 1,2,4 plus sticks 5,8 - the exact shape already shipped for the wooden axe. */
    private void craftWoodenAxe(Minecraft client) {
        craftGeneric(client, List.of(
                        planksIngredient(new int[]{1, 2, 4}),
                        stickIngredient(new int[]{5, 8})),
                Items.WOODEN_AXE, 1, () -> {
                    woodenAxeCrafted = true;
                    announce(client, "WOODEN AXE crafted - moving it to hotbar");
                    transition(Stage.MOVE_WOODEN_AXE_TO_HOTBAR, 25);
                });
    }

    private void closeTable(Minecraft client) {
        if (client.screen == null) {
            transition(afterTableClose, 15);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-crafting-table");
        if (stageTicks > 80) throw new IllegalStateException("crafting table screen did not close");
    }

    private void equipWoodenPickaxe(Minecraft client) {
        selectHotbar(client, woodenPickaxeHotbarSlot);
        var player = requirePlayer(client);
        woodenPickaxeEquipped = player.getMainHandItem().is(Items.WOODEN_PICKAXE);
        if (!woodenPickaxeEquipped) {
            if (stageTicks > 80) throw new IllegalStateException("normal hotbar input did not equip wooden pickaxe");
            return;
        }
        announce(client, "WOODEN PICKAXE EQUIPPED - beginning descent to mine cobblestone");
        transition(Stage.BEGIN_DESCENT, 20);
        pauseAtCheckpoint(client, "wooden-tools");
    }

    // ---------------------------------------------------------------------------------------
    // Staircase digging and cobblestone mining
    // ---------------------------------------------------------------------------------------

    private void beginDescent(Minecraft client) {
        var player = requirePlayer(client);
        stopMovement(client);
        shaftFacing = player.getDirection();
        shaftPath.clear();
        shaftPath.add(player.blockPosition().immutable());
        shaftIndex = 0;
        shaftDirectionRetries = 0;
        activeMiningTarget = null;
        announce(client, "Digging a reversible staircase facing " + shaftFacing + " to reach stone");
        transition(Stage.DESCEND_AND_MINE_STONE, 10);
    }

    /**
     * Digs a single-wide descending staircase one step at a time: the block directly ahead (head
     * clearance for the next step) and the block ahead-and-below (the next foot position) are both
     * mined, then the player steps into the newly cleared cell - provided its own support is solid
     * and hazard-free. Every stone block broken along the way is counted toward the cobblestone
     * target; digging keeps going past the first stone contact until enough has been mined, then the
     * whole visited path is walked back up in {@link #ascendShaft}.
     */
    private void descendAndMineStone(Minecraft client) {
        // A block broken by the held attack mapping actually turns to air on the game's own later
        // tick processing, never synchronously inside this method call - so "did the block I started
        // attacking last call actually break" has to be checked here, at the top of the very next
        // call, against a remembered target and its remembered material, rather than against the
        // return value of the same fresh read this call already used to decide whether to attack it.
        if (pendingShaftTarget != null && client.level.getBlockState(pendingShaftTarget).isAir()) {
            if (pendingShaftWasStone) cobblestoneMinedCount++;
            pendingShaftTarget = null;
        }
        if (cobblestoneMinedCount >= COBBLESTONE_TARGET) {
            stopAttack(client);
            announce(client, "Mined " + cobblestoneMinedCount + " cobblestone - collecting drops before climbing out");
            transition(Stage.COLLECT_STONE_DROPS, 10);
            return;
        }
        if (shaftPath.size() - 1 >= MAX_SHAFT_STEPS) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=resource:exhausted; "
                    + "stone was not reached within " + MAX_SHAFT_STEPS + " staircase steps" + telemetrySuffix());
        }
        var current = shaftPath.get(shaftPath.size() - 1);
        var ahead = current.relative(shaftFacing);
        var below = ahead.below();

        var aheadState = client.level.getBlockState(ahead);
        if (!aheadState.isAir()) {
            mineShaftTarget(client, ahead, aheadState, "shaft-ahead");
            return;
        }
        var belowState = client.level.getBlockState(below);
        if (!belowState.isAir()) {
            mineShaftTarget(client, below, belowState, "shaft-below");
            return;
        }
        var supportState = client.level.getBlockState(below.below());
        if (supportState.isAir() || isHazard(supportState) || isHazard(belowState)) {
            rotateShaftDirection(client, "unsafe landing beyond " + below);
            return;
        }
        shaftPath.add(below.immutable());
        shaftDirectionRetries = 0;
        stageTicks = 0;
        if (shaftPath.size() % 4 == 0) {
            announce(client, "Descending staircase - depth " + (shaftPath.size() - 1)
                    + ", cobblestone " + cobblestoneMinedCount + "/" + COBBLESTONE_TARGET);
        }
    }

    private void mineShaftTarget(Minecraft client, BlockPos target, BlockState state, String label) {
        if (!mineableShaftMaterial(client, target, state)) {
            rotateShaftDirection(client, "unmineable material at " + target + ": "
                    + state.getBlock().getName().getString());
            return;
        }
        if (!target.equals(pendingShaftTarget)) {
            pendingShaftTarget = target.immutable();
            pendingShaftWasStone = state.is(Blocks.STONE);
        }
        mineVerified(client, target, label, MAX_MINING_STALL_TICKS);
    }

    private void rotateShaftDirection(Minecraft client, String reason) {
        if (++shaftDirectionRetries > MAX_SHAFT_DIRECTION_RETRIES) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                    + "staircase descent exhausted every direction at depth " + (shaftPath.size() - 1)
                    + ": " + reason + telemetrySuffix());
        }
        shaftFacing = shaftFacing.getClockWise();
        navigationDiagnostics.add("staircase redirected to " + shaftFacing + " (" + shaftDirectionRetries
                + "/" + MAX_SHAFT_DIRECTION_RETRIES + "): " + reason);
        activeMiningTarget = null;
        stageTicks = 0;
    }

    private static boolean mineableShaftMaterial(Minecraft client, BlockPos position, BlockState state) {
        if (isHazard(state)) return false;
        return state.getDestroySpeed(client.level, position) >= 0;
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.getFluidState().is(FluidTags.LAVA);
    }

    private void collectStoneDrops(Minecraft client) {
        var player = requirePlayer(client);
        var cobble = countItem(player, Items.COBBLESTONE);
        if (cobble >= cobblestoneMinedCount) {
            stopMovement(client);
            announce(client, "Collected " + cobble + " cobblestone - climbing back to the surface");
            transition(Stage.ASCEND_SHAFT, 10);
            return;
        }
        var drops = client.level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6.0),
                        entity -> entity.isAlive() && entity.getItem().is(Items.COBBLESTONE))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        if (!drops.isEmpty()) {
            walkToward(client, drops.getFirst().position(), drops.getFirst().getY() > player.getY() + 0.5);
            inputActions.add("move:walk-to-observed-cobblestone-drop");
        } else {
            setMovement(client, false, false, false);
        }
        if (stageTicks > 300) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:collection; "
                + "mined cobblestone drops were not collected; inventory=" + cobble
                + ", mined=" + cobblestoneMinedCount + telemetrySuffix());
    }

    /** Walks back up the exact staircase just dug - every cell along it is already clear. */
    private void ascendShaft(Minecraft client) {
        var player = requirePlayer(client);
        if (shaftIndex >= shaftPath.size() - 1) {
            stopMovement(client);
            announce(client, "Back at the surface entrance of the mined staircase");
            transition(Stage.NAVIGATE_TO_TABLE, 10);
            return;
        }
        var target = shaftPath.get(shaftPath.size() - 1 - shaftIndex - 1);
        var dx = target.getX() + 0.5 - player.getX();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 0.35 && Math.abs(player.blockPosition().getY() - target.getY()) <= 1) {
            shaftIndex++;
            stageTicks = 0;
            return;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        setMovement(client, true, false, true);
        inputActions.add("move:climb-mined-staircase");
        if (stageTicks > 40 * (shaftPath.size() + 4)) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:navigation; "
                + "climbing the mined staircase back to the surface stalled at index " + shaftIndex
                + "/" + shaftPath.size() + telemetrySuffix());
    }

    private void navigateToTable(Minecraft client) {
        if (navigate(client, tablePosition, "crafting table")) {
            stopMovement(client);
            afterTableOpen = Stage.CRAFT_STONE_PICKAXE;
            announce(client, "Back at the crafting table with cobblestone - crafting the stone toolset");
            transition(Stage.OPEN_TABLE, 20);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Crafting: stone tools, furnace
    // ---------------------------------------------------------------------------------------

    private void craftStonePickaxe(Minecraft client) {
        craftGeneric(client, List.of(
                        cobblestoneIngredient(new int[]{1, 2, 3}),
                        stickIngredient(new int[]{5, 8})),
                Items.STONE_PICKAXE, 1, () -> {
                    stonePickaxeCrafted = true;
                    announce(client, "STONE PICKAXE crafted");
                    transition(Stage.MOVE_STONE_PICKAXE_TO_HOTBAR, 20);
                });
    }

    private void craftStoneAxe(Minecraft client) {
        craftGeneric(client, List.of(
                        cobblestoneIngredient(new int[]{1, 2, 4}),
                        stickIngredient(new int[]{5, 8})),
                Items.STONE_AXE, 1, () -> {
                    stoneAxeCrafted = true;
                    announce(client, "STONE AXE crafted");
                    transition(Stage.MOVE_STONE_AXE_TO_HOTBAR, 20);
                });
    }

    /** Sword shape: two cobble stacked in a column (slots 1,4) plus a stick beneath (slot 7). */
    private void craftStoneSword(Minecraft client) {
        craftGeneric(client, List.of(
                        cobblestoneIngredient(new int[]{1, 4}),
                        stickIngredient(new int[]{7})),
                Items.STONE_SWORD, 1, () -> {
                    stoneSwordCrafted = true;
                    announce(client, "STONE SWORD crafted");
                    transition(Stage.MOVE_STONE_SWORD_TO_HOTBAR, 20);
                });
    }

    /** Shovel shape: one cobble (slot 1) plus two sticks stacked beneath it (slots 4,7). */
    private void craftStoneShovel(Minecraft client) {
        craftGeneric(client, List.of(
                        cobblestoneIngredient(new int[]{1}),
                        stickIngredient(new int[]{4, 7})),
                Items.STONE_SHOVEL, 1, () -> {
                    stoneShovelCrafted = true;
                    announce(client, "STONE SHOVEL crafted - full stone toolset complete");
                    transition(Stage.MOVE_STONE_SHOVEL_TO_HOTBAR, 20);
                });
    }

    /** Furnace shape: a cobblestone ring around the empty center slot 5 - eight cobblestone. */
    private void craftFurnaceItem(Minecraft client) {
        craftGeneric(client, List.of(cobblestoneIngredient(new int[]{1, 2, 3, 4, 6, 7, 8, 9})),
                Items.FURNACE, 1, () -> {
                    furnaceCrafted = true;
                    afterTableClose = Stage.EQUIP_STONE_PICKAXE;
                    announce(client, "FURNACE crafted - moving it to hotbar");
                    transition(Stage.MOVE_FURNACE_TO_HOTBAR, 20);
                });
    }

    private void equipStonePickaxe(Minecraft client) {
        selectHotbar(client, stonePickaxeHotbarSlot);
        var player = requirePlayer(client);
        if (!player.getMainHandItem().is(Items.STONE_PICKAXE)) {
            if (stageTicks > 80) throw new IllegalStateException("normal hotbar input did not equip stone pickaxe");
            return;
        }
        announce(client, "STONE PICKAXE EQUIPPED - placing the furnace");
        transition(Stage.PLACE_FURNACE, 15);
        pauseAtCheckpoint(client, "stone-tools");
    }

    /**
     * Furnace placement candidate search: fixes the block-placement bug pattern already found and
     * being fixed elsewhere this session (see the class documentation) by restricting candidates to
     * full-cube-collision support blocks via {@link Block#isShapeFullBlock} before ever computing a
     * nominal top-face aim point, rather than aiming at {@code support.getY() + 1.0} against a
     * support whose real collision shape might be a slab, a snow layer, or anything else partial.
     */
    private void placeFurnace(Minecraft client) {
        var player = requirePlayer(client);
        if (furnacePosition == null) {
            var preferred = tablePosition.relative(shaftFacing.getOpposite());
            furnacePosition = isClearPlacement(client, preferred) ? preferred : findClearPlacement(client, tablePosition, 4);
            navigationDiagnostics.add("furnace placement candidate: " + furnacePosition
                    + " (full-cube-support verified)");
        } else if (client.level.getBlockState(furnacePosition).is(Blocks.FURNACE)) {
            furnacePlaced = true;
            announce(client, "Furnace placed with normal use input");
            transition(Stage.VERIFY, 20);
            return;
        }
        if (furnaceHotbarSlot < 0) throw new IllegalStateException("furnace hotbar slot is unknown");
        selectHotbar(client, furnaceHotbarSlot);
        if (!player.getMainHandItem().is(Items.FURNACE)) {
            if (stageTicks > 240) throw new IllegalStateException("normal hotbar selection did not hold the furnace");
            return;
        }
        placeBlockAgainstSupport(client, furnacePosition, "furnace placement", 1_200);
    }

    // ---------------------------------------------------------------------------------------
    // Terminal verification
    // ---------------------------------------------------------------------------------------

    private void verify(Minecraft client) {
        stopAttack(client);
        var player = requirePlayer(client);
        if (!player.isAlive() || player.getHealth() <= 0.0F) {
            throw new IllegalStateException("PLAYER_DIED: cause=died:unknown; "
                    + "player died before stone-toolset terminal readback" + telemetrySuffix());
        }
        if (!stonePickaxeCrafted || !stoneAxeCrafted || !stoneSwordCrafted || !stoneShovelCrafted) {
            throw new IllegalStateException("full stone toolset terminal readback failed: pickaxe=" + stonePickaxeCrafted
                    + ", axe=" + stoneAxeCrafted + ", sword=" + stoneSwordCrafted + ", shovel=" + stoneShovelCrafted);
        }
        if (!furnacePlaced) {
            throw new IllegalStateException("furnace terminal readback failed: furnace was not placed in the world");
        }
        var finalPosition = player.blockPosition();
        canSeeSkyAtEnd = client.level.canSeeSky(finalPosition);
        endedOnSurface = finalPosition.getY() >= initialSurfaceY - 1 && canSeeSkyAtEnd;
        if (!endedOnSurface) {
            throw new IllegalStateException("terminal surface readback failed: finalY=" + finalPosition.getY()
                    + ", initialSurfaceY=" + initialSurfaceY + ", canSeeSky=" + canSeeSkyAtEnd
                    + "; the run ended underground instead of back on the surface");
        }
        announce(client, "STONE AGE COMPLETE - full stone toolset, furnace placed, back on the surface");
        transition(Stage.COMPLETE_DELAY, 80);
    }

    private void completeAfterVisibleDelay(Minecraft client) {
        releaseInput(client);
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
        var fullStoneToolsetCrafted = stonePickaxeCrafted && stoneAxeCrafted && stoneSwordCrafted && stoneShovelCrafted;
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("checkpoint", checkpoint);
        result.put("checkpointComplete", checkpointComplete);
        result.put("continuationToken", continuationToken);
        result.put("worldObservation", NeoForgeGoalObservation.capture(client, policy));
        result.put("survival", survival);
        result.put("freshWorld", freshWorld);
        result.put("worldName", worldName);
        result.put("worldGameTimeAtStart", worldGameTimeAtStart);
        result.put("handMinedLogs", handMinedLogs);
        result.put("planksCrafted", planksCrafted);
        result.put("sticksCrafted", sticksCrafted);
        result.put("craftingTableCrafted", craftingTableCrafted);
        result.put("woodenPickaxeCrafted", woodenPickaxeCrafted);
        result.put("woodenPickaxeEquipped", woodenPickaxeEquipped);
        result.put("woodenAxeCrafted", woodenAxeCrafted);
        result.put("cobblestoneMinedCount", cobblestoneMinedCount);
        result.put("stonePickaxeCrafted", stonePickaxeCrafted);
        result.put("stoneAxeCrafted", stoneAxeCrafted);
        result.put("stoneSwordCrafted", stoneSwordCrafted);
        result.put("stoneShovelCrafted", stoneShovelCrafted);
        result.put("fullStoneToolsetCrafted", fullStoneToolsetCrafted);
        result.put("stonePickaxeEquipped", player.getMainHandItem().is(Items.STONE_PICKAXE));
        result.put("furnaceCrafted", furnaceCrafted);
        result.put("furnacePlaced", furnacePlaced);
        result.put("furnacePosition", furnacePosition == null ? Map.of() : position(furnacePosition));
        result.put("initialSurfaceY", initialSurfaceY);
        result.put("finalPosition", position(player.blockPosition()));
        result.put("endedOnSurface", endedOnSurface);
        result.put("canSeeSky", canSeeSkyAtEnd);
        result.put("playerAlive", player.isAlive() && player.getHealth() > 0.0F);
        result.put("healthAtEnd", player.getHealth());
        result.put("commandsUsed", false);
        result.put("directMutationUsed", false);
        result.put("intelligence", policy.intelligence().id());
        result.put("safety", policy.safety().id());
        result.put("policyMode", policy.mode());
        result.put("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled());
        result.put("obstructionBreaks", obstructionBreaks);
        result.put("safetyInterventions", List.copyOf(safetyDiagnostics));
        result.put("safetyInterventionCount", safetyDiagnostics.size());
        result.put("navigationReplans", totalNavigationReplans);
        result.put("distanceTraveled", rounded(distanceTraveled));
        result.put("blocksMined", handMinedLogs + cobblestoneMinedCount);
        result.put("elapsedTicks", totalTicks);
        result.put("observation", policy.observation());
        result.put("combatPolicy", policy.combatPolicy());
        result.put("allowBlockBreaking", policy.allowBlockBreaking());
        result.put("allowBlockPlacing", policy.allowBlockPlacing());
        result.put("allowCommands", policy.allowCommands());
        result.put("suppressInGameMessages", suppressInGameMessages);
        result.put("inGameMessagesEmitted", inGameMessagesEmitted);
        result.put("navigationDiagnostics", List.copyOf(navigationDiagnostics));
        result.put("inputActions", List.copyOf(inputActions));
        return Map.copyOf(result);
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    static String checkpoint(Map<String, Object> input) {
        var value = input.get("checkpoint");
        var checkpoint = value == null || String.valueOf(value).isBlank()
                ? "complete" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (checkpoint) {
            case "wooden-tools", "stone-tools", "complete" -> checkpoint;
            default -> throw new IllegalArgumentException(
                    "stone toolset checkpoint must be wooden-tools, stone-tools, or complete");
        };
    }

    // ---------------------------------------------------------------------------------------
    // Generic crafting-table recipe execution
    // ---------------------------------------------------------------------------------------

    /**
     * {@code matcher} intentionally accepts any item satisfying it, not one exact {@link Item} -
     * hand-mined logs are not necessarily oak, so their planks are not necessarily
     * {@code Items.OAK_PLANKS} either. Every planks ingredient below matches
     * {@link ItemTags#PLANKS} (any wood type) for exactly this reason; only cobblestone and sticks,
     * which have no per-wood-type variants, match by exact item.
     */
    private record Ingredient(java.util.function.Predicate<ItemStack> matcher, String label, int[] slots,
                              boolean singleCellWholeStack) {
    }

    /** One unit of any-wood-type planks placed into each of {@code slots} (repeats allowed). */
    private static Ingredient planksIngredient(int[] slots) {
        return new Ingredient(stack -> stack.is(ItemTags.PLANKS), "planks", slots, false);
    }

    /** One cobblestone placed into each of {@code slots} (repeats allowed). */
    private static Ingredient cobblestoneIngredient(int[] slots) {
        return new Ingredient(stack -> stack.is(Items.COBBLESTONE), "cobblestone", slots, false);
    }

    /** One stick placed into each of {@code slots} (repeats allowed). */
    private static Ingredient stickIngredient(int[] slots) {
        return new Ingredient(stack -> stack.is(Items.STICK), "stick", slots, false);
    }

    /**
     * Executes a recipe against a placed crafting table: for each ingredient, picks up the player's
     * matching stack, distributes it across the declared grid slots (one unit per right-click,
     * unless {@code singleCellWholeStack} places the entire stack with a single left-click, matching
     * the log-to-planks recipe's whole-stack-in-one-cell shape), returns any unused remainder, then
     * quick-moves the result. Reused by every stone and wooden tool recipe in this actor.
     */
    private void craftGeneric(Minecraft client, List<Ingredient> ingredients, Item result, int minResultCount,
                               Runnable onSuccess) {
        var screen = requireAnyContainer(client);
        var sequence = new ArrayList<ClickOp>();
        for (var ingredient : ingredients) {
            var source = findPlayerItemSlot(screen, ingredient.matcher());
            if (source < 0) throw new IllegalStateException("missing ingredient before recipe: " + ingredient.label());
            var label = ingredient.label();
            if (ingredient.singleCellWholeStack()) {
                sequence.add(new ClickOp(source, 0, ClickType.PICKUP, "pick-up-" + label));
                sequence.add(new ClickOp(ingredient.slots()[0], 0, ClickType.PICKUP, "place-" + label));
            } else {
                sequence.add(new ClickOp(source, 0, ClickType.PICKUP, "pick-up-" + label));
                for (var slot : ingredient.slots()) {
                    sequence.add(new ClickOp(slot, 1, ClickType.PICKUP, "place-" + label + "-" + slot));
                }
                sequence.add(new ClickOp(source, 0, ClickType.PICKUP, "return-unused-" + label));
            }
        }
        sequence.add(new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-" + itemLabel(result)));
        startClicks(sequence, () -> {
            if (countItem(requirePlayer(client), result) < minResultCount) {
                throw new IllegalStateException("visible recipe did not produce " + itemLabel(result));
            }
            onSuccess.run();
        });
    }

    private static String itemLabel(Item item) {
        return item.toString().replace(':', '-');
    }

    private void moveItemToHotbar(Minecraft client, Item item, java.util.function.IntConsumer slotSink, Stage next) {
        var screen = requireAnyContainer(client);
        var player = requirePlayer(client);
        var slot = hotbarSlot(player, item);
        if (slot >= 0) {
            slotSink.accept(slot);
            transition(next, 15);
            return;
        }
        var source = findPlayerItemSlot(screen, stack -> stack.is(item));
        if (source < 0) throw new IllegalStateException("crafted item is missing before hotbar transfer: " + item);
        startClicks(List.of(new ClickOp(source, 0, ClickType.QUICK_MOVE, "move-crafted-item-to-hotbar")), () -> {
            var moved = hotbarSlot(requirePlayer(client), item);
            if (moved < 0) throw new IllegalStateException("normal quick-move did not place crafted item in hotbar: " + item);
            slotSink.accept(moved);
            transition(next, 15);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Mining verification (pick()-verified aim, bounded stall - see class documentation)
    // ---------------------------------------------------------------------------------------

    /**
     * Verifies via {@link LocalPlayer#pick} that the raycast actually lands on {@code target} before
     * holding the attack mapping, on every tick. Never holds attack blind, and fails with a bounded,
     * typed stall error instead of grinding the whole caller-supplied timeout with no progress -
     * fixing the exact pre-fix {@code mineResource()} anti-pattern called out in this task's brief.
     */
    private boolean mineVerified(Minecraft client, BlockPos target, String label, int stallBudget) {
        var player = requirePlayer(client);
        var state = client.level.getBlockState(target);
        if (state.isAir()) {
            stopAttack(client);
            activeMiningTarget = null;
            return true;
        }
        if (!target.equals(activeMiningTarget)) {
            activeMiningTarget = target.immutable();
            miningTargetTicks = 0;
        }
        miningTargetTicks++;
        lookAt(player, target);
        var hit = player.pick(4.5, 0.0F, false);
        var aimed = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target);
        if (aimed) {
            clickAndHoldAttack(client, label);
            inputActions.add("attack:key.attack-held-with-" + label);
        } else {
            stopAttack(client);
            if (miningTargetTicks % 40 == 0) {
                navigationDiagnostics.add("mining aim miss on " + target + " at tick " + miningTargetTicks);
            }
        }
        if (miningTargetTicks > stallBudget) {
            throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:mining; "
                    + label + " failed to break verified-aim target " + target + " after " + stallBudget
                    + " ticks" + telemetrySuffix());
        }
        return false;
    }

    private boolean breakVisibleObstruction(Minecraft client, BlockPos target) {
        var player = requirePlayer(client);
        var hit = client.level.clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getBlockPos().equals(target)
                || player.distanceToSqr(Vec3.atCenterOf(blockHit.getBlockPos())) > 30.0) return false;
        if (!NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, blockHit.getBlockPos(), policy)) return false;
        lookAt(player, blockHit.getBlockPos());
        clickAndHoldAttack(client, "obstruction");
        obstructionBreaks++;
        inputActions.add("recovery:break-obstruction:" + blockHit.getBlockPos());
        safetyDiagnostics.add("obstruction:" + blockHit.getBlockPos());
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Block placement (full-cube-support-only candidate search - see class documentation)
    // ---------------------------------------------------------------------------------------

    /**
     * Restricts placement candidates to a support block whose actual, in-world collision shape is a
     * full cube. A support with a partial collision shape (a slab, a snow layer, and so on) would
     * make {@code support.getY() + 1.0} a nominal point that does not lie on the support's real
     * collision surface, so this must be checked before that point is ever computed or aimed at -
     * this is the exact placement bug pattern already found and being fixed elsewhere this session.
     */
    private boolean isClearPlacement(Minecraft client, BlockPos candidate) {
        var player = requirePlayer(client);
        var state = client.level.getBlockState(candidate);
        var supportPos = candidate.below();
        var support = client.level.getBlockState(supportPos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()
                || !Block.isShapeFullBlock(support.getCollisionShape(client.level, supportPos))
                || player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(candidate))) return false;
        var supportPoint = new Vec3(supportPos.getX() + 0.5, supportPos.getY() + 1.0 - 0.001, supportPos.getZ() + 0.5);
        var hit = client.level.clip(new ClipContext(player.getEyePosition(), supportPoint,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(supportPos);
    }

    private BlockPos findClearPlacement(Minecraft client, BlockPos origin, int maxRadius) {
        var player = requirePlayer(client);
        for (var radius = 1; radius <= maxRadius; radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var dy = 1; dy >= -3; dy--) {
                        var candidate = origin.offset(dx, dy, dz);
                        if (!isClearPlacement(client, candidate)) continue;
                        var supportPos = candidate.below();
                        var distance = player.getEyePosition().distanceTo(
                                new Vec3(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5));
                        if (distance <= 4.25) return candidate.immutable();
                    }
                }
            }
        }
        throw new IllegalStateException("no clear, full-cube-supported, in-reach position was observed for normal "
                + "block placement; origin=" + origin);
    }

    private void placeBlockAgainstSupport(Minecraft client, BlockPos target, String label, int timeoutTicks) {
        var player = requirePlayer(client);
        var support = target.below();
        var supportPoint = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
        var distance = player.getEyePosition().distanceTo(supportPoint);
        if (distance > 4.25) {
            if (navigate(client, target, label)) {
                lookAt(player, support);
                setMovement(client, true, false, player.isInWater() || player.onGround());
            }
            if (stageTicks > timeoutTicks) throw new IllegalStateException("normal-input placement approach remained "
                    + "out of reach; player=" + player.blockPosition() + ", target=" + target
                    + ", eyeDistance=" + rounded(distance));
            return;
        }
        stopMovement(client);
        lookAt(player, support);
        var hit = player.pick(5.0, 0.0F, false);
        var aimedAtSupport = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support);
        if (stageTicks % 20 == 1 && aimedAtSupport) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-" + label.replace(' ', '-'));
        }
        if (stageTicks > timeoutTicks) throw new IllegalStateException("normal use input did not place the block for "
                + label + "; player=" + player.blockPosition() + ", target=" + target
                + ", eyeDistance=" + rounded(distance) + ", aimedAtSupport=" + aimedAtSupport
                + ", held=" + player.getMainHandItem().getItem());
    }

    private BlockPos findInteractionVantage(Minecraft client, BlockPos target) {
        var player = requirePlayer(client);
        var aim = Vec3.atCenterOf(target);
        for (var radius = 1; radius <= 4; radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var dy = -1; dy <= 2; dy++) {
                        var candidate = target.offset(dx, dy, dz);
                        if (candidate.equals(target) || !isStandable(client.level, candidate)) continue;
                        var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62, candidate.getZ() + 0.5);
                        if (eye.distanceTo(aim) > 4.5) continue;
                        var clip = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE, player));
                        if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
                            return candidate.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Shared navigation machinery (duplicated from NeoForgeSurvivalTreeGoal by design)
    // ---------------------------------------------------------------------------------------

    private boolean navigate(Minecraft client, BlockPos target, String label) {
        var player = requirePlayer(client);
        var dx = target.getX() + 0.5 - player.getX();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 2.7 && Math.abs(target.getY() - player.getY()) <= 5.0) return true;

        if (!target.equals(navigationDestination)) {
            navigationDestination = target.immutable();
            navigationPath = findNavigationPath(client, target);
            navigationIndex = navigationPath.size() > 1 ? 1 : 0;
            navigationStuckTicks = 0;
            navigationReplans = 0;
            navigationDetourTicks = 0;
            if (navigationPath.isEmpty() && policy.smartNavigation()) {
                throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                        + "safe intelligent route unavailable to " + label + telemetrySuffix());
            }
            navigationDirectFallback = navigationPath.isEmpty();
            navigationLastDistance = Double.POSITIVE_INFINITY;
            var diagnostic = "planned " + label + " route: player=" + player.blockPosition()
                    + ", target=" + target + ", distance=" + rounded(horizontal)
                    + ", waypoints=" + navigationPath.size() + ", fallback=" + navigationDirectFallback;
            navigationDiagnostics.add(diagnostic);
            announce(client, diagnostic);
            inputActions.add(navigationDirectFallback
                    ? "move:direct-visible-obstacle-fallback-to-" + label.replace(' ', '-')
                    : "observe:collision-aware-route-to-" + label.replace(' ', '-'));
        }

        if (navigationDirectFallback) return navigateDirectFallback(client, target, label, horizontal);

        while (navigationIndex < navigationPath.size()) {
            var candidate = navigationPath.get(navigationIndex);
            var waypointDx = candidate.getX() + 0.5 - player.getX();
            var waypointDz = candidate.getZ() + 0.5 - player.getZ();
            if (Math.sqrt(waypointDx * waypointDx + waypointDz * waypointDz) > 0.65) break;
            navigationIndex++;
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
        }

        if (navigationIndex >= navigationPath.size()) {
            resetNavigation();
            return horizontal <= 3.4 && Math.abs(target.getY() - player.getY()) <= 5.0;
        }

        var waypoint = navigationPath.get(navigationIndex);
        var waypointDx = waypoint.getX() + 0.5 - player.getX();
        var waypointDz = waypoint.getZ() + 0.5 - player.getZ();
        var waypointDistance = Math.sqrt(waypointDx * waypointDx + waypointDz * waypointDz);
        if (waypointDistance < navigationLastDistance - 0.015) {
            navigationStuckTicks = 0;
        } else {
            navigationStuckTicks++;
        }
        navigationLastDistance = waypointDistance;

        if (navigationStuckTicks > 80) {
            stopMovement(client);
            if (policy.obstructionMiningEnabled() && breakVisibleObstruction(client, target)) {
                navigationStuckTicks = 0;
                navigationLastDistance = Double.POSITIVE_INFINITY;
                return false;
            }
            totalNavigationReplans++;
            if (++navigationReplans > 5) {
                throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:navigation; "
                        + "normal-input route remained obstructed before reaching " + label
                        + "; player=" + player.blockPosition() + ", target=" + target
                        + ", distance=" + rounded(horizontal) + ", waypoint=" + waypoint
                        + ", replans=" + navigationReplans + telemetrySuffix());
            }
            navigationPath = findNavigationPath(client, target);
            navigationIndex = navigationPath.size() > 1 ? 1 : 0;
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            if (navigationPath.isEmpty()) {
                if (policy.smartNavigation()) {
                    throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                            + "safe intelligent route remained unavailable to " + label + telemetrySuffix());
                }
                navigationDirectFallback = true;
                navigationDetourTicks = 50;
                navigationDetourSign = navigationReplans % 2 == 0 ? 1 : -1;
                navigationDiagnostics.add("BFS route unavailable after obstruction; direct normal-input detour: player="
                        + player.blockPosition() + ", target=" + target + ", distance=" + rounded(horizontal));
                announce(client, "Route blocked - using visible normal-input detour to " + label);
                inputActions.add("move:visible-obstacle-detour-fallback");
                return false;
            }
            navigationDiagnostics.add("replanned " + label + " route: player=" + player.blockPosition()
                    + ", target=" + target + ", distance=" + rounded(horizontal)
                    + ", waypoints=" + navigationPath.size() + ", attempt=" + navigationReplans);
            announce(client, "Obstacle encountered - replanning visible walking route to " + label);
            inputActions.add("move:visible-obstacle-replan");
            waitTicks = 8;
            return false;
        }

        var yaw = (float) (Math.toDegrees(Math.atan2(waypointDz, waypointDx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        inputActions.add("look:rotation-toward-" + label.replace(' ', '-'));
        var stepUp = waypoint.getY() > player.getY() + 0.35;
        setMovement(client, true, horizontal > 7.0, player.isInWater() || (player.onGround()
                && (stepUp || navigationStuckTicks > 25 || stageTicks % 45 < 3)));
        if (stageTicks > 3_600) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:navigation; "
                + "visible navigation timed out before reaching " + label
                + "; player=" + player.blockPosition() + ", target=" + target
                + ", distance=" + rounded(horizontal) + ", waypoint=" + waypoint
                + ", pathIndex=" + navigationIndex + "/" + navigationPath.size() + telemetrySuffix());
        return false;
    }

    private boolean navigateDirectFallback(Minecraft client, BlockPos target, String label, double horizontal) {
        var player = requirePlayer(client);
        if (horizontal < navigationLastDistance - 0.02) {
            navigationStuckTicks = 0;
        } else {
            navigationStuckTicks++;
        }
        navigationLastDistance = horizontal;

        if (navigationStuckTicks > 90) {
            stopMovement(client);
            navigationReplans++;
            totalNavigationReplans++;
            var recoveredPath = findNavigationPath(client, target);
            if (!recoveredPath.isEmpty()) {
                navigationPath = recoveredPath;
                navigationIndex = recoveredPath.size() > 1 ? 1 : 0;
                navigationDirectFallback = false;
                navigationStuckTicks = 0;
                navigationLastDistance = Double.POSITIVE_INFINITY;
                navigationDiagnostics.add("direct fallback recovered BFS route: player=" + player.blockPosition()
                        + ", target=" + target + ", waypoints=" + recoveredPath.size());
                announce(client, "Detour found a clear walking route to " + label);
                return false;
            }
            if (navigationReplans > 10) {
                throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:navigation; "
                        + "normal-input detours remained obstructed before reaching " + label
                        + "; player=" + player.blockPosition() + ", target=" + target
                        + ", distance=" + rounded(horizontal) + ", detours=" + navigationReplans
                        + telemetrySuffix());
            }
            navigationDetourTicks = 55;
            navigationDetourSign = navigationReplans % 2 == 0 ? 1 : -1;
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            var diagnostic = "visible detour " + navigationReplans + " for " + label
                    + ": player=" + player.blockPosition() + ", target=" + target
                    + ", distance=" + rounded(horizontal) + ", turn=" + (navigationDetourSign * 65);
            navigationDiagnostics.add(diagnostic);
            announce(client, diagnostic);
            inputActions.add("move:visible-alternating-obstacle-detour");
        }

        var dx = target.getX() + 0.5 - player.getX();
        var dz = target.getZ() + 0.5 - player.getZ();
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        if (navigationDetourTicks > 0) {
            yaw += navigationDetourSign * 65.0F;
            navigationDetourTicks--;
        }
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        inputActions.add("look:direct-visible-route-to-" + label.replace(' ', '-'));
        setMovement(client, true, horizontal > 7.0, player.isInWater()
                || (player.onGround() && (navigationDetourTicks > 0 || stageTicks % 35 < 5)));
        if (stageTicks > 3_600) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:navigation; "
                + "visible fallback navigation timed out before reaching "
                + label + "; player=" + player.blockPosition() + ", target=" + target
                + ", distance=" + rounded(horizontal) + ", detours=" + navigationReplans
                + telemetrySuffix());
        return false;
    }

    private void walkToward(Minecraft client, Vec3 target, boolean jump) {
        var player = requirePlayer(client);
        var dx = target.x - player.getX();
        var dz = target.z - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        setMovement(client, horizontal > 0.25, false, player.isInWater() || (player.onGround() && jump));
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<BlockPos> findNavigationPath(Minecraft client, BlockPos target) {
        if (policy.smartNavigation()) {
            var player = requirePlayer(client);
            return NeoForgeSafePathPlanner.find(client.level, player, player.blockPosition(), target, policy);
        }
        var level = client.level;
        var player = requirePlayer(client);
        var start = player.blockPosition();
        if (!isStandable(level, start)) {
            BlockPos adjusted = null;
            for (var dy = 2; dy >= -3 && adjusted == null; dy--) {
                var candidate = start.offset(0, dy, 0);
                if (isStandable(level, candidate)) adjusted = candidate;
            }
            if (adjusted == null) return List.of();
            start = adjusted;
        }

        var queue = new ArrayDeque<BlockPos>();
        var previous = new HashMap<Long, Long>();
        queue.add(start.immutable());
        previous.put(start.asLong(), Long.MIN_VALUE);
        BlockPos reached = null;
        var visited = 0;

        while (!queue.isEmpty() && visited++ < 45_000) {
            var current = queue.removeFirst();
            var targetDx = current.getX() + 0.5 - (target.getX() + 0.5);
            var targetDz = current.getZ() + 0.5 - (target.getZ() + 0.5);
            if (targetDx * targetDx + targetDz * targetDz <= 9.0
                    && Math.abs(current.getY() - target.getY()) <= 5) {
                reached = current;
                break;
            }
            for (var direction : Direction.Plane.HORIZONTAL) {
                var horizontal = current.relative(direction);
                for (var dy : new int[]{1, 0, -1, -2, -3}) {
                    var candidate = new BlockPos(horizontal.getX(), current.getY() + dy, horizontal.getZ());
                    if (Math.abs(candidate.getX() - start.getX()) > 72
                            || Math.abs(candidate.getZ() - start.getZ()) > 72
                            || Math.abs(candidate.getY() - start.getY()) > 24
                            || !isStandable(level, candidate)) continue;
                    var key = candidate.asLong();
                    if (previous.putIfAbsent(key, current.asLong()) == null) queue.addLast(candidate.immutable());
                    break;
                }
            }
        }
        if (reached == null) return List.of();

        var path = new ArrayList<BlockPos>();
        var cursor = reached.asLong();
        while (cursor != Long.MIN_VALUE) {
            path.add(BlockPos.of(cursor).immutable());
            cursor = previous.getOrDefault(cursor, Long.MIN_VALUE);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private static boolean isStandable(net.minecraft.client.multiplayer.ClientLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet)) return false;
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(feet.above());
        var supportPos = feet.below();
        var support = level.getBlockState(supportPos);
        var swimming = !feetState.getFluidState().isEmpty() || !support.getFluidState().isEmpty();
        return feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, feet.above()).isEmpty()
                && (swimming || !support.getCollisionShape(level, supportPos).isEmpty());
    }

    private void resetNavigation() {
        navigationDestination = null;
        navigationPath = List.of();
        navigationIndex = 0;
        navigationStuckTicks = 0;
        navigationReplans = 0;
        navigationDetourTicks = 0;
        navigationDetourSign = 1;
        navigationDirectFallback = false;
        navigationLastDistance = Double.POSITIVE_INFINITY;
    }

    private List<TreePlan> scanTrees(Minecraft client) {
        var level = client.level;
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var logs = new HashSet<Long>();
        var minY = Math.max(level.getMinBuildHeight(), center.getY() - 12);
        var maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + 32);
        for (var x = center.getX() - 64; x <= center.getX() + 64; x++) {
            for (var z = center.getZ() - 64; z <= center.getZ() + 64; z++) {
                var probe = new BlockPos(x, center.getY(), z);
                if (!level.hasChunkAt(probe)) continue;
                for (var y = minY; y <= maxY; y++) {
                    var position = new BlockPos(x, y, z);
                    if (level.getBlockState(position).is(BlockTags.LOGS)) logs.add(position.asLong());
                }
            }
        }

        var unseen = new HashSet<>(logs);
        var plans = new ArrayList<TreePlan>();
        while (!unseen.isEmpty()) {
            var seed = BlockPos.of(unseen.iterator().next());
            var queue = new ArrayDeque<BlockPos>();
            var component = new ArrayList<BlockPos>();
            queue.add(seed);
            unseen.remove(seed.asLong());
            while (!queue.isEmpty() && component.size() <= 24) {
                var next = queue.removeFirst();
                component.add(next.immutable());
                for (var direction : Direction.values()) {
                    var neighbor = next.relative(direction);
                    if (unseen.remove(neighbor.asLong())) queue.addLast(neighbor);
                }
            }
            if (component.size() < 3 || component.size() > 12) continue;
            component.sort(Comparator.comparingInt(BlockPos::getY));
            var minX = component.stream().mapToInt(BlockPos::getX).min().orElse(0);
            var maxX = component.stream().mapToInt(BlockPos::getX).max().orElse(0);
            var minZ = component.stream().mapToInt(BlockPos::getZ).min().orElse(0);
            var maxZ = component.stream().mapToInt(BlockPos::getZ).max().orElse(0);
            var minComponentY = component.getFirst().getY();
            var maxComponentY = component.getLast().getY();
            if (maxX - minX > 1 || maxZ - minZ > 1 || maxComponentY - minComponentY > 10) continue;
            var base = component.getFirst();
            if (level.getBlockState(base.below()).is(BlockTags.LOGS)) continue;
            var distance = player.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            plans.add(new TreePlan(base, List.copyOf(component), distance));
        }
        var playerY = player.blockPosition().getY();
        plans.sort(Comparator.<TreePlan>comparingInt(plan -> Math.abs(plan.base().getY() - playerY))
                .thenComparingDouble(TreePlan::distanceSquared));
        return plans;
    }

    // ---------------------------------------------------------------------------------------
    // Container click machinery (duplicated from NeoForgeSurvivalTreeGoal by design)
    // ---------------------------------------------------------------------------------------

    private void tickClicks(Minecraft client) {
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        if (!clicks.isEmpty()) {
            var click = clicks.removeFirst();
            var screen = requireAnyContainer(client);
            if (client.gameMode == null) throw new IllegalStateException("client game mode unavailable during container click");
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
        if (!clicks.isEmpty() || clicksComplete != null) throw new IllegalStateException("container click sequence already active");
        clicks.addAll(sequence);
        clicksComplete = complete;
        clickDelay = 2;
    }

    private static int findPlayerItemSlot(AbstractContainerScreen<?> screen,
                                          java.util.function.Predicate<ItemStack> predicate) {
        var slots = screen.getMenu().slots;
        var start = screen instanceof InventoryScreen ? 9 : 10;
        for (var index = start; index < slots.size(); index++) {
            var stack = slots.get(index).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) return index;
        }
        return -1;
    }

    private static int hotbarSlot(LocalPlayer player, Item item) {
        for (var slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(item)) return slot;
        return -1;
    }

    private void selectHotbar(Minecraft client, int slot) {
        if (slot < 0 || slot > 8) throw new IllegalStateException("invalid hotbar slot for normal selection: " + slot);
        if (client.player.getInventory().selected == slot) return;
        KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
        inputActions.add("select:hotbar-key-" + (slot + 1));
    }

    private static int countNonEmpty(LocalPlayer player) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) count++;
        }
        return count;
    }

    private static int countLogs(LocalPlayer player) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (isLogStack(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int countPlanks(LocalPlayer player) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) count += stack.getCount();
        }
        return count;
    }

    private static int countItem(LocalPlayer player, Item item) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isLogStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().is(BlockTags.LOGS);
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        var eye = player.getEyePosition();
        var dx = target.getX() + 0.5 - eye.x;
        var dy = target.getY() + 0.5 - eye.y;
        var dz = target.getZ() + 0.5 - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint);
        client.options.keyJump.setDown(jump);
        inputActions.add("move:key.forward-held");
        if (sprint) inputActions.add("move:key.sprint-held");
        if (jump) inputActions.add("move:key.jump-held");
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    private void clickAndHoldAttack(Minecraft client, String label) {
        if (miningTargetTicks % 10 == 1) {
            KeyMapping.click(client.options.keyAttack.getKey());
            inputActions.add("attack:key.attack-click-start:" + label);
        }
        client.options.keyAttack.setDown(true);
    }

    private static void releaseInput(Minecraft client) {
        stopMovement(client);
        stopAttack(client);
        client.options.keyUse.setDown(false);
    }

    private void announce(Minecraft client, String message) {
        if (suppressInGameMessages) return;
        var text = Component.literal("[Lodestone Goal] " + message);
        if (client.player != null) client.player.displayClientMessage(text, true);
        client.gui.getChat().addMessage(text);
        inGameMessagesEmitted += 2;
    }

    private static LocalPlayer requirePlayer(Minecraft client) {
        if (client.player == null || client.level == null) throw new IllegalStateException("client player/world unavailable");
        return client.player;
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractContainerScreen<?>> T requireContainer(Minecraft client, Class<T> type) {
        if (!type.isInstance(client.screen)) {
            throw new IllegalStateException("required visible container is not open: " + type.getSimpleName());
        }
        return (T) client.screen;
    }

    private static AbstractContainerScreen<?> requireAnyContainer(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            throw new IllegalStateException("required visible container screen is not open");
        }
        return screen;
    }

    private void transition(Stage next, int delay) {
        stage = next;
        stageTicks = 0;
        waitTicks = delay;
    }

    private enum Stage {
        WAIT_WORLD,
        SEARCH_TREE,
        NAVIGATE_TREE,
        MINE_LOGS,
        COLLECT_LOGS,
        OPEN_INVENTORY,
        CRAFT_PLANKS,
        CRAFT_TABLE,
        CRAFT_STICKS,
        MOVE_TABLE_TO_HOTBAR,
        CLOSE_INVENTORY,
        PLACE_TABLE,
        OPEN_TABLE,
        CRAFT_WOODEN_PICKAXE,
        MOVE_WOODEN_PICKAXE_TO_HOTBAR,
        CRAFT_WOODEN_AXE,
        MOVE_WOODEN_AXE_TO_HOTBAR,
        CLOSE_TABLE,
        EQUIP_WOODEN_PICKAXE,
        BEGIN_DESCENT,
        DESCEND_AND_MINE_STONE,
        COLLECT_STONE_DROPS,
        ASCEND_SHAFT,
        NAVIGATE_TO_TABLE,
        CRAFT_STONE_PICKAXE,
        MOVE_STONE_PICKAXE_TO_HOTBAR,
        CRAFT_STONE_AXE,
        MOVE_STONE_AXE_TO_HOTBAR,
        CRAFT_STONE_SWORD,
        MOVE_STONE_SWORD_TO_HOTBAR,
        CRAFT_STONE_SHOVEL,
        MOVE_STONE_SHOVEL_TO_HOTBAR,
        CRAFT_FURNACE_ITEM,
        MOVE_FURNACE_TO_HOTBAR,
        EQUIP_STONE_PICKAXE,
        PLACE_FURNACE,
        VERIFY,
        COMPLETE_DELAY
    }

    private record ClickOp(int slot, int button, ClickType type, String label) {
    }

    private record TreePlan(BlockPos base, List<BlockPos> logs, double distanceSquared) {
    }
}
