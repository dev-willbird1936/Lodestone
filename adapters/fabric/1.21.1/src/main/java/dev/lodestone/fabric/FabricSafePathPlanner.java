// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Weighted A* over locally readable block collision data.
 *
 * <p>Ported from {@code NeoForgeSafePathPlanner} for the {@code minecraft.goal.navigation.safe-waypoint}
 * capability; the algorithm, edge costs, and safety rules are bit-for-bit identical to the
 * NeoForge 1.21.1 original. Trimmed of the entry points and helpers that only ever served other,
 * out-of-scope goal actors on the NeoForge side ({@code findSteps}/{@code findToAnySteps}'s public
 * multi-target-with-mutation-info wrappers, {@code findFromWalkableOrigin}, {@code probe},
 * {@code floodFillReachable}, and {@code findSafeDescent}); {@code FabricNavigationGoal} and
 * {@code FabricGoalSupervisor} only ever call {@link #find}, {@link #findWaterRetreat}, and
 * {@link #findHazardRetreat}, so this file keeps exactly the shared engine those three need.
 */
final class FabricSafePathPlanner {
    private static final int MAX_VISITED = 45_000;
    private static final int MAX_HORIZONTAL_RADIUS = 96;
    private static final int MAX_VERTICAL_RADIUS = 32;
    private static final int[] DY_OFFSETS = {1, 0, -1, -2, -3};
    // Tunable preference weight, not a derived game-mechanics value: how many extra walked-block
    // equivalents one point of real estimated fall damage is worth avoiding.
    private static final double FALL_DAMAGE_COST_PER_HP = 4.0;
    private static final Direction[][] DIAGONALS = {
            {Direction.NORTH, Direction.EAST}, {Direction.NORTH, Direction.WEST},
            {Direction.SOUTH, Direction.EAST}, {Direction.SOUTH, Direction.WEST},
    };

    private FabricSafePathPlanner() { }

    /**
     * Whether reaching a path step requires mining or placing a block first. A cell that would
     * need both a mine and a place is out of v1 scope; if ever needed, model it as two consecutive
     * {@link PathStep}s at the same position rather than adding a 4th composite kind.
     */
    enum MutationKind { NONE, MINE, PLACE }

    /**
     * One step of a planned route. {@code mutationTargets} is a list rather than a single position
     * because clearing a destination for a 2-tall hitbox can require breaking both the feet and
     * head cells; it is empty whenever {@code kind} is {@link MutationKind#NONE}.
     */
    record PathStep(BlockPos position, MutationKind kind, List<BlockPos> mutationTargets) {
        static PathStep move(BlockPos position) {
            return new PathStep(position, MutationKind.NONE, List.of());
        }
    }

    /** Everything a path-edge cost extension needs, without depending on A*'s own internal state. */
    record EdgeContext(ClientLevel level, FabricWorldSnapshot snapshot, LocalPlayer player,
                        BlockPos from, BlockPos to, MutationKind mutationKind,
                        List<BlockPos> mutationTargets, FabricGoalPolicy policy) { }

    static List<BlockPos> find(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                               FabricGoalPolicy policy) {
        return find(level, player, start, target, policy, new ArrivalSpec(3.0, 5.0));
    }

    static List<BlockPos> find(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                               FabricGoalPolicy policy, ArrivalSpec arrival) {
        return toPositions(findToAny(level, player, start, List.of(target), policy, arrival));
    }

    private static List<PathStep> findToAny(ClientLevel level, LocalPlayer player, BlockPos start,
                                            Collection<BlockPos> targets,
                                            FabricGoalPolicy policy, ArrivalSpec arrival) {
        if (targets.isEmpty()) return List.of();
        var snapshot = FabricWorldSnapshot.capture(level, policy, player);
        var origin = start.immutable();
        var originAllowed = FabricSurvivalInvariant.normalRouteOriginAllowed(snapshot.walkable(origin),
                snapshot.bufferedWalkable(origin), policy.highSafety());
        if (!originAllowed) return List.of();
        var goalKeys = new HashSet<Long>();
        for (var target : targets) goalKeys.add(target.asLong());
        var singleTarget = targets.size() == 1 ? targets.iterator().next() : null;

        var queue = new PriorityQueue<Node>();
        var previous = new HashMap<Long, Long>();
        var cost = new HashMap<Long, Double>();
        var incomingKind = new HashMap<Long, MutationKind>();
        var incomingTargets = new HashMap<Long, List<BlockPos>>();
        queue.add(new Node(origin, 0.0, heuristic(origin, targets)));
        previous.put(origin.asLong(), Long.MIN_VALUE);
        cost.put(origin.asLong(), 0.0);
        BlockPos reached = null;
        var visited = 0;

        while (!queue.isEmpty() && visited++ < MAX_VISITED) {
            var current = queue.remove();
            if (singleTarget != null
                    ? arrival.reached(current.position(), singleTarget)
                    : goalKeys.contains(current.position().asLong())) {
                reached = current.position();
                break;
            }
            for (var direction : Direction.Plane.HORIZONTAL) {
                var horizontal = current.position().relative(direction);
                for (var dy : DY_OFFSETS) {
                    relaxNeighbor(current.position(), horizontal, dy, origin, policy, snapshot, player,
                            targets, cost, previous, incomingKind, incomingTargets, queue);
                }
            }
            // Medium+ intelligence also considers the 4 diagonal directions (a client can move
            // along both axes in one input tick). A diagonal is only safe when at least one
            // flanking cardinal cell is passable (both, under high safety) - otherwise the move
            // would clip through a solid corner that doesn't exist in the real collision model.
            // Diagonal-plus-climb needs no separate handling: relaxNeighbor's existing transit,
            // descent-cap, and destination checks are already direction-agnostic.
            if (policy.safeNavigationPlanningEnabled()) {
                for (var pair : DIAGONALS) {
                    var flankA = current.position().relative(pair[0]);
                    var flankB = current.position().relative(pair[1]);
                    if (!cornerClear(flankA, flankB, policy, snapshot)) continue;
                    var diagonal = flankA.relative(pair[1]);
                    for (var dy : DY_OFFSETS) {
                        relaxNeighbor(current.position(), diagonal, dy, origin, policy, snapshot, player,
                                targets, cost, previous, incomingKind, incomingTargets, queue);
                    }
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
        if (path.size() < 2) return List.of(PathStep.move(path.getFirst()));
        var expanded = new ArrayList<PathStep>(path.size() + 4);
        expanded.add(PathStep.move(path.getFirst()));
        for (int index = 1; index < path.size(); index++) {
            var from = path.get(index - 1);
            var to = path.get(index);
            if (from.getY() != to.getY()
                    && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                var transit = new BlockPos(to.getX(), from.getY(), to.getZ()).immutable();
                if (!expanded.getLast().position().equals(transit)) expanded.add(PathStep.move(transit));
            }
            if (!expanded.getLast().position().equals(to)) {
                expanded.add(new PathStep(to, incomingKind.getOrDefault(to.asLong(), MutationKind.NONE),
                        incomingTargets.getOrDefault(to.asLong(), List.of())));
            }
        }
        return List.copyOf(expanded);
    }

    /** Shared A* relax step for both the cardinal and diagonal neighbor loops in {@link #findToAny}. */
    private static void relaxNeighbor(BlockPos current, BlockPos horizontal, int dy, BlockPos origin,
                                       FabricGoalPolicy policy, FabricWorldSnapshot snapshot, LocalPlayer player,
                                       Collection<BlockPos> targets, HashMap<Long, Double> cost,
                                       HashMap<Long, Long> previous, HashMap<Long, MutationKind> incomingKind,
                                       HashMap<Long, List<BlockPos>> incomingTargets, PriorityQueue<Node> queue) {
        var candidate = new BlockPos(horizontal.getX(), current.getY() + dy, horizontal.getZ());
        if (!withinBounds(origin, candidate)) return;
        // A client cannot traverse a diagonal height change as one movement edge. It must first
        // enter the same-height horizontal cell, then step up/down. Reject the shortcut unless
        // that transit cell is safe; reconstruction below inserts it into the returned route so
        // the input driver can actually follow the edge instead of holding forward against a
        // ledge forever.
        if (dy != 0 && (policy.highSafety()
                ? !snapshot.bufferedWalkable(horizontal)
                : !snapshot.walkable(horizontal))) return;
        // A normal player can safely step down one block by default; larger drops are fall damage,
        // not navigation. Policy-driven (FabricGoalPolicy.maxDescentBlocks(), default 1) rather
        // than hardcoded, but every actor that builds its policy via FabricGoalPolicy.from()
        // directly still gets exactly 1 - this stays the same rule, unaffected, for all of them.
        // DY_OFFSETS already enumerates candidates down to a 3-block drop, so a policy backing this
        // off up to 3 costs nothing extra here; it never manufactures a deeper candidate on its own.
        if (!descentAllowed(current.getY() - candidate.getY(), policy.maxDescentBlocks())) return;

        if (policy.highSafety() ? snapshot.bufferedWalkable(candidate) : snapshot.walkable(candidate)) {
            offerEdge(current, candidate, MutationKind.NONE, List.of(), policy, snapshot, player, targets,
                    cost, previous, incomingKind, incomingTargets, queue);
            return;
        }
        // Only adaptive intelligence considers reaching an otherwise-blocked cell by mining an
        // obstruction or placing a support block; FabricPathCostExtensions prices (and can
        // outright veto via POSITIVE_INFINITY) both, so proposing the edge here never bypasses a
        // legality gate - it only ever gives A* the option, purely additive over the plain check
        // above. RAW_V1/GUARDED_V1 never reach this branch, so they stay provably unaffected.
        if (policy.intelligence() != FabricGoalPolicy.Intelligence.ADAPTIVE_V1) return;
        var mutation = mutationCandidate(snapshot, candidate);
        if (mutation == null) return;
        offerEdge(current, candidate, mutation.kind(), mutation.targets(), policy, snapshot, player, targets,
                cost, previous, incomingKind, incomingTargets, queue);
    }

    /** Inserts (or skips, if not an improvement) one proposed edge - a plain move or a mutation alike. */
    private static void offerEdge(BlockPos current, BlockPos candidate, MutationKind kind,
                                   List<BlockPos> mutationTargets, FabricGoalPolicy policy,
                                   FabricWorldSnapshot snapshot, LocalPlayer player, Collection<BlockPos> targets,
                                   HashMap<Long, Double> cost, HashMap<Long, Long> previous,
                                   HashMap<Long, MutationKind> incomingKind,
                                   HashMap<Long, List<BlockPos>> incomingTargets, PriorityQueue<Node> queue) {
        var nextCost = cost.get(current.asLong())
                + edgeCost(current, candidate, policy, snapshot, player, kind, mutationTargets);
        if (nextCost >= cost.getOrDefault(candidate.asLong(), Double.POSITIVE_INFINITY)) return;
        cost.put(candidate.asLong(), nextCost);
        previous.put(candidate.asLong(), current.asLong());
        incomingKind.put(candidate.asLong(), kind);
        incomingTargets.put(candidate.asLong(), mutationTargets);
        queue.add(new Node(candidate.immutable(), nextCost, nextCost + heuristic(candidate, targets)));
    }

    /**
     * Why a cell that fails the plain walkability check could still become a MINE or PLACE edge:
     * either a single solid, breakable obstruction sits in the feet and/or head cell (mine
     * whichever of the two are actually solid), or the support block below is missing entirely (a
     * floor gap, fillable by placing). A cell can never need both in this v1 scope - see
     * {@link MutationKind}'s doc.
     */
    private record MutationCandidate(MutationKind kind, List<BlockPos> targets) { }

    private static MutationCandidate mutationCandidate(FabricWorldSnapshot snapshot, BlockPos candidate) {
        var level = snapshot.level();
        var head = candidate.above();
        var support = candidate.below();
        if (!level.hasChunkAt(candidate) || !level.hasChunkAt(head) || !level.hasChunkAt(support)) return null;
        var feetSolid = !level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty();
        var headSolid = !level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        var supportSolid = !level.getBlockState(support).getCollisionShape(level, support).isEmpty();
        var feetFluidEmpty = level.getFluidState(candidate).isEmpty();
        var headFluidEmpty = level.getFluidState(head).isEmpty();
        if (mineCandidateEligible(feetSolid, headSolid, supportSolid, feetFluidEmpty, headFluidEmpty)) {
            var mutationTargets = new ArrayList<BlockPos>(2);
            if (feetSolid) mutationTargets.add(candidate);
            if (headSolid) mutationTargets.add(head);
            return new MutationCandidate(MutationKind.MINE, List.copyOf(mutationTargets));
        }
        if (placeCandidateEligible(feetSolid, headSolid, supportSolid, feetFluidEmpty, headFluidEmpty)) {
            return new MutationCandidate(MutationKind.PLACE, List.of(support));
        }
        return null;
    }

    /** Pure obstruction-shape decision, isolated from world access so it's directly unit-testable. */
    static boolean mineCandidateEligible(boolean feetSolid, boolean headSolid, boolean supportSolid,
                                          boolean feetFluidEmpty, boolean headFluidEmpty) {
        return supportSolid && feetFluidEmpty && headFluidEmpty && (feetSolid || headSolid);
    }

    /** Pure floor-gap decision, isolated from world access so it's directly unit-testable. */
    static boolean placeCandidateEligible(boolean feetSolid, boolean headSolid, boolean supportSolid,
                                           boolean feetFluidEmpty, boolean headFluidEmpty) {
        return !feetSolid && !headSolid && feetFluidEmpty && headFluidEmpty && !supportSolid;
    }

    /**
     * A diagonal move is only legal when the player couldn't be clipping through a solid corner
     * that doesn't exist in the real collision model: at least one flanking cardinal cell must be
     * passable, or both under high safety.
     */
    private static boolean cornerClear(BlockPos flankA, BlockPos flankB, FabricGoalPolicy policy,
                                        FabricWorldSnapshot snapshot) {
        var highSafety = policy.highSafety();
        var flankAPasses = highSafety ? snapshot.bufferedWalkable(flankA) : snapshot.walkable(flankA);
        var flankBPasses = highSafety ? snapshot.bufferedWalkable(flankB) : snapshot.walkable(flankB);
        return cornerClear(flankAPasses, flankBPasses, highSafety);
    }

    /** Pure corner-cut decision, isolated from world access so it's directly unit-testable. */
    static boolean cornerClear(boolean flankAPasses, boolean flankBPasses, boolean requireBoth) {
        return requireBoth ? flankAPasses && flankBPasses : flankAPasses || flankBPasses;
    }

    /**
     * Pure descent-cap decision, isolated from world access so it's directly unit-testable.
     * {@code dropBlocks} is {@code current.getY() - candidate.getY()}: zero or negative (level or
     * ascending) is always allowed regardless of the cap, which only ever restricts how far a
     * single edge may descend.
     */
    static boolean descentAllowed(int dropBlocks, int maxDescentBlocks) {
        return dropBlocks <= maxDescentBlocks;
    }

    static double edgeCost(BlockPos from, BlockPos to, FabricGoalPolicy policy) {
        var diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
        var vertical = Math.abs(to.getY() - from.getY());
        var cost = (diagonal ? Math.sqrt(2.0) : 1.0) + vertical * (policy.highSafety() ? 4.0 : 1.5);
        if (policy.safety() == FabricGoalPolicy.Safety.BALANCED && to.getY() < from.getY()) cost += 1.0;
        if (policy.intelligence() == FabricGoalPolicy.Intelligence.ADAPTIVE_V1
                && to.getY() < from.getY()) cost += 12.0;
        return cost;
    }

    /**
     * Real fall-damage cost layered on top of {@link #edgeCost(BlockPos, BlockPos, FabricGoalPolicy)}'s
     * existing terms (which stay unchanged - they weight general descent preference, not damage risk).
     * Every edge here is bounded by {@link #descentAllowed}'s policy-driven cap, still exactly
     * 1 block by default (real fall damage always zero there); this is deliberately still real and
     * tested rather than a stub.
     */
    private static double fallDamageCost(BlockPos from, BlockPos to, FabricGoalPolicy policy,
                                          FabricWorldSnapshot snapshot) {
        var appliesAtThisTier = policy.safety() == FabricGoalPolicy.Safety.BALANCED || policy.highSafety()
                || policy.safety() == FabricGoalPolicy.Safety.LOW
                        && policy.intelligence() == FabricGoalPolicy.Intelligence.ADAPTIVE_V1;
        if (!appliesAtThisTier) return 0.0;
        var dyBlocks = from.getY() - to.getY();
        if (dyBlocks <= 0) return 0.0;
        var damage = estimatedFallDamage(dyBlocks, snapshot.safeFallDistance(), snapshot.fallDamageMultiplier(),
                snapshot.featherFallingLevel(), snapshot.slowFalling());
        return damage * FALL_DAMAGE_COST_PER_HP;
    }

    /**
     * Real vanilla fall-damage formula: {@code max(0, ceil((fallBlocks - safeFallDistance) *
     * fallDamageMultiplier))}, reduced by Feather Falling's real linear protection-value curve
     * (3.0/level) converted via the standard 4%-per-point-capped-at-20 approximation (the exact
     * percentage aggregator is server-only and unreachable from a client-side mod). Slow Falling
     * and Levitation reset fall distance every tick in vanilla, so they always fully negate this.
     */
    static double estimatedFallDamage(int dyBlocks, double safeFallDistance, double fallDamageMultiplier,
                                       int featherFallingLevel, boolean slowFalling) {
        if (slowFalling || dyBlocks <= 0) return 0.0;
        var raw = Math.max(0.0, Math.ceil((dyBlocks - safeFallDistance) * fallDamageMultiplier));
        if (featherFallingLevel <= 0 || raw <= 0.0) return raw;
        var protectionValue = Math.min(3.0 * featherFallingLevel, 20.0);
        return raw * (1.0 - protectionValue * 0.04);
    }

    /**
     * Full edge cost: pure geometry/tier cost, plus real fall-damage cost, plus whatever
     * {@link FabricPathCostExtensions} adds for mob/lava proximity or a mine/place mutation.
     */
    private static double edgeCost(BlockPos from, BlockPos to, FabricGoalPolicy policy,
                                    FabricWorldSnapshot snapshot, LocalPlayer player,
                                    MutationKind kind, List<BlockPos> mutationTargets) {
        var ctx = new EdgeContext(snapshot.level(), snapshot, player, from, to, kind, mutationTargets, policy);
        return edgeCost(from, to, policy) + fallDamageCost(from, to, policy, snapshot)
                + FabricPathCostExtensions.additionalCost(ctx);
    }

    private static double heuristic(BlockPos from, BlockPos target) {
        return Math.abs(from.getX() - target.getX()) + Math.abs(from.getZ() - target.getZ())
                + Math.abs(from.getY() - target.getY()) * 1.5;
    }

    private static double heuristic(BlockPos from, Collection<BlockPos> targets) {
        var best = Double.POSITIVE_INFINITY;
        for (var target : targets) best = Math.min(best, heuristic(from, target));
        return best;
    }

    private record Node(BlockPos position, double cost, double priority) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) { return Double.compare(priority, other.priority); }
    }

    record ArrivalSpec(double horizontalTolerance, double verticalTolerance) {
        boolean reached(BlockPos current, BlockPos target) {
            return reached(current.getX() + 0.5, current.getY(), current.getZ() + 0.5, target);
        }

        boolean reached(double x, double y, double z, BlockPos target) {
            var dx = target.getX() + 0.5 - x;
            var dz = target.getZ() + 0.5 - z;
            var dy = target.getY() + 0.1 - y;
            return dx * dx + dz * dz <= horizontalTolerance * horizontalTolerance
                    && Math.abs(dy) <= verticalTolerance;
        }
    }

    private static List<BlockPos> toPositions(List<PathStep> steps) {
        return steps.stream().map(PathStep::position).toList();
    }

    /**
     * Emergency graph from the real submerged pose to dry buffered ground. It never descends,
     * crosses non-water fluid, invents an origin, or selects a route longer than the air reserve.
     */
    static List<BlockPos> findWaterRetreat(ClientLevel level, LocalPlayer player, BlockPos start,
                                            FabricGoalPolicy policy, int airSupply) {
        var maxEdges = FabricSurvivalInvariant.maxWaterRetreatEdges(airSupply);
        if (maxEdges <= 0) return List.of();
        var snapshot = FabricWorldSnapshot.capture(level, policy, player);
        var origin = start.immutable();
        if (!snapshot.waterRetreatPassable(origin)) return List.of();

        var queue = new ArrayDeque<BlockPos>();
        var previous = new HashMap<Long, Long>();
        var depth = new HashMap<Long, Integer>();
        queue.add(origin);
        previous.put(origin.asLong(), Long.MIN_VALUE);
        depth.put(origin.asLong(), 0);

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            var currentDepth = depth.get(current.asLong());
            if (currentDepth > 0 && snapshot.bufferedWalkable(current)) {
                return reconstruct(previous, current);
            }
            if (currentDepth >= maxEdges) continue;
            var candidates = new ArrayList<BlockPos>(5);
            candidates.add(current.above());
            for (var direction : Direction.Plane.HORIZONTAL) candidates.add(current.relative(direction));
            for (var candidate : candidates) {
                if (candidate.getY() < origin.getY()
                        || Math.abs(candidate.getX() - origin.getX()) > 12
                        || Math.abs(candidate.getZ() - origin.getZ()) > 12
                        || candidate.getY() - origin.getY() > 12
                        || previous.containsKey(candidate.asLong())
                        || !snapshot.waterRetreatPassable(candidate)) continue;
                previous.put(candidate.asLong(), current.asLong());
                depth.put(candidate.asLong(), currentDepth + 1);
                queue.addLast(candidate.immutable());
            }
        }
        return List.of();
    }

    /**
     * Escape graph for a player already touching lava or fire. Normal A* rejects a hazardous
     * origin by design, so this deliberately permits only the current cell and a small connected
     * band of empty/lava cells until a fully buffered dry surface is reached.
     */
    static List<BlockPos> findHazardRetreat(ClientLevel level, LocalPlayer player, BlockPos start,
                                             FabricGoalPolicy policy) {
        var snapshot = FabricWorldSnapshot.capture(level, policy, player);
        var origin = start.immutable();
        var queue = new ArrayDeque<BlockPos>();
        var previous = new HashMap<Long, Long>();
        var depth = new HashMap<Long, Integer>();
        queue.add(origin);
        previous.put(origin.asLong(), Long.MIN_VALUE);
        depth.put(origin.asLong(), 0);

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            var currentDepth = depth.get(current.asLong());
            if (currentDepth > 0 && snapshot.bufferedWalkable(current)) {
                return reconstruct(previous, current);
            }
            if (currentDepth >= 24) continue;
            var candidates = new ArrayList<BlockPos>(5);
            candidates.add(current.above());
            for (var direction : Direction.Plane.HORIZONTAL) candidates.add(current.relative(direction));
            for (var candidate : candidates) {
                if (previous.containsKey(candidate.asLong())
                        || Math.abs(candidate.getX() - origin.getX()) > 12
                        || Math.abs(candidate.getZ() - origin.getZ()) > 12
                        || Math.abs(candidate.getY() - origin.getY()) > 8
                        || !hazardEscapePassable(level, candidate, snapshot)) continue;
                previous.put(candidate.asLong(), current.asLong());
                depth.put(candidate.asLong(), currentDepth + 1);
                queue.addLast(candidate.immutable());
            }
        }
        return List.of();
    }

    private static boolean hazardEscapePassable(ClientLevel level, BlockPos candidate,
                                                FabricWorldSnapshot snapshot) {
        if (snapshot.bufferedWalkable(candidate)) return true;
        var head = candidate.above();
        if (!level.hasChunkAt(candidate) || !level.hasChunkAt(head)) return false;
        if (!level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
        var feetFluid = level.getFluidState(candidate);
        var supportFluid = level.getFluidState(candidate.below());
        return feetFluid.is(FluidTags.LAVA) || supportFluid.is(FluidTags.LAVA);
    }

    private static List<BlockPos> reconstruct(HashMap<Long, Long> previous, BlockPos reached) {
        var path = new ArrayList<BlockPos>();
        var cursor = reached.asLong();
        while (cursor != Long.MIN_VALUE) {
            path.add(BlockPos.of(cursor).immutable());
            cursor = previous.getOrDefault(cursor, Long.MIN_VALUE);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private static boolean withinBounds(BlockPos origin, BlockPos candidate) {
        return Math.abs(candidate.getX() - origin.getX()) <= MAX_HORIZONTAL_RADIUS
                && Math.abs(candidate.getZ() - origin.getZ()) <= MAX_HORIZONTAL_RADIUS
                && Math.abs(candidate.getY() - origin.getY()) <= MAX_VERTICAL_RADIUS;
    }
}
