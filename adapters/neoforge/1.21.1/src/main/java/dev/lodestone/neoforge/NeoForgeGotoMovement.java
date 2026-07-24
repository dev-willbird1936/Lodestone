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
    private static final int LATERAL_DETOUR_OFFSET = 4;
    private static final int SOFT_MINE_TIMEOUT_TICKS = 30;
    /** After this many abandoned mine attempts (soft or hard) in one invocation, stop retrying and
     * report repeated-mutation-failure instead of grinding out the full timeout budget. */
    private static final int MAX_MUTATION_FAILURES = 3;
    // Live-caught "dancing" bug: the walker re-aimed at the current path node's own block Y (feet
    // level) every single tick, producing a steep, ever-changing down-pitch at close range that
    // never settled, plus numerically unstable yaw right on top of a node - see reaimTowardWalkNode
    // and nodeConsumed's own doc. These four constants are the fix's tunables, not derived
    // game-mechanics values.
    private static final float REAIM_YAW_THRESHOLD_DEGREES = 10.0F;
    private static final double NODE_ARRIVAL_HORIZONTAL_DISTANCE = 0.7;
    private static final int STUCK_TICKS_BEFORE_JUMP_ASSIST = 15;
    private static final int MAX_JUMP_ASSISTS_BEFORE_REPLAN = 2;
    private static final double SPRINT_DISTANCE_THRESHOLD_BLOCKS = 6.0;

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

    /** Pure stuck-recovery escalation ladder - see {@link #nextStuckAction}'s own doc. */
    enum StuckAction { CONTINUE, JUMP_ASSIST, REPLAN, GIVE_UP }

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
    private int jumpAssistsUsed;
    /** Whether this movement instance has already spent its one stuck-triggered replan (see {@link
     * #nextStuckAction}'s own doc) - never reset back to false once set, unlike {@link
     * #jumpAssistsUsed}/{@link #stuckTicks}, which reset on ordinary progress or an ordinary replan;
     * this is a lifetime ratchet for the whole invocation so a persistently stuck walk fails fast
     * exactly once instead of replanning forever. */
    private boolean stuckReplanUsed;
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

        while (pathIndex < path.size() && nodeConsumed(player, path.get(pathIndex).position())) {
            pathIndex++;
            stuckTicks = 0;
            jumpAssistsUsed = 0;
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
        if (distance >= lastDistance - 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            jumpAssistsUsed = 0;
        }
        lastDistance = distance;

        var jumpAssist = false;
        switch (nextStuckAction(stuckTicks, jumpAssistsUsed, stuckReplanUsed,
                STUCK_TICKS_BEFORE_JUMP_ASSIST, MAX_JUMP_ASSISTS_BEFORE_REPLAN)) {
            case JUMP_ASSIST -> {
                jumpAssistsUsed++;
                stuckTicks = 0;
                jumpAssist = true;
                diagnostics.add("stuck:jump-assist-" + jumpAssistsUsed + "-at-" + player.blockPosition());
            }
            case REPLAN -> {
                stuckReplanUsed = true;
                jumpAssistsUsed = 0;
                stuckTicks = 0;
                diagnostics.add("stuck:replanning-at-" + player.blockPosition());
                path = List.of();
                return Outcome.MOVING;
            }
            case GIVE_UP -> {
                diagnostics.add("stuck:giving-up-at-" + player.blockPosition());
                releaseInput(client);
                return Outcome.NO_ROUTE;
            }
            case CONTINUE -> { }
        }

        reaimTowardWalkNode(player, waypoint);
        var ascendingStep = waypoint.getY() > player.blockPosition().getY() && player.onGround();
        var sprint = shouldSprint(horizontalDistance(player, target));
        setMovement(client, true, sprint, jumpAssist || ascendingStep);
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
        jumpAssistsUsed = 0;
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

    /**
     * A path node counts as consumed once the player is within a loose horizontal radius of it,
     * regardless of exact sub-block position, and within one step height vertically - never
     * demanding exact-cell occupancy. See {@link #nodeConsumed(double, double)}'s own doc for why
     * this matters beyond just "close enough": lingering right on top of a node long enough is
     * exactly what let the old per-tick re-aim's yaw computation go numerically unstable.
     */
    private static boolean nodeConsumed(LocalPlayer player, BlockPos position) {
        return nodeConsumed(horizontalDistance(player, position),
                Math.abs(player.blockPosition().getY() - position.getY()));
    }

    /**
     * Pure node-arrival decision, isolated from world/entity access so it's directly unit-testable.
     * A live-caught bug ("dancing": the player stopped on a node and rapidly spun in place, pitching
     * back down to "all the way down" each time) traced partly to this tolerance being both too
     * tight (0.8, encouraging the walker to linger closer than necessary) and to {@code closeTo}'s
     * old name inviting exact-cell thinking; {@code verticalDistance} deliberately only needs to be
     * within one ordinary step height (1 block) - never exact-cell occupancy.
     */
    static boolean nodeConsumed(double horizontalDistance, double verticalDistance) {
        return horizontalDistance < NODE_ARRIVAL_HORIZONTAL_DISTANCE && verticalDistance <= 1.0;
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The eye-level walking re-aim: turns toward the next path node only when the yaw error exceeds
     * {@link #REAIM_YAW_THRESHOLD_DEGREES}, never every tick. This is the actual fix for the
     * live-caught "dancing" bug (operator-observed: the player looked at the floor and back up on
     * every step) - the old code called {@link #lookAt(LocalPlayer, BlockPos)} unconditionally each
     * tick, which aims at the node's own block Y (close to feet level); from an eye position roughly
     * 1.6 blocks higher, at the short range between consecutive path nodes, that produces a steep,
     * ever-changing down-pitch that never settles, and - right on top of a node, at near-zero
     * horizontal distance - a numerically unstable yaw (tiny position jitter flips the sign of the
     * atan2 inputs), which is what actually caused the reported "stops and rapidly turns around".
     * Aiming at the player's own eye height instead keeps pitch level while walking (0 unless
     * genuinely ascending/descending terrain); only {@link #continueHardMine}/{@link
     * #continueSoftMine}'s own block-level aim should ever pitch down, and they are unaffected by
     * this method entirely.
     */
    private static void reaimTowardWalkNode(LocalPlayer player, BlockPos node) {
        var eye = player.getEyePosition();
        var aim = eyeLevelAimTarget(node, eye.y);
        var angles = computeLookAngles(eye, aim);
        if (!yawNeedsReaim(player.getYRot(), angles.yaw())) return;
        player.setYRot(angles.yaw());
        player.setYHeadRot(angles.yaw());
        player.setXRot(angles.pitch());
    }

    /**
     * Eye-level aim target for walking toward a path node: same X/Z as the node's center, but the
     * player's OWN eye height rather than the node's own block Y - see {@link
     * #reaimTowardWalkNode}'s own doc for why. Package-private and pure for direct testing: {@code
     * target.y} must equal {@code eyeY} regardless of {@code node.getY()}.
     */
    static Vec3 eyeLevelAimTarget(BlockPos node, double eyeY) {
        return new Vec3(node.getX() + 0.5, eyeY, node.getZ() + 0.5);
    }

    /**
     * Whether a fresh yaw computation differs enough from the player's current yaw to be worth
     * actually applying - more than {@link #REAIM_YAW_THRESHOLD_DEGREES}, wrapped through {@link
     * #wrapDegrees} so a target just the "wrong side" of the -180/180 seam is never mistaken for a
     * huge error. Package-private and pure for direct testing.
     */
    static boolean yawNeedsReaim(float currentYaw, float desiredYaw) {
        return Math.abs(wrapDegrees(desiredYaw - currentYaw)) > REAIM_YAW_THRESHOLD_DEGREES;
    }

    /** Wraps a yaw delta into (-180, 180] so a re-aim threshold check isn't fooled by the
     * 360-degree wraparound. Package-private and pure for direct testing. */
    static float wrapDegrees(float degrees) {
        var wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    /** Whether a route still has enough remaining distance left to be worth sprinting for -
     * short hops (e.g. the last step into arrival radius) don't need it. Package-private and pure
     * for direct testing. */
    static boolean shouldSprint(double remainingDistanceToTarget) {
        return remainingDistanceToTarget > SPRINT_DISTANCE_THRESHOLD_BLOCKS;
    }

    /**
     * Pure stuck-recovery escalation ladder, isolated from world/input access so it's directly
     * unit-testable. A live-caught bug had the old handler do nothing for 45 stuck ticks and then
     * silently force a bare replan forever, which - combined with the aiming bug above - looked like
     * the player "circles/spins at the node" instead of making an honest recovery attempt. The new
     * ladder is a strict, one-shot escalation for the whole movement instance: try a bounded number
     * of jump-assists first (a stuck player is very often just caught on a lip or a fence post), then
     * spend the one stuck-triggered replan this instance is allowed (see {@link #stuckReplanUsed}'s
     * own doc for why that flag never resets), and if the walker is STILL stuck after that, give up
     * and report honestly instead of repeating the cycle for the rest of the timeout budget.
     */
    static StuckAction nextStuckAction(int stuckTicks, int jumpAssistsUsed, boolean stuckReplanUsed,
                                        int stuckTicksBeforeJumpAssist, int maxJumpAssistsBeforeReplan) {
        if (stuckTicks < stuckTicksBeforeJumpAssist) return StuckAction.CONTINUE;
        // Once the one stuck-triggered replan has been spent, every later stuck episode gives up
        // immediately - never restarts the jump-assist ladder, however jumpAssistsUsed itself was
        // reset in the meantime - so this is a true one-shot escalation, not a repeating cycle.
        if (stuckReplanUsed) return StuckAction.GIVE_UP;
        if (jumpAssistsUsed < maxJumpAssistsBeforeReplan) return StuckAction.JUMP_ASSIST;
        return StuckAction.REPLAN;
    }

    /** Pure yaw/pitch result of aiming from one point at another - see {@link
     * #computeLookAngles}. */
    record LookAngles(float yaw, float pitch) { }

    /** Pure look-angle math, isolated from the player object so it's directly unit-testable; {@link
     * #lookAt(LocalPlayer, Vec3)} and {@link #reaimTowardWalkNode} both apply its result rather than
     * duplicating the atan2 formulas. */
    static LookAngles computeLookAngles(Vec3 eye, Vec3 target) {
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new LookAngles(yaw, Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    /** Block-level aim, used only by {@link #continueHardMine}/{@link #continueSoftMine} - a
     * mining/placing aim legitimately needs to pitch down at the obstruction itself, unlike ordinary
     * walking (see {@link #reaimTowardWalkNode}). Re-aims every tick, unlike walking, since landing
     * the mining raycast needs precision the yaw-hysteresis would otherwise blur. */
    private static void lookAt(LocalPlayer player, BlockPos target) {
        lookAt(player, new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        var angles = computeLookAngles(player.getEyePosition(), target);
        player.setYRot(angles.yaw());
        player.setYHeadRot(angles.yaw());
        player.setXRot(angles.pitch());
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
