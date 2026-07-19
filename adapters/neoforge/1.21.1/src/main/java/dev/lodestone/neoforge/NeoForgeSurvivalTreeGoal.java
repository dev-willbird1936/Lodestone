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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

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
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/**
 * Bounded client-input actor for the registered survival tree goal.
 *
 * <p>This class deliberately has no server/world mutation API. Blocks are broken by holding the
 * ordinary attack mapping, items are collected from drops, recipes are assembled in visible
 * vanilla menus through the same container-click path exposed by the adapter, and the crafting
 * table is placed/opened with the normal use mapping.</p>
 */
final class NeoForgeSurvivalTreeGoal implements NeoForgeResumableGoal {
    private static final int HAND_LOGS_REQUIRED = 3;
    // Live-caught under realtime (seed 7777777777777, adaptive-v1/balanced, two independent runs):
    // totalTicks only advances while tick(client) actually runs (paused/resumed segments do not
    // accumulate it - see resume(), which resets stageTicks/waitTicks but deliberately not
    // totalTicks), so it tracks real ClientTickEvent.Post ticks at the game's fixed ~50ms/tick
    // rate. One run's failed "gather-resource" invocation measured 450772ms over 9001 ticks -
    // almost exactly 50ms/tick, confirming the old 9_000 budget (~450s) was the actual limiter,
    // not terrain difficulty alone: it fired well inside the checkpointed workflow's own
    // per-invocation outer ceiling (the confirmed 600000ms MCP maxDurationMs cap the harness now
    // deliberately uses for realtime, see commit 3e20402), on just the *first* of this goal's
    // three checkpoint segments (gather-resource, craft-axe, mine-target - see
    // BuiltinGoalPlanner's wooden-axe-tree workflow). totalTicks is never reset across those
    // three resumed segments, so this budget is a *cumulative* ceiling across all of them, not a
    // per-segment one. Sized to the same segment-count reasoning as the rate-limit fix in
    // 2beefea: three segments, each potentially needing up to the full 600000ms/~12000-tick outer
    // ceiling, at the confirmed ~50ms/tick rate.
    private static final int MAX_TOTAL_TICKS = 36_000;
    private static final int MAX_SEARCH_ATTEMPTS = 16;
    private static final int ELECTION_PROBE_BUDGET = 9_000;
    private static final int MAX_TREE_RE_ELECTIONS = 6;
    private static final int MAX_MINING_VANTAGE_ATTEMPTS = 4;
    private static final int MAX_TABLE_PLACEMENT_VANTAGE_ATTEMPTS = 4;

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
    private int searchAttempts;
    private int mineIndex;
    private int handMinedLogs;
    private int targetMinedLogs;
    private int inGameMessagesEmitted;
    private int planksCrafted;
    private int sticksCrafted;
    private int tableHotbarSlot = -1;
    private int axeHotbarSlot = -1;
    private long worldGameTimeAtStart;
    private String worldName = "fresh-survival-world";
    private boolean survival;
    private boolean freshWorld;
    private boolean craftingTableCrafted;
    private boolean woodenAxeCrafted;
    private boolean woodenAxeEquipped;
    private boolean allTargetLogsMinedWithWoodenAxe = true;
    private TreePlan resourceTree;
    private TreePlan targetTree;
    private BlockPos tablePosition;
    private BlockPos tableInteractionVantage;
    private BlockPos tablePlacementVantage;
    private int placeTableAimTicks;
    private int placeTableVantageAttempts;
    private BlockPos miningTargetVantage;
    private BlockPos miningVantageForTarget;
    private int miningVantageAttempts;
    private final Set<Long> rejectedMiningVantages = new HashSet<>();
    private BlockPos navigationDestination;
    private List<BlockPos> navigationPath = List.of();
    private int navigationIndex;
    private int navigationStuckTicks;
    private int navigationReplans;
    private int navigationDetourTicks;
    private int navigationDetourSign = 1;
    private boolean navigationDirectFallback;
    private double navigationLastDistance = Double.POSITIVE_INFINITY;
    private int obstructionBreaks;
    private final Set<Long> blacklistedTrees = new HashSet<>();
    private List<TreePlan> candidateTrees = List.of();
    private final List<TreePlan> electedTrees = new ArrayList<>();
    private int candidateProbeIndex;
    private int treeReElections;
    private int totalNavigationReplans;
    private double distanceTraveled;
    private double lastTickX = Double.NaN;
    private double lastTickZ = Double.NaN;

    NeoForgeSurvivalTreeGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
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
        if (!paused || finished) throw new IllegalStateException("survival tree goal is not resumable");
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
                        + "; survival tree goal exceeded its bounded input budget"
                        + telemetrySuffix());
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
                case SEARCH_TREES -> searchTrees(client);
                case ELECT_TREES -> electTrees(client);
                case EXPLORE -> explore(client);
                case NAVIGATE_RESOURCE -> navigateResource(client);
                case MINE_RESOURCE -> mineResource(client);
                case COLLECT_RESOURCE -> collectResource(client);
                case OPEN_INVENTORY -> openInventory(client);
                case CRAFT_PLANKS -> craftPlanks(client);
                case CRAFT_TABLE -> craftTable(client);
                case CRAFT_STICKS -> craftSticks(client);
                case MOVE_TABLE_TO_HOTBAR -> moveItemToHotbar(client, Items.CRAFTING_TABLE, false);
                case CLOSE_INVENTORY -> closeInventory(client);
                case PLACE_TABLE -> placeTable(client);
                case OPEN_TABLE -> openTable(client);
                case CRAFT_AXE -> craftAxe(client);
                case MOVE_AXE_TO_HOTBAR -> moveItemToHotbar(client, Items.WOODEN_AXE, true);
                case CLOSE_TABLE -> closeTable(client);
                case EQUIP_AXE -> equipAxe(client);
                case NAVIGATE_TARGET -> navigateTarget(client);
                case MINE_TARGET -> mineTarget(client);
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
            throw new IllegalStateException("survival wooden-axe workflow refuses allowCommands=true");
        }
        if (!policy.allowBlockBreaking() || !policy.allowBlockPlacing()) {
            throw new IllegalStateException("survival wooden-axe workflow requires block breaking and placing permissions");
        }
        if (!freshWorld) {
            throw new IllegalStateException("goal requires a fresh empty world; gameTime=" + worldGameTimeAtStart
                    + ", nonEmptySlots=" + countNonEmpty(player));
        }
        announce(client, "SURVIVAL READY - fresh world, empty inventory");
        transition(Stage.SEARCH_TREES, 40);
    }

    /**
     * A dead player cannot act; grinding the remaining budget against a death screen only
     * hides the cause. Fail immediately with the observed damage source so retry policy and
     * benchmarks can distinguish a suffocation death from a navigation stall.
     */
    private void failFastOnDeath(Minecraft client) {
        var player = client.player;
        if (player == null || (!player.isDeadOrDying() && player.getHealth() > 0.0F)) return;
        var source = player.getLastDamageSource();
        var cause = source == null ? "unknown" : source.type().msgId();
        throw new IllegalStateException("PLAYER_DIED: cause=died:" + cause
                + "; player died during " + stage + " at " + player.blockPosition()
                + telemetrySuffix());
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
            case SEARCH_TREES, ELECT_TREES, EXPLORE -> "search";
            case NAVIGATE_RESOURCE, NAVIGATE_TARGET -> "navigation";
            case MINE_RESOURCE, COLLECT_RESOURCE, MINE_TARGET -> "mining";
            case VERIFY, COMPLETE_DELAY -> "verify";
            default -> "crafting";
        };
    }

    /** Bounded machine-parseable run counters appended to every terminal failure message. */
    private String telemetrySuffix() {
        return " | telemetry{stage=" + stage
                + ",ticks=" + totalTicks
                + ",replans=" + totalNavigationReplans
                + ",reElections=" + treeReElections
                + ",obstructionBreaks=" + obstructionBreaks
                + ",safetyInterventions=" + safetyDiagnostics.size()
                + ",distance=" + rounded(distanceTraveled)
                + ",blocksMined=" + (handMinedLogs + targetMinedLogs)
                + "}";
    }

    private void searchTrees(Minecraft client) {
        var plans = scanTrees(client);
        var reachableCandidates = plans.stream()
                .filter(plan -> !blacklistedTrees.contains(plan.base().asLong())).toList();
        var scanDiagnostic = "tree scan: observed=" + plans.size()
                + ", candidates=" + reachableCandidates.size()
                + ", blacklisted=" + blacklistedTrees.size()
                + ", player=" + requirePlayer(client).blockPosition() + ", pass=" + (searchAttempts + 1);
        navigationDiagnostics.add(scanDiagnostic);
        announce(client, scanDiagnostic);
        var needed = treesNeeded();
        if (reachableCandidates.size() >= needed) {
            candidateTrees = reachableCandidates;
            candidateProbeIndex = 0;
            electedTrees.clear();
            transition(Stage.ELECT_TREES, 0);
            return;
        }
        if (searchAttempts >= MAX_SEARCH_ATTEMPTS) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=resource:exhausted; fewer than "
                    + needed + " reachable small trees after bounded visible exploration; blacklisted="
                    + blacklistedTrees.size() + telemetrySuffix());
        }
        transition(Stage.EXPLORE, 0);
    }

    private int treesNeeded() {
        // After the axe exists, a re-election only needs a replacement target tree; restarting
        // the hand-mining phase against a fresh resource tree would repeat completed work.
        return woodenAxeCrafted ? 1 : 2;
    }

    /**
     * Probe one candidate per tick with a reduced search budget before committing to it.
     * Geometric proximity alone elected trees across ravines and under overhangs; a candidate
     * only becomes the resource or target tree once a route to it is actually plannable. Probe
     * exhaustion means "prefer another candidate", never "proven unreachable".
     */
    private void electTrees(Minecraft client) {
        var needed = treesNeeded();
        var player = requirePlayer(client);
        while (electedTrees.size() < needed && candidateProbeIndex < candidateTrees.size()) {
            var candidate = candidateTrees.get(candidateProbeIndex);
            if (!policy.smartNavigation()) {
                electedTrees.add(candidate);
                candidateProbeIndex++;
                continue;
            }
            var route = NeoForgeSafePathPlanner.probe(client.level, player, player.blockPosition(),
                    candidate.base(), policy, ELECTION_PROBE_BUDGET);
            candidateProbeIndex++;
            if (route.isEmpty()) {
                navigationDiagnostics.add("tree election: probe rejected " + candidate.base()
                        + " (no bounded route)");
                continue;
            }
            electedTrees.add(candidate);
            navigationDiagnostics.add("tree election: probe accepted " + candidate.base()
                    + " (waypoints=" + route.size() + ")");
            // One probe per tick keeps candidate screening off the client tick's critical path.
            if (electedTrees.size() < needed) return;
        }
        if (electedTrees.size() >= needed) {
            resetNavigation();
            if (needed == 1) {
                targetTree = electedTrees.get(0);
                announce(client, "Re-elected reachable target tree - walking with WOODEN AXE");
                transition(Stage.NAVIGATE_TARGET, 20);
            } else {
                resourceTree = electedTrees.get(0);
                targetTree = electedTrees.get(1);
                announce(client, "Found two reachable natural trees - walking to gather wood by hand");
                transition(Stage.NAVIGATE_RESOURCE, 20);
            }
            return;
        }
        if (candidateProbeIndex >= candidateTrees.size()) {
            navigationDiagnostics.add("tree election: exhausted " + candidateTrees.size()
                    + " candidates with " + electedTrees.size() + "/" + needed + " reachable");
            if (searchAttempts >= MAX_SEARCH_ATTEMPTS) {
                throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; no route to "
                        + needed + " candidate trees after " + searchAttempts + " exploration passes"
                        + telemetrySuffix());
            }
            transition(Stage.EXPLORE, 0);
        }
    }

    /**
     * A navigation failure toward an elected tree is a wrong election, not a terminal goal
     * failure. Blacklist the tree and re-elect from the remaining candidates, bounded so a
     * hostile world still terminates with a typed cause.
     */
    private boolean blacklistAndReElect(Minecraft client, TreePlan tree, String label,
                                        IllegalStateException failure) {
        if (tree == null || !isRecoverableNavigationFailure(failure.getMessage())) return false;
        if (++treeReElections > MAX_TREE_RE_ELECTIONS) {
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; " + label
                    + " re-election budget exhausted after " + MAX_TREE_RE_ELECTIONS
                    + " blacklisted trees; last=" + tree.base() + "; " + failure.getMessage()
                    + telemetrySuffix());
        }
        blacklistedTrees.add(tree.base().asLong());
        stopMovement(client);
        resetNavigation();
        var diagnostic = "navigation failure toward " + label + " at " + tree.base()
                + " - blacklisting and re-electing (" + treeReElections + "/" + MAX_TREE_RE_ELECTIONS
                + "): " + failure.getMessage();
        navigationDiagnostics.add(diagnostic);
        safetyDiagnostics.add("tree-re-election:" + label.replace(' ', '-') + ":" + tree.base());
        announce(client, "Route to " + label + " failed - selecting a different tree");
        transition(Stage.SEARCH_TREES, 10);
        return true;
    }

    private static boolean isRecoverableNavigationFailure(String message) {
        if (message == null) return false;
        return message.contains("route unavailable to")
                || message.contains("route remained unavailable to")
                || message.contains("remained obstructed before reaching")
                || message.contains("navigation timed out before reaching")
                || message.contains("detours remained obstructed before reaching");
    }

    private void explore(Minecraft client) {
        var player = requirePlayer(client);
        if (stageTicks == 1) {
            searchAttempts++;
            player.setYRot(player.getYRot() + 55.0F);
            player.setYHeadRot(player.getYRot());
            inputActions.add("look:visible-search-sweep");
            announce(client, "Searching for nearby trees - exploration pass " + searchAttempts);
        }
        setMovement(client, true, true, player.isInWater()
                || (player.onGround() && (stageTicks % 30 < 5)));
        if (stageTicks >= 120) {
            stopMovement(client);
            transition(Stage.SEARCH_TREES, 20);
        }
    }

    private void navigateResource(Minecraft client) {
        try {
            if (navigate(client, resourceTree.base(), "resource tree")) {
                stopMovement(client);
                mineIndex = 0;
                announce(client, "Gathering wood by HAND - 0/" + HAND_LOGS_REQUIRED + " logs");
                transition(Stage.MINE_RESOURCE, 20);
            }
        } catch (IllegalStateException failure) {
            if (!blacklistAndReElect(client, resourceTree, "resource tree", failure)) throw failure;
        }
    }

    private void mineResource(Minecraft client) {
        if (mineIndex >= HAND_LOGS_REQUIRED) {
            stopAttack(client);
            transition(Stage.COLLECT_RESOURCE, 0);
            return;
        }
        var player = requirePlayer(client);
        if (!player.getMainHandItem().isEmpty() && !isBenignHandMiningByproduct(player.getMainHandItem())) {
            // A log, sapling, or stick is benign: breaking a log (or the leaves that decay
            // alongside it) spawns its drop right at the player's feet, and ordinary vanilla
            // auto-pickup can fill the main hand with it before this tick's check runs, ahead of
            // the dedicated walk-to-drops collection stage. That is exactly the progress this
            // stage exists to make, not a fault condition - only some other, genuinely unexpected
            // item warrants stopping.
            throw new IllegalStateException("PRECONDITION_FAILED: cause=precondition:unexpected-held-item; "
                    + "hand-mining stage unexpectedly holds " + player.getMainHandItem().getItem()
                    + telemetrySuffix());
        }
        var target = resourceTree.logs().get(mineIndex);
        var state = client.level.getBlockState(target);
        if (!state.is(BlockTags.LOGS) && !state.isAir()) {
            stopAttack(client);
            if (policy.obstructionMiningEnabled() && breakVisibleMiningObstruction(client, target)) return;
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                    + "resource log target is occupied by non-log block " + target
                    + ": " + state.getBlock().getName().getString() + telemetrySuffix());
        }
        if (state.isAir()) {
            stopAttack(client);
            handMinedLogs++;
            mineIndex++;
            stageTicks = 0;
            miningTargetVantage = null;
            miningVantageForTarget = null;
            miningVantageAttempts = 0;
            rejectedMiningVantages.clear();
            announce(client, "Hand-mined log " + handMinedLogs + "/" + HAND_LOGS_REQUIRED);
            waitTicks = 12;
            return;
        }
        if (!target.equals(miningVantageForTarget)) {
            miningVantageForTarget = target;
            miningTargetVantage = null;
            miningVantageAttempts = 0;
            rejectedMiningVantages.clear();
        }
        if (miningTargetVantage != null) {
            try {
                if (navigate(client, miningTargetVantage, "mining vantage for resource log")) {
                    stopMovement(client);
                    miningTargetVantage = null;
                    stageTicks = 0;
                }
            } catch (IllegalStateException failure) {
                if (!isRecoverableNavigationFailure(failure.getMessage())) throw failure;
                // Mirrors mineTarget()'s handling: the candidate vantage itself proved
                // unreachable, not that the log is unseeable - exclude it and let the next
                // relocation attempt pick a different one instead of aborting the whole goal.
                navigationDiagnostics.add("hand-mining vantage route rejected " + miningTargetVantage
                        + " - trying another: " + failure.getMessage());
                rejectedMiningVantages.add(miningTargetVantage.asLong());
                stopMovement(client);
                resetNavigation();
                miningTargetVantage = null;
            }
            return;
        }
        lookAt(player, target);
        var hit = player.pick(5.0, 0.0F, false);
        var aimedAtTarget = hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && blockHit.getBlockPos().equals(target);
        var leafOcclusion = aimedAtTarget ? null : handBreakableOcclusion(client, hit);
        if (!isAttackableMiningStance(aimedAtTarget, leafOcclusion != null)) {
            // Unlike mineTarget() against the target tree, this early hand-mining stage never
            // had any aim verification at all - a stance that could never land the raycast on
            // the observed log (the trunk's own geometry occluding it from the parked stance,
            // the same class of problem mineTarget() already handles) would just grind the full
            // 360-tick stall timeout with attack held uselessly the whole time. Verify the pick
            // actually lands on the target and, if it does not, reposition with the same bounded,
            // widening vantage search mineTarget() uses rather than attacking blind.
            stopAttack(client);
            if (policy.obstructionMiningEnabled() && breakVisibleMiningObstruction(client, target)) return;
            if (policy.toolPrerequisiteGuardEnabled()) {
                if (stageTicks > 20) {
                    while (miningVantageAttempts < MAX_MINING_VANTAGE_ATTEMPTS) {
                        miningVantageAttempts++;
                        var vantage = findMiningTargetVantage(client, target, resourceTree.base(), miningVantageAttempts);
                        if (vantage != null) {
                            miningTargetVantage = vantage;
                            stageTicks = 0;
                            navigationDiagnostics.add("hand-mining vantage relocation "
                                    + miningVantageAttempts + "/" + MAX_MINING_VANTAGE_ATTEMPTS
                                    + " for resource log " + target + ": " + vantage);
                            announce(client, "Resource log out of reach - repositioning for a clear line of sight");
                            return;
                        }
                        navigationDiagnostics.add("hand-mining vantage relocation " + miningVantageAttempts + "/"
                                + MAX_MINING_VANTAGE_ATTEMPTS + " for resource log " + target
                                + " found no candidate in the widened search");
                    }
                    throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                            + "hand-mining route cannot see resource log " + target
                            + " after " + miningVantageAttempts + " vantage relocation attempts"
                            + "; refusing to attack an unplanned obstruction" + telemetrySuffix());
                }
                navigationDiagnostics.add("hand-mining-target-not-visible:" + target);
                return;
            }
        }
        if (leafOcclusion != null) {
            inputActions.add("recovery:clear-hand-mining-occlusion:" + leafOcclusion);
        }
        clickAndHoldAttack(client, "hand");
        inputActions.add("attack:key.attack-held-by-hand");
        if (stageTicks > 360) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:mining; "
                + "hand mining failed to break observed log " + target + telemetrySuffix());
    }

    /**
     * A raycast that misses the intended log but lands on foliage growing around the same tree
     * (leaves, or a vine hanging off it) is not a stray obstruction the way a wall or terrain
     * block would be: vanilla lets any bare hand clear these instantly, for free, with no tool
     * requirement and no hazard, and a real player chopping a tree routinely does exactly this
     * without a second thought. The pre-fix blind attack-anything behavior happened to clear this
     * kind of occlusion by accident; gating its deliberate replacement behind the adaptive-only
     * {@code obstructionMiningEnabled()} machinery (meant for genuinely costly/hazardous terrain
     * clearing) regressed that for guarded-v1 hand-mining runs that only ever needed a leaf out
     * of the way. Treat it as a safe-by-construction stance in its own right, independent of
     * policy tier, rather than routing it through vantage relocation or the adaptive obstruction
     * gate - returns the occluding position to attack, or null if the miss is something else.
     */
    private static BlockPos handBreakableOcclusion(Minecraft client, net.minecraft.world.phys.HitResult hit) {
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) return null;
        var state = client.level.getBlockState(blockHit.getBlockPos());
        return state.is(BlockTags.LEAVES) || state.is(net.minecraft.world.level.block.Blocks.VINE)
                ? blockHit.getBlockPos() : null;
    }

    /**
     * The control-flow gate both mineResource() and mineTarget() use to decide whether a raycast
     * that missed the primary mining target should still be attacked this tick, or whether the
     * stance needs vantage relocation / the adaptive obstruction gate instead. This is exactly the
     * decision the mineResource() regression got wrong: the pre-fix condition only asked "did the
     * aim land on the primary target", so a miss that actually landed on safe-to-clear foliage (or,
     * in mineTarget()'s case, a redirect-eligible log of the same tree) still fell all the way
     * through to vantage relocation and, at guarded-v1 where {@code obstructionMiningEnabled()} is
     * always false, gave up with target:obstructed instead of just attacking the harmless thing
     * actually in front of the player.
     */
    static boolean isAttackableMiningStance(boolean aimedAtPrimaryTarget, boolean hasAlternateAttackTarget) {
        return aimedAtPrimaryTarget || hasAlternateAttackTarget;
    }

    private void collectResource(Minecraft client) {
        var player = requirePlayer(client);
        var logs = countLogs(player);
        if (logs >= HAND_LOGS_REQUIRED) {
            stopMovement(client);
            announce(client, "Collected " + logs + " hand-mined logs - opening inventory");
            transition(Stage.OPEN_INVENTORY, 20);
            pauseAtCheckpoint(client, "resource-gather");
            return;
        }
        var drops = client.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        player.getBoundingBox().inflate(20.0), entity -> entity.isAlive()
                                && isLogStack(entity.getItem()))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        if (!drops.isEmpty()) {
            var drop = drops.getFirst();
            var dx = drop.getX() - player.getX();
            var dz = drop.getZ() - player.getZ();
            var horizontal = Math.sqrt(dx * dx + dz * dz);
            var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(0.0F);
            setMovement(client, horizontal > 0.25, false, player.isInWater()
                    || (player.onGround() && (drop.getY() > player.getY() + 0.5 || stageTicks % 35 < 5)));
            inputActions.add("move:walk-to-observed-log-drop");
            if (stageTicks == 1 || stageTicks % 80 == 1) {
                announce(client, "Collecting observed log drop at " + drop.blockPosition()
                        + " - inventory " + logs + "/" + HAND_LOGS_REQUIRED);
            }
        } else {
            lookAt(player, resourceTree.base());
            setMovement(client, true, false, player.isInWater()
                    || (player.onGround() && stageTicks % 40 < 5));
            inputActions.add("move:search-near-resource-tree-for-drops");
        }
        if (stageTicks > 600) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:collection; "
                + "hand-mined log drops were not collected through movement; inventory="
                + logs + ", observedDrops=" + drops.size() + ", player=" + player.blockPosition()
                + ", resourceTree=" + resourceTree.base() + telemetrySuffix());
    }

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
        var logSlot = findPlayerItemSlot(screen, NeoForgeSurvivalTreeGoal::isLogStack);
        if (logSlot < 0) throw new IllegalStateException("no collected log is present in player inventory");
        startClicks(List.of(
                new ClickOp(logSlot, 0, ClickType.PICKUP, "pick-up-hand-mined-logs"),
                new ClickOp(1, 0, ClickType.PICKUP, "place-logs-in-2x2-grid"),
                new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-planks")), () -> {
            planksCrafted = countPlanks(requirePlayer(client));
            if (planksCrafted < 12) throw new IllegalStateException("visible plank recipe produced only " + planksCrafted);
            announce(client, "Crafted " + planksCrafted + " planks - crafting table next");
            transition(Stage.CRAFT_TABLE, 20);
        });
    }

    private void craftTable(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
        if (planks < 0) throw new IllegalStateException("planks are missing before crafting table recipe");
        startClicks(List.of(
                new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-table"),
                new ClickOp(1, 1, ClickType.PICKUP, "table-grid-1"),
                new ClickOp(2, 1, ClickType.PICKUP, "table-grid-2"),
                new ClickOp(3, 1, ClickType.PICKUP, "table-grid-3"),
                new ClickOp(4, 1, ClickType.PICKUP, "table-grid-4"),
                new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-planks"),
                new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafting-table")), () -> {
            craftingTableCrafted = countItem(requirePlayer(client), Items.CRAFTING_TABLE) >= 1;
            if (!craftingTableCrafted) throw new IllegalStateException("visible crafting-table recipe did not produce a table");
            announce(client, "Crafting table complete - crafting sticks in visible grid");
            transition(Stage.CRAFT_STICKS, 20);
        });
    }

    private void craftSticks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
        if (planks < 0) throw new IllegalStateException("planks are missing before stick recipe");
        startClicks(List.of(
                new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-sticks"),
                new ClickOp(1, 1, ClickType.PICKUP, "stick-grid-top"),
                new ClickOp(3, 1, ClickType.PICKUP, "stick-grid-bottom"),
                new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-planks"),
                new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-sticks")), () -> {
            sticksCrafted = countItem(requirePlayer(client), Items.STICK);
            if (sticksCrafted < 4) throw new IllegalStateException("visible stick recipe produced only " + sticksCrafted);
            announce(client, "Crafted " + sticksCrafted + " sticks - moving table to hotbar");
            transition(Stage.MOVE_TABLE_TO_HOTBAR, 20);
        });
    }

    private void moveItemToHotbar(Minecraft client, Item item, boolean axe) {
        var screen = requireAnyContainer(client);
        var player = requirePlayer(client);
        var slot = hotbarSlot(player, item);
        if (slot >= 0) {
            if (axe) axeHotbarSlot = slot; else tableHotbarSlot = slot;
            transition(axe ? Stage.CLOSE_TABLE : Stage.CLOSE_INVENTORY, 15);
            return;
        }
        var source = findPlayerItemSlot(screen, stack -> stack.is(item));
        if (source < 0) throw new IllegalStateException("crafted item is missing before hotbar transfer: " + item);
        startClicks(List.of(new ClickOp(source, 0, ClickType.QUICK_MOVE, "move-crafted-item-to-hotbar")), () -> {
            var moved = hotbarSlot(requirePlayer(client), item);
            if (moved < 0) throw new IllegalStateException("normal quick-move did not place crafted item in hotbar: " + item);
            if (axe) axeHotbarSlot = moved; else tableHotbarSlot = moved;
            transition(axe ? Stage.CLOSE_TABLE : Stage.CLOSE_INVENTORY, 15);
        });
    }

    private void closeInventory(Minecraft client) {
        if (client.screen == null) {
            var preferred = resourceTree.base();
            tablePosition = isClearTablePlacement(client, preferred) ? preferred : findTablePlacement(client);
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
        if (client.level.getBlockState(tablePosition).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            announce(client, "Crafting table placed with normal use input - opening 3x3 grid");
            transition(Stage.OPEN_TABLE, 25);
            return;
        }
        if (tableHotbarSlot < 0) throw new IllegalStateException("crafting table hotbar slot is unknown");
        selectHotbar(client, tableHotbarSlot);
        if (!player.getMainHandItem().is(Items.CRAFTING_TABLE)) {
            if (stageTicks > 240) {
                // Live-caught (seed 44210, B2 postfix rescoring v2): normal hotbar selection never
                // held the crafting table within the timeout, even though MOVE_TABLE_TO_HOTBAR had
                // already re-verified the table's slot right after the quick-move that placed it
                // there. Self-heal once against the possibility that the recorded slot has since
                // drifted (the table is still somewhere in the hotbar, just not where this actor
                // last recorded it) before giving up - re-deriving the slot the same way
                // moveItemToHotbar() originally established it, rather than trusting a value that
                // may now be stale. Never confirmed live as the actual root cause (no per-tick
                // inventory snapshot exists in the captured evidence to tell whether the slot truly
                // drifted or the key input itself just never registered), so this is a defensive
                // recovery attempt, not a confirmed fix - if the slot genuinely did not move, this
                // changes nothing and the typed failure below still fires on the very next tick.
                var actualSlot = hotbarSlot(player, Items.CRAFTING_TABLE);
                if (actualSlot >= 0 && actualSlot != tableHotbarSlot) {
                    navigationDiagnostics.add("crafting-table hotbar slot drifted from " + tableHotbarSlot
                            + " to " + actualSlot + " - re-selecting");
                    tableHotbarSlot = actualSlot;
                    stageTicks = 0;
                    return;
                }
                throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:hotbar-selection; "
                        + "normal hotbar selection did not hold the crafting table" + telemetrySuffix());
            }
            return;
        }

        var support = tablePosition.below();
        if (tablePlacementVantage != null) {
            if (navigate(client, tablePlacementVantage, "crafting-table placement vantage")) {
                tablePlacementVantage = null;
                placeTableAimTicks = 0;
            }
            return;
        }
        var supportPoint = new net.minecraft.world.phys.Vec3(support.getX() + 0.5,
                support.getY() + 1.0, support.getZ() + 0.5);
        var distance = player.getEyePosition().distanceTo(supportPoint);
        if (stageTicks == 1 || stageTicks % 80 == 1) {
            var diagnostic = "table placement approach: player=" + player.blockPosition()
                    + ", table=" + tablePosition + ", eyeDistance=" + rounded(distance)
                    + ", held=" + player.getMainHandItem().getItem();
            navigationDiagnostics.add(diagnostic);
            announce(client, diagnostic);
        }
        if (distance > 4.25) {
            placeTableAimTicks = 0;
            if (navigate(client, tablePosition, "crafting table placement")) {
                lookAt(player, support);
                setMovement(client, true, false, player.isInWater() || player.onGround());
            }
            if (stageTicks > 1_200) throw new IllegalStateException("normal-input placement approach remained out of reach; player="
                    + player.blockPosition() + ", table=" + tablePosition + ", eyeDistance=" + rounded(distance));
            return;
        }
        stopMovement(client);
        lookAt(player, support);
        var hit = player.pick(5.0, 0.0F, false);
        var aimedAtSupport = hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && blockHit.getBlockPos().equals(support);
        placeTableAimTicks++;
        if (!aimedAtSupport && placeTableAimTicks > 20) {
            // navigate() above only steers toward tablePosition itself (the air cell the table
            // will occupy) using the same 2.7-3.4 block arrival tolerance every other navigate()
            // caller uses - it was never told to verify a clear line of sight to support, only
            // proximity to tablePosition. isClearTablePlacement already confirmed support is a
            // genuine full-cube surface with a clear sightline from the player's stance at
            // candidate-selection time, but complex terrain (this failure mode's seeds have all
            // been elevated, uneven ground) can leave the arrived-at approach stance with a
            // different, blocked sightline even though support itself is perfectly placeable. A
            // blocked line of sight from a fixed stance never clears on its own; repeating the
            // identical click would just burn the whole 1200-tick timeout on a click that can
            // never land. Mirror openTable()'s own findTableInteractionVantage recovery for the
            // same failure mode one stage later.
            //
            // Live-caught (script seed 302304127329527063 with adaptive-v1, and realtime seed
            // 7777777777777): the original single fixed-size search (radius 1-4, dy -1..+2, one
            // attempt) came back empty on genuinely obstructed terrain and threw immediately.
            // Mirror findMiningTargetVantage's proven attempt-widening pattern instead of giving
            // up after the first, narrowest pass - attempt 1 reproduces the original bounds
            // exactly, so a candidate that was findable before is still found on the first try.
            //
            // Regression caught by the 2026-07-19 B2 official rescoring (seeds 302304127329527063,
            // -8172974586314107235, and 123456789012345 flipped from clean SUCCEEDED runs to this
            // exact failure): placeTableVantageAttempts is a per-goal field, not per-episode, and
            // the original version of this fix never reset it. The pre-existing code could retry
            // this >20-tick aim-timeout relocation an unbounded number of times across the whole
            // PLACE_TABLE stage (bounded only by the unrelated stageTicks > 1_200 timeout below) -
            // each retry deterministically re-finding the same nearest candidate and simply
            // re-confirming/re-clicking it until aim timing eventually landed. Without a reset,
            // every retry episode kept consuming from the same 4-attempt budget, so a seed that
            // legitimately needed a 5th or later episode now hit MAX_TABLE_PLACEMENT_VANTAGE_ATTEMPTS
            // and threw immediately - even though each of those episodes only ever widened the
            // *search radius*, which was never the actual bottleneck for them (the same closest
            // candidate is found at every widened bound once it exists at the narrowest one).
            // Resetting on every successful relocation restores that unbounded-episode-count
            // behavior exactly, while still keeping the *within-episode* widening this fix was
            // meant to add: an episode that finds nothing at the narrow attempt-1 bounds still
            // escalates through attempt 4 before giving up, instead of throwing on the first try.
            while (placeTableVantageAttempts < MAX_TABLE_PLACEMENT_VANTAGE_ATTEMPTS) {
                placeTableVantageAttempts++;
                var relocated = findTablePlacementVantage(client, support, placeTableVantageAttempts);
                if (relocated != null) {
                    tablePlacementVantage = relocated;
                    placeTableAimTicks = 0;
                    placeTableVantageAttempts = 0;
                    navigationDiagnostics.add("table placement vantage relocation: " + relocated);
                    announce(client, "Crafting table support out of sight - repositioning for a clear line of sight");
                    return;
                }
                navigationDiagnostics.add("table placement vantage relocation " + placeTableVantageAttempts + "/"
                        + MAX_TABLE_PLACEMENT_VANTAGE_ATTEMPTS + " found no candidate in the widened search");
            }
            throw new IllegalStateException("no unobstructed crafting-table placement vantage after "
                    + placeTableVantageAttempts + " widening attempts; player=" + player.blockPosition()
                    + ", table=" + tablePosition + ", eyeDistance=" + rounded(distance));
        }
        if (stageTicks % 20 == 1 && aimedAtSupport) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-place-crafting-table");
        }
        if (stageTicks > 1_200) throw new IllegalStateException("normal use input did not place the crafting table; player="
                + player.blockPosition() + ", table=" + tablePosition + ", eyeDistance=" + rounded(distance)
                + ", aimedAtSupport=" + aimedAtSupport + ", held=" + player.getMainHandItem().getItem());
    }

    /**
     * Search for a standable cell with a clear, in-range line of sight to the crafting table's
     * support block - the placement-phase analogue of findTableInteractionVantage, which does the
     * same search for tablePosition itself one stage later (after the table already exists).
     * navigate()'s own arrival tolerance only steers toward proximity to tablePosition, never
     * toward a stance verified to actually see support, so this is what actually recovers a
     * blocked sightline instead of grinding the placement timeout.
     *
     * <p>{@code attempt} (1-based, bounded by MAX_TABLE_PLACEMENT_VANTAGE_ATTEMPTS) widens both
     * the horizontal ring and the vertical band on each retry, mirroring findMiningTargetVantage's
     * proven widening pattern - attempt 1 reproduces the original, unwidened bounds exactly, so a
     * repeated search against the same unchanged support genuinely searches a larger candidate
     * pool instead of deterministically repeating the first call's (possibly empty) result.</p>
     */
    private BlockPos findTablePlacementVantage(Minecraft client, BlockPos support, int attempt) {
        var player = requirePlayer(client);
        var aim = new net.minecraft.world.phys.Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
        var bounds = tablePlacementVantageSearchBounds(attempt);
        for (var radius = 1; radius <= bounds.maxRadius(); radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var dy = bounds.minDy(); dy <= bounds.maxDy(); dy++) {
                        var candidate = tablePosition.offset(dx, dy, dz);
                        if (candidate.equals(tablePosition) || !isStandable(client.level, candidate)) continue;
                        var eye = new net.minecraft.world.phys.Vec3(candidate.getX() + 0.5,
                                candidate.getY() + 1.62, candidate.getZ() + 0.5);
                        if (eye.distanceTo(aim) > 4.25) continue;
                        var clip = client.level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                        if (clip instanceof net.minecraft.world.phys.BlockHitResult blockHit
                                && blockHit.getBlockPos().equals(support)) return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Pure geometry for the horizontal ring radius and vertical search band
     * findTablePlacementVantage uses at a given (1-based) attempt. Attempt 1 reproduces the
     * original, unwidened bounds (radius 4, dy -1..+2 relative to tablePosition) exactly; each
     * later attempt widens both dimensions, mirroring vantageSearchBounds's widening shape.
     */
    static TablePlacementVantageBounds tablePlacementVantageSearchBounds(int attempt) {
        var pad = attempt - 1;
        return new TablePlacementVantageBounds(4 + pad * 2, -1 - pad, 2 + pad);
    }

    record TablePlacementVantageBounds(int maxRadius, int minDy, int maxDy) {
    }

    private BlockPos findTablePlacement(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        for (var radius = 2; radius <= 4; radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var dy = 1; dy >= -3; dy--) {
                        var candidate = origin.offset(dx, dy, dz);
                        if (!isClearTablePlacement(client, candidate)) continue;
                        var supportPos = candidate.below();
                        var distance = player.getEyePosition().distanceTo(
                                new net.minecraft.world.phys.Vec3(supportPos.getX() + 0.5,
                                        supportPos.getY() + 1.0, supportPos.getZ() + 0.5));
                        if (distance <= 4.25) return candidate.immutable();
                    }
                }
            }
        }
        throw new IllegalStateException("no clear in-reach position was observed for normal crafting-table placement; player="
                + origin);
    }

    private boolean isClearTablePlacement(Minecraft client, BlockPos candidate) {
        var player = requirePlayer(client);
        var state = client.level.getBlockState(candidate);
        var supportPos = candidate.below();
        var support = client.level.getBlockState(supportPos);
        var supportShape = support.getCollisionShape(client.level, supportPos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || supportShape.isEmpty()
                || !support.isFaceSturdy(client.level, supportPos, Direction.UP)
                || !isFullUnitCube(supportShape.bounds())
                || player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(candidate))) return false;
        var supportPoint = new net.minecraft.world.phys.Vec3(supportPos.getX() + 0.5,
                supportPos.getY() + 1.0 - 0.001, supportPos.getZ() + 0.5);
        var hit = client.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(), supportPoint,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getBlockPos().equals(supportPos);
    }

    /**
     * placeTable()'s runtime aim targets the support block's nominal cube center
     * (support.getY() + 0.5) every tick until placement succeeds or the goal times out at 1200
     * ticks. That aim only behaves consistently for a genuine full-cube support: a partial-height
     * block (a dirt path, farmland, a slab, a snow layer, ...) can present a non-empty, even
     * sturdy, collision shape while its real surface sits below the nominal cube top the aim
     * assumes, so the placement raycast can miss forever with nothing ever changing to correct
     * it. Require the support's actual collision bounds to fill the entire unit cube, matching
     * what a normal full block would present, so findTablePlacement's ring search keeps scanning
     * past any such candidate instead of committing to one that can never be aimed at reliably.
     */
    static boolean isFullUnitCube(net.minecraft.world.phys.AABB bounds) {
        return bounds.minX <= 0.0 && bounds.maxX >= 1.0
                && bounds.minY <= 0.0 && bounds.maxY >= 1.0
                && bounds.minZ <= 0.0 && bounds.maxZ >= 1.0;
    }

    private void openTable(Minecraft client) {
        if (client.screen instanceof CraftingScreen) {
            tableInteractionVantage = null;
            announce(client, "3x3 crafting table open - arranging wooden axe recipe");
            transition(Stage.CRAFT_AXE, 20);
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
        var sightBlocked = !(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && blockHit.getBlockPos().equals(tablePosition));
        // A blocked sightline (e.g. foliage clipping through the placed table) never clears on
        // its own - repeating the identical click from the identical stance would just burn the
        // whole timeout on a click that can never land. The sibling placeTable() above already
        // verifies its click via a raycast before firing; escalate to a freshly re-verified
        // vantage the moment the sightline is confirmed blocked, instead of grinding to the
        // hard timeout below.
        if (sightBlocked && stageTicks > 20) {
            var relocated = findTableInteractionVantage(client);
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
     * Search for a standable cell with a clear, in-range line of sight to the placed table. A
     * blocked sightline (e.g. an overhanging leaf clipping through the table after placement)
     * never clears on its own; this mirrors findTablePlacement's read-only collision search used
     * before placement, but verifies visibility of the table itself rather than placement
     * legality of its support block.
     */
    private BlockPos findTableInteractionVantage(Minecraft client) {
        var player = requirePlayer(client);
        var aim = net.minecraft.world.phys.Vec3.atCenterOf(tablePosition);
        for (var radius = 1; radius <= 4; radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var dy = -1; dy <= 2; dy++) {
                        var candidate = tablePosition.offset(dx, dy, dz);
                        if (candidate.equals(tablePosition) || !isStandable(client.level, candidate)) continue;
                        var eye = new net.minecraft.world.phys.Vec3(candidate.getX() + 0.5,
                                candidate.getY() + 1.62, candidate.getZ() + 0.5);
                        if (eye.distanceTo(aim) > 4.5) continue;
                        var clip = client.level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                        if (clip instanceof net.minecraft.world.phys.BlockHitResult blockHit
                                && blockHit.getBlockPos().equals(tablePosition)) return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    private void craftAxe(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
        var sticks = findPlayerItemSlot(screen, stack -> stack.is(Items.STICK));
        if (planks < 0 || sticks < 0) throw new IllegalStateException("wooden axe ingredients are missing from player inventory");
        startClicks(List.of(
                new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-axe"),
                new ClickOp(1, 1, ClickType.PICKUP, "axe-plank-1"),
                new ClickOp(2, 1, ClickType.PICKUP, "axe-plank-2"),
                new ClickOp(4, 1, ClickType.PICKUP, "axe-plank-3"),
                new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-planks"),
                new ClickOp(sticks, 0, ClickType.PICKUP, "pick-up-sticks-for-axe"),
                new ClickOp(5, 1, ClickType.PICKUP, "axe-stick-1"),
                new ClickOp(8, 1, ClickType.PICKUP, "axe-stick-2"),
                new ClickOp(sticks, 0, ClickType.PICKUP, "return-unused-sticks"),
                new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-wooden-axe")), () -> {
            woodenAxeCrafted = countItem(requirePlayer(client), Items.WOODEN_AXE) >= 1;
            if (!woodenAxeCrafted) throw new IllegalStateException("visible 3x3 recipe did not produce wooden axe");
            announce(client, "WOODEN AXE crafted - moving it to hotbar");
            transition(Stage.MOVE_AXE_TO_HOTBAR, 25);
        });
    }

    private void closeTable(Minecraft client) {
        if (client.screen == null) {
            transition(Stage.EQUIP_AXE, 15);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-crafting-table");
        if (stageTicks > 80) throw new IllegalStateException("crafting table screen did not close");
    }

    private void equipAxe(Minecraft client) {
        selectHotbar(client, axeHotbarSlot);
        var player = requirePlayer(client);
        woodenAxeEquipped = player.getMainHandItem().is(Items.WOODEN_AXE);
        if (!woodenAxeEquipped) {
            if (stageTicks > 80) throw new IllegalStateException("normal hotbar input did not equip wooden axe");
            return;
        }
        announce(client, "WOODEN AXE EQUIPPED - walking to second tree (" + targetTree.logs().size() + " logs)");
        transition(Stage.NAVIGATE_TARGET, 35);
        pauseAtCheckpoint(client, "craft-axe");
    }

    private void navigateTarget(Minecraft client) {
        try {
            if (navigate(client, targetTree.base(), "target tree")) {
                stopMovement(client);
                mineIndex = 0;
                announce(client, "Target tree reached - mining every log with WOODEN AXE");
                transition(Stage.MINE_TARGET, 25);
            }
        } catch (IllegalStateException failure) {
            if (!blacklistAndReElect(client, targetTree, "target tree", failure)) throw failure;
        }
    }

    private void mineTarget(Minecraft client) {
        if (mineIndex >= targetTree.logs().size()) {
            stopAttack(client);
            transition(Stage.VERIFY, 25);
            return;
        }
        var player = requirePlayer(client);
        if (!player.getMainHandItem().is(Items.WOODEN_AXE)) {
            allTargetLogsMinedWithWoodenAxe = false;
            throw new IllegalStateException("wooden axe was not held during target-tree mining");
        }
        var target = targetTree.logs().get(mineIndex);
        var state = client.level.getBlockState(target);
        if (!state.is(BlockTags.LOGS) && !state.isAir()) {
            stopAttack(client);
            if (policy.obstructionMiningEnabled() && breakVisibleMiningObstruction(client, target)) return;
            throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                    + "target log is occupied by non-log block " + target
                    + ": " + state.getBlock().getName().getString() + telemetrySuffix());
        }
        if (state.isAir()) {
            stopAttack(client);
            targetMinedLogs++;
            mineIndex++;
            stageTicks = 0;
            miningTargetVantage = null;
            miningVantageForTarget = null;
            miningVantageAttempts = 0;
            rejectedMiningVantages.clear();
            announce(client, "Axe-mined target log " + targetMinedLogs + "/" + targetTree.logs().size());
            waitTicks = 10;
            return;
        }
        if (!target.equals(miningVantageForTarget)) {
            miningVantageForTarget = target;
            miningTargetVantage = null;
            miningVantageAttempts = 0;
            rejectedMiningVantages.clear();
        }
        if (miningTargetVantage != null) {
            try {
                if (navigate(client, miningTargetVantage, "mining vantage for target log")) {
                    stopMovement(client);
                    miningTargetVantage = null;
                    stageTicks = 0;
                }
            } catch (IllegalStateException failure) {
                if (!isRecoverableNavigationFailure(failure.getMessage())) throw failure;
                // The candidate vantage itself proved unreachable (not that the log is unseeable) -
                // exclude it and let the next relocation attempt, still bounded by
                // MAX_MINING_VANTAGE_ATTEMPTS, pick a different one instead of aborting the whole
                // goal on the first unreachable candidate.
                navigationDiagnostics.add("mining vantage route rejected " + miningTargetVantage
                        + " - trying another: " + failure.getMessage());
                rejectedMiningVantages.add(miningTargetVantage.asLong());
                stopMovement(client);
                resetNavigation();
                miningTargetVantage = null;
            }
            return;
        }
        lookAt(player, target);
        var hit = player.pick(5.0F, 0.0F, false);
        var attackTarget = target;
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)
                || !blockHit.getBlockPos().equals(target)) {
            // The trunk can occlude a higher sequenced log from the player's current stance even
            // while looking straight at it. If the raycast that misses the sequenced target
            // instead lands on a different, still-standing log of this same tree, attacking that
            // one is just as safe (never a stray block) and productive - the state.isAir() branch
            // above already handles the originally sequenced position for free, once mineIndex
            // reaches it and finds it already empty.
            var redirect = hit instanceof net.minecraft.world.phys.BlockHitResult redirectedHit
                    && targetTree.logs().contains(redirectedHit.getBlockPos())
                    && client.level.getBlockState(redirectedHit.getBlockPos()).is(BlockTags.LOGS)
                    ? redirectedHit.getBlockPos() : null;
            var occlusion = redirect == null ? handBreakableOcclusion(client, hit) : null;
            if (isAttackableMiningStance(false, redirect != null || occlusion != null)) {
                // Foliage growing on the same tree (leaves, a vine) is not a stray obstruction the
                // way a wall or terrain block would be - vanilla lets any bare hand (or an axe, a
                // fortiori) clear it instantly, for free, with no tool requirement and no hazard.
                // Attacking it directly, independent of policy tier, restores the self-clearing
                // effect the old blind-attack behavior had by accident without reintroducing its
                // lack of verification: everything that is NOT a redirect-eligible log or
                // confirmed foliage still falls through to the guarded vantage-relocation/
                // obstruction handling below.
                attackTarget = redirect != null ? redirect : occlusion;
                if (occlusion != null) inputActions.add("recovery:clear-mining-occlusion:" + occlusion);
            } else {
                stopAttack(client);
                if (policy.obstructionMiningEnabled() && breakVisibleMiningObstruction(client, target)) return;
                if (policy.toolPrerequisiteGuardEnabled()) {
                    // A raycast miss with no obstruction and no redirect-eligible log is a stance
                    // problem, not a world problem: mineTarget() never moves once navigateTarget()
                    // parks the player at the tree's base, so a log that is high up the trunk (or
                    // wedged on the trunk's far side) can end up outside the 5-block pick even
                    // though nothing changed underneath it. Grinding the identical raycast from the
                    // identical stance can never land it; reposition to a freshly re-verified vantage
                    // with a confirmed line of sight, mirroring findTableInteractionVantage, and only
                    // escalate to a genuine failure once that repositioning itself is exhausted.
                    if (stageTicks > 20) {
                        while (miningVantageAttempts < MAX_MINING_VANTAGE_ATTEMPTS) {
                            miningVantageAttempts++;
                            var vantage = findMiningTargetVantage(client, target, targetTree.base(), miningVantageAttempts);
                            if (vantage != null) {
                                miningTargetVantage = vantage;
                                stageTicks = 0;
                                navigationDiagnostics.add("mining vantage relocation "
                                        + miningVantageAttempts + "/" + MAX_MINING_VANTAGE_ATTEMPTS
                                        + " for target log " + target + ": " + vantage);
                                announce(client, "Target log out of reach - repositioning for a clear line of sight");
                                return;
                            }
                            navigationDiagnostics.add("mining vantage relocation " + miningVantageAttempts + "/"
                                    + MAX_MINING_VANTAGE_ATTEMPTS + " for target log " + target
                                    + " found no candidate in the widened search");
                        }
                        throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:obstructed; "
                                + "intelligent mining route cannot see target log " + target
                                + " after " + miningVantageAttempts + " vantage relocation attempts"
                                + "; refusing to attack an unplanned obstruction" + telemetrySuffix());
                    }
                    navigationDiagnostics.add("mining-target-not-visible:" + target);
                    return;
                }
            }
        }
        clickAndHoldAttack(client, "wooden-axe");
        inputActions.add("attack:key.attack-held-with-wooden-axe");
        if (stageTicks > 260) throw new IllegalStateException("STUCK_NO_PROGRESS: cause=stall:mining; "
                + "axe mining failed to break observed target log " + attackTarget + telemetrySuffix());
    }

    /**
     * Search for a standable cell with a clear, in-range line of sight to a specific tree log.
     * navigateTarget()/navigateResource() only walk the player to the tree's base once, before
     * mining begins; as mineIndex climbs the trunk, a log positioned higher up (or wedged into
     * the trunk's far side) can end up more than 5 blocks from that fixed ground-level stance
     * even though nothing underneath it changed. The vertical band deliberately spans from just
     * below the tree's own base up to just above the target log itself, but is tried ground level
     * first: a stance near the tree's own base only needs a modest horizontal shuffle plus a
     * steeper look angle to regain reach on most logs, and is immediately walkable with no
     * climbing, whereas a canopy-height platform (tried only once the ground-level ring is
     * exhausted) may need real pathing to reach and can turn out not to be reachable at all. This
     * otherwise mirrors findTableInteractionVantage's read-only standable/line-of-sight search,
     * retargeted at whichever log mineIndex currently names instead of the fixed table position.
     *
     * <p>{@code attempt} (1-based, bounded by MAX_MINING_VANTAGE_ATTEMPTS) widens both the
     * horizontal ring and the vertical band on each retry, so a second, third, or fourth call
     * against the same unchanged target genuinely searches a larger candidate pool instead of
     * deterministically repeating the first call's result.</p>
     */
    private BlockPos findMiningTargetVantage(Minecraft client, BlockPos target, BlockPos treeBase, int attempt) {
        var player = requirePlayer(client);
        var aim = net.minecraft.world.phys.Vec3.atCenterOf(target);
        var bounds = vantageSearchBounds(target, treeBase, attempt);
        for (var radius = 1; radius <= bounds.maxRadius(); radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (var y = bounds.minY(); y <= bounds.maxY(); y++) {
                        var candidate = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                        if (rejectedMiningVantages.contains(candidate.asLong())
                                || !isStandable(client.level, candidate)) continue;
                        var eye = new net.minecraft.world.phys.Vec3(candidate.getX() + 0.5,
                                candidate.getY() + 1.62, candidate.getZ() + 0.5);
                        if (eye.distanceTo(aim) > 4.5) continue;
                        var clip = client.level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                        if (clip instanceof net.minecraft.world.phys.BlockHitResult blockHit
                                && blockHit.getBlockPos().equals(target)) return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Pure geometry for the horizontal ring radius and vertical search band
     * findMiningTargetVantage uses at a given (1-based) attempt. Attempt 1 reproduces the
     * original, unwidened bounds exactly; each later attempt widens both dimensions so a repeated
     * search against the same unchanged target is genuinely larger, not a deterministic repeat of
     * the first attempt's (possibly empty) result.
     */
    static VantageSearchBounds vantageSearchBounds(BlockPos target, BlockPos treeBase, int attempt) {
        var verticalPad = attempt - 1;
        var minY = Math.min(treeBase.getY(), target.getY()) - 1 - verticalPad;
        var maxY = Math.max(target.getY() + 1, minY) + verticalPad;
        var maxRadius = 4 + (attempt - 1) * 2;
        return new VantageSearchBounds(maxRadius, minY, maxY);
    }

    record VantageSearchBounds(int maxRadius, int minY, int maxY) {
    }

    private boolean breakVisibleMiningObstruction(Minecraft client, BlockPos target) {
        var player = requirePlayer(client);
        var hit = client.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),
                net.minecraft.world.phys.Vec3.atCenterOf(target),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)
                || blockHit.getBlockPos().equals(target)
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(blockHit.getBlockPos())) > 30.0) return false;
        var state = client.level.getBlockState(blockHit.getBlockPos());
        if (!NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, blockHit.getBlockPos(), policy)) return false;
        lookAt(player, blockHit.getBlockPos());
        clickAndHoldAttack(client, "tree-obstruction");
        obstructionBreaks++;
        inputActions.add("recovery:break-tree-obstruction:" + blockHit.getBlockPos());
        safetyDiagnostics.add("tree-obstruction:" + blockHit.getBlockPos());
        return true;
    }

    private void verify(Minecraft client) {
        stopAttack(client);
        var player = requirePlayer(client);
        if (!player.isAlive() || player.getHealth() <= 0.0F) {
            throw new IllegalStateException("PLAYER_DIED: cause=died:unknown; "
                    + "player died before wooden-axe tree terminal readback" + telemetrySuffix());
        }
        var remaining = (int) targetTree.logs().stream()
                .filter(position -> client.level.getBlockState(position).is(BlockTags.LOGS)).count();
        if (remaining != 0 || targetMinedLogs != targetTree.logs().size()) {
            throw new IllegalStateException("target tree terminal readback failed: mined=" + targetMinedLogs
                    + ", initial=" + targetTree.logs().size() + ", remaining=" + remaining);
        }
        if (!requirePlayer(client).getMainHandItem().is(Items.WOODEN_AXE)) {
            throw new IllegalStateException("wooden axe is not equipped at terminal readback");
        }
        announce(client, "FULL TREE CLEARED - " + targetMinedLogs + "/" + targetTree.logs().size()
                + " logs mined with WOODEN AXE");
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
        var initialLogs = targetTree == null ? 0 : targetTree.logs().size();
        var remaining = targetTree == null ? 0 : (int) targetTree.logs().stream()
                .filter(position -> client.level.getBlockState(position).is(BlockTags.LOGS)).count();
        return Map.ofEntries(
                Map.entry("checkpoint", checkpoint),
                Map.entry("checkpointComplete", checkpointComplete),
                Map.entry("continuationToken", continuationToken),
                Map.entry("worldObservation", NeoForgeGoalObservation.capture(client, policy)),
                Map.entry("survival", survival), Map.entry("freshWorld", freshWorld),
                Map.entry("worldName", worldName), Map.entry("worldGameTimeAtStart", worldGameTimeAtStart),
                Map.entry("handMinedLogs", handMinedLogs), Map.entry("planksCrafted", planksCrafted),
                Map.entry("sticksCrafted", sticksCrafted), Map.entry("craftingTableCrafted", craftingTableCrafted),
                Map.entry("woodenAxeCrafted", woodenAxeCrafted), Map.entry("woodenAxeEquipped", woodenAxeEquipped),
                Map.entry("targetTreeInitialLogs", initialLogs),
                Map.entry("targetTreeMinedLogs", targetMinedLogs), Map.entry("targetTreeRemainingLogs", remaining),
                Map.entry("fullTreeMined", remaining == 0 && targetMinedLogs == initialLogs),
                Map.entry("allTargetLogsMinedWithWoodenAxe", allTargetLogsMinedWithWoodenAxe),
                Map.entry("playerAlive", player.isAlive() && player.getHealth() > 0.0F),
                Map.entry("healthAtEnd", player.getHealth()),
                Map.entry("commandsUsed", false), Map.entry("directMutationUsed", false),
                Map.entry("intelligence", policy.intelligence().id()), Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()), Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("obstructionBreaks", obstructionBreaks),
                Map.entry("safetyInterventions", List.copyOf(safetyDiagnostics)),
                Map.entry("safetyInterventionCount", safetyDiagnostics.size()),
                Map.entry("navigationReplans", totalNavigationReplans),
                Map.entry("treeReElections", treeReElections),
                Map.entry("blacklistedTrees", blacklistedTrees.size()),
                Map.entry("distanceTraveled", rounded(distanceTraveled)),
                Map.entry("blocksMined", handMinedLogs + targetMinedLogs),
                Map.entry("elapsedTicks", totalTicks),
                Map.entry("observation", policy.observation()), Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowBlockBreaking", policy.allowBlockBreaking()),
                Map.entry("allowBlockPlacing", policy.allowBlockPlacing()),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("suppressInGameMessages", suppressInGameMessages),
                Map.entry("inGameMessagesEmitted", inGameMessagesEmitted),
                Map.entry("navigationDiagnostics", List.copyOf(navigationDiagnostics)),
                Map.entry("inputActions", List.copyOf(inputActions)));
    }

    private static String checkpoint(Map<String, Object> input) {
        var value = input.get("checkpoint");
        var checkpoint = value == null || String.valueOf(value).isBlank()
                ? "complete" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (checkpoint) {
            case "resource-gather", "craft-axe", "complete" -> checkpoint;
            default -> throw new IllegalArgumentException(
                    "survival tree checkpoint must be resource-gather, craft-axe, or complete");
        };
    }

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
            if (policy.obstructionMiningEnabled() && breakVisibleNavigationObstruction(client, target)) {
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

    private boolean breakVisibleNavigationObstruction(Minecraft client, BlockPos target) {
        var player = requirePlayer(client);
        var hit = client.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),
                net.minecraft.world.phys.Vec3.atCenterOf(target),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) return false;
        var position = blockHit.getBlockPos();
        var state = client.level.getBlockState(position);
        if (position.equals(target) || !NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, position, policy)
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) > 30.0) return false;
        lookAt(player, position);
        clickAndHoldAttack(client, "navigation-obstruction");
        obstructionBreaks++;
        inputActions.add("recovery:break-navigation-obstruction:" + position);
        safetyDiagnostics.add("navigation-obstruction:" + position);
        return true;
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
            if (stack.is(ItemTags.PLANKS)) count += stack.getCount();
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

    /**
     * Every item a hand-mining tick's main hand can legitimately, incidentally end up holding
     * ahead of the dedicated collection stage: a log itself (auto-picked-up from the block just
     * broken), a sapling (an equally incidental drop from tree-adjacent leaf breakage or natural
     * decay during hand-mining, regardless of which sapling variant matches whatever tree type
     * was targeted), or a stick (another common incidental tree-adjacent drop). None of these
     * represent a genuinely unexpected item warranting a precondition failure.
     */
    private static boolean isBenignHandMiningByproduct(ItemStack stack) {
        return isLogStack(stack) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK);
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

    /** Start a vanilla destroy session, then keep the ordinary attack mapping held. */
    private void clickAndHoldAttack(Minecraft client, String label) {
        if (stageTicks % 10 == 1) {
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
        SEARCH_TREES,
        ELECT_TREES,
        EXPLORE,
        NAVIGATE_RESOURCE,
        MINE_RESOURCE,
        COLLECT_RESOURCE,
        OPEN_INVENTORY,
        CRAFT_PLANKS,
        CRAFT_TABLE,
        CRAFT_STICKS,
        MOVE_TABLE_TO_HOTBAR,
        CLOSE_INVENTORY,
        PLACE_TABLE,
        OPEN_TABLE,
        CRAFT_AXE,
        MOVE_AXE_TO_HOTBAR,
        CLOSE_TABLE,
        EQUIP_AXE,
        NAVIGATE_TARGET,
        MINE_TARGET,
        VERIFY,
        COMPLETE_DELAY
    }

    private record ClickOp(int slot, int button, ClickType type, String label) {
    }

    private record TreePlan(BlockPos base, List<BlockPos> logs, double distanceSquared) {
    }
}
