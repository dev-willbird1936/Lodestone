// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

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

/** Weighted A* over locally readable block collision data. */
final class NeoForgeSafePathPlanner {
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

    private NeoForgeSafePathPlanner() { }

    /** Whether reaching a path step requires mining or placing a block first. */
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
    record EdgeContext(ClientLevel level, NeoForgeWorldSnapshot snapshot, LocalPlayer player,
                        BlockPos from, BlockPos to, MutationKind mutationKind,
                        List<BlockPos> mutationTargets, NeoForgeGoalPolicy policy) { }

    static List<BlockPos> find(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                               NeoForgeGoalPolicy policy) {
        return find(level, player, start, target, policy, new ArrivalSpec(3.0, 5.0));
    }

    static List<BlockPos> find(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                               NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return toPositions(findToAnySteps(level, player, start, List.of(target), policy, arrival));
    }

    static List<PathStep> findSteps(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                                    NeoForgeGoalPolicy policy) {
        return findSteps(level, player, start, target, policy, new ArrivalSpec(3.0, 5.0));
    }

    static List<PathStep> findSteps(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                                    NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAnySteps(level, player, start, List.of(target), policy, arrival);
    }

    /**
     * Find one safe route to any member of a target set in a single search. This is important for
     * adaptive resource acquisition: trying every legal mining cell with a separate A* search can
     * monopolize the client thread even when each individual search has a visit bound.
     */
    static List<BlockPos> findToAny(ClientLevel level, LocalPlayer player, BlockPos start,
                                    Collection<BlockPos> targets,
                                    NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return toPositions(findToAnySteps(level, player, start, targets, policy, arrival));
    }

    static List<PathStep> findToAnySteps(ClientLevel level, LocalPlayer player, BlockPos start,
                                         Collection<BlockPos> targets,
                                         NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAny(level, player, start, targets, policy, arrival, false, MAX_VISITED);
    }

    /**
     * Find a route from a currently walkable but not fully buffered origin. This is used only
     * for recovery into a high-safety work surface; every destination and subsequent edge still
     * has to satisfy the full buffered-walkable contract.
     */
    static List<BlockPos> findFromWalkableOrigin(ClientLevel level, LocalPlayer player, BlockPos start,
                                                  Collection<BlockPos> targets,
                                                  NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return toPositions(findFromWalkableOriginSteps(level, player, start, targets, policy, arrival));
    }

    static List<PathStep> findFromWalkableOriginSteps(ClientLevel level, LocalPlayer player, BlockPos start,
                                                      Collection<BlockPos> targets,
                                                      NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAny(level, player, start, targets, policy, arrival, true, MAX_VISITED);
    }

    /**
     * Election-time reachability probe with a reduced visit budget. Candidate screening runs on
     * the client tick, so a run of unreachable candidates must not each burn the full search
     * budget; the committed route is still planned with the full budget afterwards. A probe that
     * exhausts its reduced budget means "prefer another candidate", never "proven unreachable".
     */
    static List<BlockPos> probe(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                                NeoForgeGoalPolicy policy, int maxVisited) {
        return toPositions(findToAny(level, player, start, List.of(target), policy, new ArrivalSpec(3.0, 5.0),
                false, Math.min(maxVisited, MAX_VISITED)));
    }

    private static List<BlockPos> toPositions(List<PathStep> steps) {
        return steps.stream().map(PathStep::position).toList();
    }

    private static List<PathStep> findToAny(ClientLevel level, LocalPlayer player, BlockPos start,
                                            Collection<BlockPos> targets,
                                            NeoForgeGoalPolicy policy, ArrivalSpec arrival,
                                            boolean allowWalkableOrigin, int maxVisited) {
        if (targets.isEmpty()) return List.of();
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy, player);
        var origin = start.immutable();
        var originAllowed = NeoForgeSurvivalInvariant.normalRouteOriginAllowed(snapshot.walkable(origin),
                snapshot.bufferedWalkable(origin), policy.highSafety());
        if (!originAllowed && allowWalkableOrigin) {
            originAllowed = snapshot.walkable(origin)
                    && !snapshot.hazard(origin) && !snapshot.hazard(origin.above());
        }
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

        while (!queue.isEmpty() && visited++ < maxVisited) {
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
                                       NeoForgeGoalPolicy policy, NeoForgeWorldSnapshot snapshot, LocalPlayer player,
                                       Collection<BlockPos> targets, HashMap<Long, Double> cost,
                                       HashMap<Long, Long> previous, HashMap<Long, MutationKind> incomingKind,
                                       HashMap<Long, List<BlockPos>> incomingTargets, PriorityQueue<Node> queue) {
        var candidate = new BlockPos(horizontal.getX(), current.getY() + dy, horizontal.getZ());
        if (!withinBounds(origin, candidate)
                || policy.highSafety() && !snapshot.bufferedWalkable(candidate)
                || !policy.highSafety() && !snapshot.walkable(candidate)) return;
        // A client cannot traverse a diagonal height change as one movement edge. It must first
        // enter the same-height horizontal cell, then step up/down. Reject the shortcut unless
        // that transit cell is safe; reconstruction below inserts it into the returned route so
        // the input driver can actually follow the edge instead of holding forward against a
        // ledge forever.
        if (dy != 0 && (policy.highSafety()
                ? !snapshot.bufferedWalkable(horizontal)
                : !snapshot.walkable(horizontal))) return;
        // A normal player can safely step down one block; larger drops are fall damage, not
        // navigation. Adaptive intelligence keeps this rule even with balanced safety so
        // planning quality cannot trade health for a shorter route.
        if (current.getY() - candidate.getY() > 1) return;
        var nextCost = cost.get(current.asLong()) + edgeCost(current, candidate, policy, snapshot, player);
        if (nextCost >= cost.getOrDefault(candidate.asLong(), Double.POSITIVE_INFINITY)) return;
        cost.put(candidate.asLong(), nextCost);
        previous.put(candidate.asLong(), current.asLong());
        // Plain movement only for now; mine/place candidate edges are a later, separate addition
        // once the real cost formulas that make them meaningful exist (NeoForgePathCostExtensions).
        incomingKind.put(candidate.asLong(), MutationKind.NONE);
        incomingTargets.put(candidate.asLong(), List.of());
        queue.add(new Node(candidate.immutable(), nextCost, nextCost + heuristic(candidate, targets)));
    }

    /**
     * A diagonal move is only legal when the player couldn't be clipping through a solid corner
     * that doesn't exist in the real collision model: at least one flanking cardinal cell must be
     * passable, or both under high safety.
     */
    private static boolean cornerClear(BlockPos flankA, BlockPos flankB, NeoForgeGoalPolicy policy,
                                        NeoForgeWorldSnapshot snapshot) {
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
     * Find normal-input vertical progress when the requested target is materially below an
     * otherwise unreachable origin. Every edge uses the same walkability and one-block descent
     * rules as ordinary A*, so this cannot turn route recovery into an unsafe fall shortcut.
     */
    static List<BlockPos> findSafeDescent(ClientLevel level, LocalPlayer player, BlockPos start, BlockPos target,
                                           NeoForgeGoalPolicy policy, java.util.Set<Long> rejected) {
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy, player);
        var origin = start.immutable();
        if (!NeoForgeSurvivalInvariant.verticalRecoveryOriginAllowed(snapshot.walkable(origin),
                snapshot.hazard(origin), snapshot.hazard(origin.above()))) return List.of();

        var queue = new PriorityQueue<Node>();
        var previous = new HashMap<Long, Long>();
        var cost = new HashMap<Long, Double>();
        queue.add(new Node(origin, 0.0, 0.0));
        previous.put(origin.asLong(), Long.MIN_VALUE);
        cost.put(origin.asLong(), 0.0);
        BlockPos best = null;
        var bestTargetHeightDelta = Integer.MAX_VALUE;
        var bestHorizontalDelta = Integer.MAX_VALUE;
        var bestCost = Double.POSITIVE_INFINITY;
        var visited = 0;

        while (!queue.isEmpty() && visited++ < MAX_VISITED) {
            var current = queue.remove();
            var position = current.position();
            if (position.getY() < origin.getY() && !rejected.contains(position.asLong())) {
                var targetHeightDelta = Math.abs(position.getY() - target.getY());
                var horizontalDelta = Math.abs(position.getX() - target.getX())
                        + Math.abs(position.getZ() - target.getZ());
                if (targetHeightDelta < bestTargetHeightDelta
                        || targetHeightDelta == bestTargetHeightDelta && horizontalDelta < bestHorizontalDelta
                        || targetHeightDelta == bestTargetHeightDelta && horizontalDelta == bestHorizontalDelta
                        && current.cost() < bestCost) {
                    best = position;
                    bestTargetHeightDelta = targetHeightDelta;
                    bestHorizontalDelta = horizontalDelta;
                    bestCost = current.cost();
                }
                // A target-level cell within ordinary interaction range is sufficient vertical
                // progress; stop before exploring the rest of the loaded 24-block volume.
                if (targetHeightDelta <= 1 && horizontalDelta <= 4) break;
            }
            for (var direction : Direction.Plane.HORIZONTAL) {
                var horizontal = position.relative(direction);
                for (var dy : new int[]{1, 0, -1, -2, -3}) {
                    var candidate = new BlockPos(horizontal.getX(), position.getY() + dy, horizontal.getZ());
                    if (Math.abs(candidate.getX() - origin.getX()) > 24
                            || Math.abs(candidate.getZ() - origin.getZ()) > 24
                            || Math.abs(candidate.getY() - origin.getY()) > 24
                            || !snapshot.bufferedWalkable(candidate)
                            || position.getY() - candidate.getY() > 1) continue;
                    var nextCost = current.cost() + edgeCost(position, candidate, policy, snapshot, player);
                    if (nextCost >= cost.getOrDefault(candidate.asLong(), Double.POSITIVE_INFINITY)) continue;
                    cost.put(candidate.asLong(), nextCost);
                    previous.put(candidate.asLong(), position.asLong());
                    queue.add(new Node(candidate.immutable(), nextCost, nextCost));
                }
            }
            var directBelow = position.below();
            if (snapshot.bufferedWalkable(directBelow)
                    && directBelow.getY() < origin.getY()
                    && !rejected.contains(directBelow.asLong())
                    && current.cost() + edgeCost(position, directBelow, policy, snapshot, player)
                    < cost.getOrDefault(directBelow.asLong(), Double.POSITIVE_INFINITY)) {
                cost.put(directBelow.asLong(), current.cost() + edgeCost(position, directBelow, policy, snapshot, player));
                previous.put(directBelow.asLong(), position.asLong());
                queue.add(new Node(directBelow.immutable(), current.cost() + edgeCost(position, directBelow, policy, snapshot, player),
                        current.cost() + edgeCost(position, directBelow, policy, snapshot, player)));
            }
        }
        if (best == null) return List.of();
        return reconstruct(previous, best);
    }

    /**
     * Emergency graph from the real submerged pose to dry buffered ground. It never descends,
     * crosses non-water fluid, invents an origin, or selects a route longer than the air reserve.
     */
    static List<BlockPos> findWaterRetreat(ClientLevel level, LocalPlayer player, BlockPos start,
                                            NeoForgeGoalPolicy policy, int airSupply) {
        var maxEdges = NeoForgeSurvivalInvariant.maxWaterRetreatEdges(airSupply);
        if (maxEdges <= 0) return List.of();
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy, player);
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
                                             NeoForgeGoalPolicy policy) {
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy, player);
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
                                                NeoForgeWorldSnapshot snapshot) {
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

    static double edgeCost(BlockPos from, BlockPos to, NeoForgeGoalPolicy policy) {
        var diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
        var vertical = Math.abs(to.getY() - from.getY());
        var cost = (diagonal ? Math.sqrt(2.0) : 1.0) + vertical * (policy.highSafety() ? 4.0 : 1.5);
        if (policy.safety() == NeoForgeGoalPolicy.Safety.BALANCED && to.getY() < from.getY()) cost += 1.0;
        if (policy.intelligence() == NeoForgeGoalPolicy.Intelligence.ADAPTIVE_V1
                && to.getY() < from.getY()) cost += 12.0;
        return cost;
    }

    /**
     * Real fall-damage cost layered on top of {@link #edgeCost(BlockPos, BlockPos, NeoForgeGoalPolicy)}'s
     * existing terms (which stay unchanged - they weight general descent preference, not damage risk).
     * Every edge here is capped at a 1-block drop by the existing descent-cap check, under which
     * real fall damage is always zero; this is deliberately still real and tested rather than a
     * stub, so it is already correct if that cap is ever relaxed for a future recovery route.
     */
    private static double fallDamageCost(BlockPos from, BlockPos to, NeoForgeGoalPolicy policy,
                                          NeoForgeWorldSnapshot snapshot) {
        var appliesAtThisTier = policy.safety() == NeoForgeGoalPolicy.Safety.BALANCED || policy.highSafety()
                || policy.safety() == NeoForgeGoalPolicy.Safety.LOW
                        && policy.intelligence() == NeoForgeGoalPolicy.Intelligence.ADAPTIVE_V1;
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

    /** Convenience overload for the common case: a plain move, no mutation involved. */
    private static double edgeCost(BlockPos from, BlockPos to, NeoForgeGoalPolicy policy,
                                    NeoForgeWorldSnapshot snapshot, LocalPlayer player) {
        return edgeCost(from, to, policy, snapshot, player, MutationKind.NONE, List.of());
    }

    /**
     * Full edge cost: pure geometry/tier cost, plus real fall-damage cost, plus whatever
     * {@link NeoForgePathCostExtensions} adds for mob/lava proximity or a mine/place mutation.
     * Filling in those extension formulas never needs another change here.
     */
    private static double edgeCost(BlockPos from, BlockPos to, NeoForgeGoalPolicy policy,
                                    NeoForgeWorldSnapshot snapshot, LocalPlayer player,
                                    MutationKind kind, List<BlockPos> mutationTargets) {
        var ctx = new EdgeContext(snapshot.level(), snapshot, player, from, to, kind, mutationTargets, policy);
        return edgeCost(from, to, policy) + fallDamageCost(from, to, policy, snapshot)
                + NeoForgePathCostExtensions.additionalCost(ctx);
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
}
