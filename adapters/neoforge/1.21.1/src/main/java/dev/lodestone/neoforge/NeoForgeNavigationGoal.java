// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bounded benchmark actor for testing ordinary-input navigation against constructed fixtures. */
final class NeoForgeNavigationGoal {
    private static final int MAX_TOTAL_TICKS = 2_400;
    // Bounded normal-input timeout for a single PLACE attempt (hotbar select + aim + use-click),
    // mirroring the ~100-tick bound NeoForgeGoalSupervisor already uses for its own reactive
    // adjacent-escape attempts.
    private static final int PLACE_TIMEOUT_TICKS = 100;
    // After this many abandoned mine/place attempts in one invocation, stop retrying (the same
    // obstruction would otherwise just get re-proposed by the next replan and fail the same way
    // until MAX_TOTAL_TICKS burns out) and report a structured failure instead.
    private static final int MAX_MUTATION_FAILURES = 3;

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final NeoForgeGoalPolicy policy;
    private final BlockPos target;
    private final List<String> actions = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final NeoForgeGoalSupervisor supervisor;

    private List<NeoForgeSafePathPlanner.PathStep> path = List.of();
    private int pathIndex;
    private int totalTicks;
    private int replans;
    private int pathNodesVisited;
    private int stuckTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private boolean directFallback;

    // Mutation execution state: consumes the MutationKind carried by each PathStep (adaptive-v1
    // only - see NeoForgeSafePathPlanner.relaxNeighbor) and performs the real ordinary-input action
    // instead of walking the plan down to bare positions and hoping the walker survives contact
    // with whatever the plan intended to mine through or bridge across.
    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    private BlockPos mineTarget;
    private int mineTicks;
    private BlockPos placeTarget;
    private int placeTicks;
    private int mutationFailures;
    private int blocksMined;
    private int blocksPlaced;

    NeoForgeNavigationGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
        this.target = new BlockPos(requiredInt(invocation, "targetX"),
                requiredInt(invocation, "targetY"), requiredInt(invocation, "targetZ"));
        this.supervisor = new NeoForgeGoalSupervisor(policy, actions, diagnostics);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("benchmark navigation exceeded its bounded input budget");
            }
            if (client.level == null || client.player == null || client.gameMode == null
                    || client.screen != null) return;
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("benchmark navigation requires survival or adventure mode");
            }
            if (policy.allowCommands()) {
                throw new IllegalStateException("survival navigation workflow refuses allowCommands=true");
            }
            if (reached(client.player)) {
                complete(client);
                return;
            }
            if (supervisor.tick(client)) return;

            var player = client.player;
            if (!policy.smartNavigation()) {
                directFallback = true;
                lookAt(player, target);
                setMovement(client, true, false, target.getY() > player.blockPosition().getY() && player.onGround());
                actions.add("move:raw-direct-to-target");
                return;
            }

            if (path.isEmpty() || pathIndex >= path.size() || totalTicks % 20 == 0) {
                replan(client);
            }
            if (path.isEmpty()) {
                if (policy.smartNavigation()) {
                    completeUnreached(client, "no-safe-route-from-current-position");
                    return;
                }
                directFallback = true;
                lookAt(player, target);
                setMovement(client, true, false, false);
                actions.add("move:direct-fallback-to-target");
                return;
            }

            while (pathIndex < path.size() && closeTo(player, path.get(pathIndex).position(), 0.8)) {
                pathNodesVisited++;
                pathIndex++;
                stuckTicks = 0;
                lastDistance = Double.POSITIVE_INFINITY;
            }
            if (reached(player)) {
                complete(client);
                return;
            }
            if (pathIndex >= path.size()) {
                replan(client);
                return;
            }

            var step = path.get(pathIndex);
            if (step.kind() != NeoForgeSafePathPlanner.MutationKind.NONE) {
                var handled = switch (step.kind()) {
                    case MINE -> executeMineStep(client, player, step);
                    case PLACE -> executePlaceStep(client, player, step);
                    case NONE -> false;
                };
                if (mutationFailures >= MAX_MUTATION_FAILURES) {
                    completeUnreached(client, "repeated-mutation-failure");
                    return;
                }
                if (handled) return;
            }

            var waypoint = step.position();
            var distance = horizontalDistance(player, waypoint);
            if (distance >= lastDistance - 0.01) stuckTicks++;
            else stuckTicks = 0;
            lastDistance = distance;
            if (stuckTicks > 45) {
                diagnostics.add("navigation-stuck:replanning-at-" + player.blockPosition());
                replan(client);
                return;
            }
            lookAt(player, waypoint);
            setMovement(client, true, false, waypoint.getY() > player.blockPosition().getY() && player.onGround());
            actions.add("move:key.forward-held");
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void replan(Minecraft client) {
        var player = client.player;
        // A stale in-progress mutation attempt from the previous plan must not keep holding
        // attack/use into whatever the new plan turns out to want instead.
        mineTarget = null;
        mineQueue.clear();
        mineTicks = 0;
        placeTarget = null;
        placeTicks = 0;
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        path = NeoForgeSafePathPlanner.findSteps(client.level, player, player.blockPosition(), target, policy);
        pathIndex = path.size() > 1 ? 1 : 0;
        replans++;
        stuckTicks = 0;
        lastDistance = Double.POSITIVE_INFINITY;
        diagnostics.add("replan:" + replans + ":nodes=" + path.size() + ":from="
                + player.blockPosition() + ":to=" + target);
        actions.add(path.isEmpty() ? "observe:no-safe-path" : "observe:loaded-chunk-safe-path");
    }

    /**
     * Executes one MINE step's real vanilla break: holds attack while aimed at the obstruction,
     * exactly like {@code NeoForgeGoalSupervisor.tickObstructionClear}'s own reactive obstruction
     * clearing, but for a planned edge instead of a stall recovery. Re-verifies {@link
     * NeoForgeGoalActionGuard#canBreakObstruction} live (the world can have changed since this edge
     * was costed) rather than trusting the plan blindly - this never loosens that veto, it just
     * re-runs it. Returns {@code true} while this tick's input was spent on mining (caller should
     * not also issue movement input this tick), {@code false} once every target in the step is
     * already clear (caller should fall through to ordinary movement the same tick).
     */
    private boolean executeMineStep(Minecraft client, LocalPlayer player, NeoForgeSafePathPlanner.PathStep step) {
        if (mineTarget == null && mineQueue.isEmpty()) {
            for (var candidate : step.mutationTargets()) {
                var state = client.level.getBlockState(candidate);
                if (!state.isAir() && state.getFluidState().isEmpty()) mineQueue.add(candidate.immutable());
            }
            mineTarget = mineQueue.poll();
            mineTicks = 0;
        }
        if (mineTarget == null) return false;

        var state = client.level.getBlockState(mineTarget);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            blocksMined++;
            diagnostics.add("mutation:mine-cleared:" + mineTarget);
            mineTarget = mineQueue.poll();
            mineTicks = 0;
            if (mineTarget == null) {
                client.options.keyAttack.setDown(false);
                return false;
            }
            state = client.level.getBlockState(mineTarget);
        }
        if (!NeoForgeGoalActionGuard.canBreakObstruction(client.level, player, mineTarget, policy)) {
            client.options.keyAttack.setDown(false);
            diagnostics.add("mutation:mine-veto:" + mineTarget);
            abandonMutation("mine-veto:" + mineTarget);
            return true;
        }
        var progressPerTick = state.getDestroyProgress(player, client.level, mineTarget);
        if (progressPerTick <= 0.0) {
            client.options.keyAttack.setDown(false);
            diagnostics.add("mutation:mine-unbreakable:" + mineTarget);
            abandonMutation("mine-unbreakable:" + mineTarget);
            return true;
        }
        if (++mineTicks > mineTimeoutTicks(progressPerTick)) {
            client.options.keyAttack.setDown(false);
            diagnostics.add("mutation:mine-timeout:" + mineTarget);
            abandonMutation("mine-timeout:" + mineTarget);
            return true;
        }

        setMovement(client, false, false, false);
        lookAt(player, mineTarget);
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(mineTarget)) {
            if (mineTicks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
            actions.add("mutation:mine-" + mineTarget);
        } else {
            client.options.keyAttack.setDown(false);
        }
        return true;
    }

    /**
     * Executes one PLACE step's real vanilla placement: selects the same inventory-owned full-cube
     * item {@link NeoForgePathCostExtensions#selectPlacementItem} already priced, aims at the
     * sturdy block below the gap (the only real click target able to produce a block at the gap
     * itself), and holds use. If the chosen item is not currently in the hotbar, this deliberately
     * gives up rather than driving an inventory screen to move it there - v1 scope cut, same spirit
     * as {@link NeoForgeSafePathPlanner.MutationKind}'s own documented cuts.
     */
    private boolean executePlaceStep(Minecraft client, LocalPlayer player, NeoForgeSafePathPlanner.PathStep step) {
        if (placeTarget == null) {
            if (step.mutationTargets().isEmpty()) return false;
            placeTarget = step.mutationTargets().getFirst();
            placeTicks = 0;
        }
        var state = client.level.getBlockState(placeTarget);
        if (!state.getCollisionShape(client.level, placeTarget).isEmpty()) {
            blocksPlaced++;
            diagnostics.add("mutation:place-complete:" + placeTarget);
            placeTarget = null;
            client.options.keyUse.setDown(false);
            return false;
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        if (!policy.obstructionPlacementEnabled() || !snapshot.safePlacementSite(placeTarget)) {
            client.options.keyUse.setDown(false);
            diagnostics.add("mutation:place-veto:" + placeTarget);
            abandonMutation("place-veto:" + placeTarget);
            return true;
        }
        var chosen = NeoForgePathCostExtensions.selectPlacementItem(player.getInventory());
        if (chosen == null) {
            client.options.keyUse.setDown(false);
            diagnostics.add("mutation:place-no-item:" + placeTarget);
            abandonMutation("place-no-item:" + placeTarget);
            return true;
        }
        var slot = hotbarSlotOf(player, chosen.item());
        if (slot < 0) {
            client.options.keyUse.setDown(false);
            diagnostics.add("mutation:place-item-not-in-hotbar:" + placeTarget);
            abandonMutation("place-item-not-in-hotbar:" + placeTarget);
            return true;
        }
        if (++placeTicks > PLACE_TIMEOUT_TICKS) {
            client.options.keyUse.setDown(false);
            diagnostics.add("mutation:place-timeout:" + placeTarget);
            abandonMutation("place-timeout:" + placeTarget);
            return true;
        }
        if (player.getInventory().selected != slot) {
            KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
            actions.add("select:hotbar-" + (slot + 1));
            return true;
        }

        setMovement(client, false, false, false);
        var clickSupport = placeTarget.below();
        var supportPoint = new Vec3(clickSupport.getX() + 0.5, clickSupport.getY() + 1.0, clickSupport.getZ() + 0.5);
        lookAt(player, supportPoint);
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(clickSupport)) {
            if (placeTicks % 10 == 1) KeyMapping.click(client.options.keyUse.getKey());
            actions.add("mutation:place-" + placeTarget);
        }
        return true;
    }

    /** Real vanilla break-time-derived timeout: generous margin over the priced estimate, never a
     * fixed guess independent of the block actually being mined. Package-private and pure for
     * direct testing. */
    static int mineTimeoutTicks(double progressPerTick) {
        if (progressPerTick <= 0.0) return 0;
        return (int) Math.ceil(1.0 / progressPerTick) * 3 + 40;
    }

    private static int hotbarSlotOf(LocalPlayer player, Item item) {
        var inventory = player.getInventory();
        for (var slot = 0; slot < 9; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) return slot;
        }
        return -1;
    }

    private void abandonMutation(String reason) {
        mineTarget = null;
        mineQueue.clear();
        mineTicks = 0;
        placeTarget = null;
        placeTicks = 0;
        mutationFailures++;
        actions.add("safety:abandon-mutation");
        diagnostics.add("mutation:abandon:" + reason);
        // Force a fresh top-of-tick replan rather than re-attempting the same failed edge.
        path = List.of();
        pathIndex = 0;
    }

    private boolean reached(LocalPlayer player) {
        return horizontalDistance(player, target) <= 2.7
                && Math.abs(player.blockPosition().getY() - target.getY()) <= 2;
    }

    private static boolean closeTo(LocalPlayer player, BlockPos position, double radius) {
        return horizontalDistance(player, position) <= radius
                && Math.abs(player.blockPosition().getY() - position.getY()) <= 1;
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        if (!client.player.isAlive() || client.player.getHealth() <= 0.0F) {
            throw new IllegalStateException("player died before navigation terminal readback");
        }
        result.complete(Map.ofEntries(
                Map.entry("reachedTarget", true),
                Map.entry("target", position(target)),
                Map.entry("finalPosition", position(client.player.blockPosition())),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("observation", policy.observation()),
                Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("replans", replans),
                Map.entry("plannedPathLength", path.size()),
                Map.entry("pathNodesVisited", pathNodesVisited),
                Map.entry("directFallback", directFallback),
                Map.entry("safetyInterventions", List.copyOf(diagnostics)),
                Map.entry("inputActions", List.copyOf(actions)),
                Map.entry("playerAlive", client.player.isAlive()),
                Map.entry("commandsUsed", false),
                Map.entry("directMutationUsed", false),
                Map.entry("blocksMined", blocksMined),
                Map.entry("blocksPlaced", blocksPlaced)));
    }

    /**
     * Terminal, non-throwing failure completion for "no route exists" (either the very first plan
     * came back empty, or repeated mutation abandonment left nothing left to try). Reports the same
     * fields {@link #complete} does, plus a bounded best-effort diagnosis - the nearest position a
     * real flood-fill from the player's own current position could actually reach, and up to three
     * non-air neighbors of that point as a sample of what's blocking further progress - so a
     * model-orchestrator caller can reason about "mine through, try a different target, or give up"
     * instead of only ever seeing an opaque thrown error string.
     */
    private void completeUnreached(Minecraft client, String reason) {
        releaseInput(client);
        var diagnosis = diagnoseUnreachable(client);
        var output = new LinkedHashMap<String, Object>();
        output.put("reachedTarget", false);
        output.put("target", position(target));
        output.put("finalPosition", position(client.player.blockPosition()));
        output.put("intelligence", policy.intelligence().id());
        output.put("safety", policy.safety().id());
        output.put("policyMode", policy.mode());
        output.put("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled());
        output.put("observation", policy.observation());
        output.put("combatPolicy", policy.combatPolicy());
        output.put("allowCommands", policy.allowCommands());
        output.put("replans", replans);
        output.put("plannedPathLength", path.size());
        output.put("pathNodesVisited", pathNodesVisited);
        output.put("directFallback", directFallback);
        output.put("safetyInterventions", List.copyOf(diagnostics));
        output.put("inputActions", List.copyOf(actions));
        output.put("playerAlive", client.player.isAlive());
        output.put("commandsUsed", false);
        output.put("directMutationUsed", false);
        output.put("blocksMined", blocksMined);
        output.put("blocksPlaced", blocksPlaced);
        output.put("unreachableReason", reason);
        if (diagnosis.nearestReachablePoint() != null) {
            output.put("nearestReachablePoint", position(diagnosis.nearestReachablePoint()));
        }
        if (!diagnosis.obstructionSample().isEmpty()) {
            output.put("obstructionSample", diagnosis.obstructionSample());
        }
        result.complete(Map.copyOf(output));
    }

    private record UnreachableDiagnosis(BlockPos nearestReachablePoint, List<Map<String, Object>> obstructionSample) { }

    private UnreachableDiagnosis diagnoseUnreachable(Minecraft client) {
        var player = client.player;
        var reachability = NeoForgeSafePathPlanner.floodFillReachable(client.level, player,
                player.blockPosition(), policy, 20_000);
        var nearest = nearestReachablePoint(target, reachability.reached());
        if (nearest == null) return new UnreachableDiagnosis(null, List.of());
        var samples = new ArrayList<Map<String, Object>>();
        for (var direction : Direction.values()) {
            if (samples.size() >= 3) break;
            var neighbor = nearest.relative(direction);
            if (!client.level.hasChunkAt(neighbor)) continue;
            var state = client.level.getBlockState(neighbor);
            if (state.isAir()) continue;
            samples.add(Map.of("position", position(neighbor),
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        }
        return new UnreachableDiagnosis(nearest, List.copyOf(samples));
    }

    /** Nearest (by real 3D distance) reachable position to the target, or {@code null} for an empty
     * reachable set - a genuinely encircled origin. Package-private and pure for direct testing. */
    static BlockPos nearestReachablePoint(BlockPos target, List<BlockPos> reached) {
        BlockPos best = null;
        var bestDistanceSq = Double.POSITIVE_INFINITY;
        for (var candidate : reached) {
            var dx = (double) (candidate.getX() - target.getX());
            var dy = (double) (candidate.getY() - target.getY());
            var dz = (double) (candidate.getZ() - target.getZ());
            var distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private static int requiredInt(InvocationContext invocation, String key) {
        var value = invocation.request().input().get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        return number.intValue();
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

    private static void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }
}
