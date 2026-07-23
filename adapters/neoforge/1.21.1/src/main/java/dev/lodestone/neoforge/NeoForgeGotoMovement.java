// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared point-to-point movement engine reused by {@code minecraft.goal.move.goto},
 * {@code minecraft.goal.gather.collect-drops}, and {@code minecraft.goal.gather.chop-tree} - see
 * each goal's own class for its thin wrapper. Deliberately reuses {@link NeoForgeSafePathPlanner}
 * and {@link NeoForgeWorldSnapshot} rather than forking the A* search: this class only adds two
 * behaviors {@code minecraft.goal.navigation.safe-waypoint} does not have -
 * <ul>
 *   <li>soft-foliage clearing: a bounded, reflex-level mine of a leaf/vine cell directly blocking
 *   the next waypoint, instead of relying on the planner's own (adaptive-only) obstruction mining;
 *   see {@link #isSoftFoliageBlockId}.</li>
 *   <li>a single lateral-detour retry (candidate waypoints offset perpendicular to the direct line)
 *   before a "no route" plan is accepted as final; see {@link #lateralDetourCandidates}.</li>
 * </ul>
 * Everything else (replanning cadence, stuck detection, hard mine-through-obstruction execution)
 * mirrors {@link NeoForgeNavigationGoal} closely, reusing its package-private pure helpers
 * ({@link NeoForgeNavigationGoal#confirmedArrival}, {@link NeoForgeNavigationGoal#mineTimeoutTicks},
 * {@link NeoForgeNavigationGoal#nearestReachablePoint}) instead of duplicating them.
 */
final class NeoForgeGotoMovement {
    private static final int REPLAN_INTERVAL_TICKS = 20;
    private static final int STUCK_TICKS_BEFORE_REPLAN = 45;
    private static final int LATERAL_DETOUR_OFFSET = 4;
    private static final int SOFT_MINE_TIMEOUT_TICKS = 30;
    /** After this many abandoned mine attempts (soft or hard) in one invocation, stop retrying and
     * report repeated-mutation-failure instead of grinding out the full timeout budget. */
    private static final int MAX_MUTATION_FAILURES = 3;

    /** Vanilla block ids that are cheap, safe, and expected to be cleared on sight rather than
     * routed around or reported as a failure - see this class's own javadoc. Not exhaustive of
     * every leaf/flower variant by id (leaves are matched by suffix instead, see
     * {@link #isSoftFoliageBlockId}), just of everything else the spec calls out by name. */
    private static final Set<String> SOFT_FOLIAGE_IDS = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:grass", "minecraft:fern",
            "minecraft:large_fern", "minecraft:dead_bush", "minecraft:vine", "minecraft:glow_lichen",
            "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
            "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
            "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy",
            "minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:torchflower",
            "minecraft:wither_rose", "minecraft:sunflower", "minecraft:lilac", "minecraft:peony",
            "minecraft:rose_bush", "minecraft:pitcher_plant");

    enum Outcome { MOVING, ARRIVED, NO_ROUTE, MUTATION_FAILURE }

    record Diagnosis(BlockPos nearestReachable, List<Map<String, Object>> obstructionSample) {
    }

    private final NeoForgeGoalPolicy policy;
    private final boolean allowSoftFoliageClearing;
    private final List<String> diagnostics = new ArrayList<>();

    private List<NeoForgeSafePathPlanner.PathStep> path = List.of();
    private int pathIndex;
    private int ticksSinceReplan;
    private int replans;
    private int stuckTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private boolean lateralDetourAttempted;
    private int blocksMined;
    private int mutationFailures;

    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    private BlockPos mineTarget;
    private int mineTicks;
    private BlockPos softMineTarget;
    private int softMineTicks;

    /**
     * @param allowMining full mine-through-any-obstruction, delegated to the planner's own
     *                    adaptive-v1 obstruction mining.
     * @param allowBlockBreaking soft-foliage clearing only (unless {@code allowMining} is also set,
     *                           in which case it implies breaking capability regardless).
     */
    NeoForgeGotoMovement(boolean allowMining, boolean allowBlockBreaking) {
        var effectiveBreaking = allowBlockBreaking || allowMining;
        this.policy = new NeoForgeGoalPolicy(
                allowMining ? NeoForgeGoalPolicy.Intelligence.ADAPTIVE_V1 : NeoForgeGoalPolicy.Intelligence.GUARDED_V1,
                NeoForgeGoalPolicy.Safety.BALANCED, "loaded-chunks", "defensive",
                effectiveBreaking, false, false, NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS);
        this.allowSoftFoliageClearing = effectiveBreaking;
    }

    int blocksMined() {
        return blocksMined;
    }

    int replans() {
        return replans;
    }

    List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    Outcome tick(Minecraft client, LocalPlayer player, BlockPos target, double arriveRadius) {
        if (reached(player, target, arriveRadius)) {
            releaseInput(client);
            return Outcome.ARRIVED;
        }
        if (mutationFailures >= MAX_MUTATION_FAILURES) {
            releaseInput(client);
            return Outcome.MUTATION_FAILURE;
        }
        if (softMineTarget != null) {
            continueSoftMine(client, player);
            return Outcome.MOVING;
        }
        if (mineTarget != null || !mineQueue.isEmpty()) {
            continueHardMine(client, player);
            return Outcome.MOVING;
        }

        ticksSinceReplan++;
        if (path.isEmpty() || pathIndex >= path.size() || ticksSinceReplan >= REPLAN_INTERVAL_TICKS) {
            replan(client, player, target);
            ticksSinceReplan = 0;
        }
        if (path.isEmpty()) {
            releaseInput(client);
            return Outcome.NO_ROUTE;
        }

        while (pathIndex < path.size() && closeTo(player, path.get(pathIndex).position())) {
            pathIndex++;
            stuckTicks = 0;
            lastDistance = Double.POSITIVE_INFINITY;
        }
        if (reached(player, target, arriveRadius)) {
            releaseInput(client);
            return Outcome.ARRIVED;
        }
        if (pathIndex >= path.size()) {
            replan(client, player, target);
            if (path.isEmpty()) {
                releaseInput(client);
                return Outcome.NO_ROUTE;
            }
        }

        var step = path.get(pathIndex);
        if (step.kind() == NeoForgeSafePathPlanner.MutationKind.MINE) {
            beginHardMine(client, step);
            continueHardMine(client, player);
            return Outcome.MOVING;
        }

        if (allowSoftFoliageClearing && beginSoftMineIfBlocking(client, step.position())) {
            continueSoftMine(client, player);
            return Outcome.MOVING;
        }

        var waypoint = step.position();
        var distance = horizontalDistance(player, waypoint);
        if (distance >= lastDistance - 0.01) stuckTicks++;
        else stuckTicks = 0;
        lastDistance = distance;
        if (stuckTicks > STUCK_TICKS_BEFORE_REPLAN) {
            diagnostics.add("stuck:replanning-at-" + player.blockPosition());
            path = List.of();
            return Outcome.MOVING;
        }
        lookAt(player, waypoint);
        setMovement(client, true, false, waypoint.getY() > player.blockPosition().getY() && player.onGround());
        return Outcome.MOVING;
    }

    Diagnosis diagnose(Minecraft client, LocalPlayer player, BlockPos target) {
        var reachability = NeoForgeSafePathPlanner.floodFillReachable(client.level, player,
                player.blockPosition(), policy, 20_000);
        var nearest = NeoForgeNavigationGoal.nearestReachablePoint(target, reachability.reached());
        if (nearest == null) return new Diagnosis(null, List.of());
        var samples = new ArrayList<Map<String, Object>>();
        for (var direction : Direction.values()) {
            if (samples.size() >= 3) break;
            var neighbor = nearest.relative(direction);
            if (!client.level.hasChunkAt(neighbor)) continue;
            var state = client.level.getBlockState(neighbor);
            if (state.isAir()) continue;
            samples.add(Map.of("position", positionMap(neighbor), "block", NeoForgeHardScript.blockId(state)));
        }
        return new Diagnosis(nearest, List.copyOf(samples));
    }

    void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }

    private void replan(Minecraft client, LocalPlayer player, BlockPos target) {
        mineTarget = null;
        mineQueue.clear();
        mineTicks = 0;
        softMineTarget = null;
        softMineTicks = 0;
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        var origin = player.blockPosition();
        var found = NeoForgeSafePathPlanner.findSteps(client.level, player, origin, target, policy);
        if (found.isEmpty() && !lateralDetourAttempted) {
            lateralDetourAttempted = true;
            for (var candidate : lateralDetourCandidates(origin, target, LATERAL_DETOUR_OFFSET)) {
                if (!client.level.isInWorldBounds(candidate) || !client.level.isLoaded(candidate)) continue;
                var detour = NeoForgeSafePathPlanner.findSteps(client.level, player, origin, candidate, policy);
                if (!detour.isEmpty()) {
                    diagnostics.add("lateral-detour:" + candidate);
                    found = detour;
                    break;
                }
            }
        }
        path = found;
        pathIndex = path.size() > 1 ? 1 : 0;
        replans++;
        stuckTicks = 0;
        lastDistance = Double.POSITIVE_INFINITY;
    }

    private boolean beginSoftMineIfBlocking(Minecraft client, BlockPos waypoint) {
        for (var candidate : List.of(waypoint, waypoint.above())) {
            var state = client.level.getBlockState(candidate);
            if (state.getCollisionShape(client.level, candidate).isEmpty()) continue;
            if (isSoftFoliageBlockId(NeoForgeHardScript.blockId(state))) {
                softMineTarget = candidate.immutable();
                softMineTicks = 0;
                return true;
            }
        }
        return false;
    }

    private void continueSoftMine(Minecraft client, LocalPlayer player) {
        var state = client.level.getBlockState(softMineTarget);
        if (state.getCollisionShape(client.level, softMineTarget).isEmpty()) {
            blocksMined++;
            diagnostics.add("soft-foliage-cleared:" + softMineTarget);
            endSoftMine(client);
            return;
        }
        if (!NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, softMineTarget, policy)) {
            diagnostics.add("soft-foliage-veto:" + softMineTarget);
            mutationFailures++;
            endSoftMine(client);
            return;
        }
        if (++softMineTicks > SOFT_MINE_TIMEOUT_TICKS) {
            diagnostics.add("soft-foliage-timeout:" + softMineTarget);
            mutationFailures++;
            endSoftMine(client);
            return;
        }
        setMovement(client, false, false, false);
        lookAt(player, softMineTarget);
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(softMineTarget)) {
            if (softMineTicks % 5 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
        } else {
            client.options.keyAttack.setDown(false);
        }
    }

    private void endSoftMine(Minecraft client) {
        softMineTarget = null;
        softMineTicks = 0;
        client.options.keyAttack.setDown(false);
    }

    private void beginHardMine(Minecraft client, NeoForgeSafePathPlanner.PathStep step) {
        if (mineTarget != null || !mineQueue.isEmpty()) return;
        for (var candidate : step.mutationTargets()) {
            var state = client.level.getBlockState(candidate);
            if (!state.isAir() && state.getFluidState().isEmpty()) mineQueue.add(candidate.immutable());
        }
        mineTarget = mineQueue.poll();
        mineTicks = 0;
    }

    private void continueHardMine(Minecraft client, LocalPlayer player) {
        if (mineTarget == null) return;
        var state = client.level.getBlockState(mineTarget);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            blocksMined++;
            diagnostics.add("mine-cleared:" + mineTarget);
            mineTarget = mineQueue.poll();
            mineTicks = 0;
            if (mineTarget == null) client.options.keyAttack.setDown(false);
            return;
        }
        if (!NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, mineTarget, policy)) {
            diagnostics.add("mine-veto:" + mineTarget);
            abandonHardMine(client);
            return;
        }
        var progressPerTick = state.getDestroyProgress(player, client.level, mineTarget);
        if (progressPerTick <= 0.0) {
            diagnostics.add("mine-unbreakable:" + mineTarget);
            abandonHardMine(client);
            return;
        }
        if (++mineTicks > NeoForgeNavigationGoal.mineTimeoutTicks(progressPerTick)) {
            diagnostics.add("mine-timeout:" + mineTarget);
            abandonHardMine(client);
            return;
        }
        setMovement(client, false, false, false);
        lookAt(player, mineTarget);
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(mineTarget)) {
            if (mineTicks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
        } else {
            client.options.keyAttack.setDown(false);
        }
    }

    private void abandonHardMine(Minecraft client) {
        client.options.keyAttack.setDown(false);
        mineTarget = null;
        mineQueue.clear();
        mineTicks = 0;
        mutationFailures++;
        path = List.of();
    }

    private boolean reached(LocalPlayer player, BlockPos target, double arriveRadius) {
        var withinRadius = horizontalDistance(player, target) <= arriveRadius
                && Math.abs(player.blockPosition().getY() - target.getY()) <= Math.max(2, (int) Math.ceil(arriveRadius));
        var level = player.level();
        var feet = player.blockPosition();
        var feetBlocked = !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        var head = feet.above();
        var headBlocked = !level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        return NeoForgeNavigationGoal.confirmedArrival(withinRadius, feetBlocked, headBlocked);
    }

    private static boolean closeTo(LocalPlayer player, BlockPos position) {
        return horizontalDistance(player, position) <= 0.8
                && Math.abs(player.blockPosition().getY() - position.getY()) <= 1;
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        lookAt(player, new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        var eye = player.getEyePosition();
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

    private static void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(false);
    }

    static Map<String, Object> positionMap(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    /** True for any block id this movement core treats as cheap/safe to clear on sight rather than
     * route around or fail on - leaves (matched by the vanilla {@code *_leaves} naming convention,
     * covering every wood species without an exhaustive list) plus the fixed
     * {@link #SOFT_FOLIAGE_IDS} set of grasses, ferns, vines, and flowers. Package-private and pure
     * for direct testing. */
    static boolean isSoftFoliageBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        var id = blockId.trim().toLowerCase(Locale.ROOT);
        return id.endsWith("_leaves") || SOFT_FOLIAGE_IDS.contains(id);
    }

    /**
     * Two candidate intermediate waypoints offset {@code offset} blocks perpendicular to the
     * straight line from {@code from} to {@code target}, anchored at its midpoint - used to retry a
     * failed plan once before reporting no-route (see this class's own javadoc). Returns an empty
     * list when {@code from} and {@code target} share the same horizontal position (no well-defined
     * perpendicular). Package-private and pure for direct testing.
     */
    static List<BlockPos> lateralDetourCandidates(BlockPos from, BlockPos target, int offset) {
        var dx = target.getX() - from.getX();
        var dz = target.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return List.of();
        var length = Math.sqrt((double) dx * dx + (double) dz * dz);
        var perpX = -dz / length;
        var perpZ = dx / length;
        var midX = (from.getX() + target.getX()) / 2.0;
        var midY = Math.floorDiv(from.getY() + target.getY(), 2);
        var midZ = (from.getZ() + target.getZ()) / 2.0;
        var left = new BlockPos((int) Math.round(midX + perpX * offset), midY, (int) Math.round(midZ + perpZ * offset));
        var right = new BlockPos((int) Math.round(midX - perpX * offset), midY, (int) Math.round(midZ - perpZ * offset));
        return List.of(left, right);
    }
}
