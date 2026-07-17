// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
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

    private NeoForgeSafePathPlanner() { }

    static List<BlockPos> find(ClientLevel level, BlockPos start, BlockPos target,
                               NeoForgeGoalPolicy policy) {
        return find(level, start, target, policy, new ArrivalSpec(3.0, 5.0));
    }

    static List<BlockPos> find(ClientLevel level, BlockPos start, BlockPos target,
                               NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAny(level, start, List.of(target), policy, arrival);
    }

    /**
     * Find one safe route to any member of a target set in a single search. This is important for
     * adaptive resource acquisition: trying every legal mining cell with a separate A* search can
     * monopolize the client thread even when each individual search has a visit bound.
     */
    static List<BlockPos> findToAny(ClientLevel level, BlockPos start, Collection<BlockPos> targets,
                                    NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAny(level, start, targets, policy, arrival, false, MAX_VISITED);
    }

    /**
     * Find a route from a currently walkable but not fully buffered origin. This is used only
     * for recovery into a high-safety work surface; every destination and subsequent edge still
     * has to satisfy the full buffered-walkable contract.
     */
    static List<BlockPos> findFromWalkableOrigin(ClientLevel level, BlockPos start,
                                                  Collection<BlockPos> targets,
                                                  NeoForgeGoalPolicy policy, ArrivalSpec arrival) {
        return findToAny(level, start, targets, policy, arrival, true, MAX_VISITED);
    }

    /**
     * Election-time reachability probe with a reduced visit budget. Candidate screening runs on
     * the client tick, so a run of unreachable candidates must not each burn the full search
     * budget; the committed route is still planned with the full budget afterwards. A probe that
     * exhausts its reduced budget means "prefer another candidate", never "proven unreachable".
     */
    static List<BlockPos> probe(ClientLevel level, BlockPos start, BlockPos target,
                                NeoForgeGoalPolicy policy, int maxVisited) {
        return findToAny(level, start, List.of(target), policy, new ArrivalSpec(3.0, 5.0),
                false, Math.min(maxVisited, MAX_VISITED));
    }

    private static List<BlockPos> findToAny(ClientLevel level, BlockPos start,
                                            Collection<BlockPos> targets,
                                            NeoForgeGoalPolicy policy, ArrivalSpec arrival,
                                            boolean allowWalkableOrigin, int maxVisited) {
        if (targets.isEmpty()) return List.of();
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
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
                for (var dy : new int[]{1, 0, -1, -2, -3}) {
                    var candidate = new BlockPos(horizontal.getX(), current.position().getY() + dy,
                            horizontal.getZ());
                    if (!withinBounds(origin, candidate)
                            || policy.highSafety() && !snapshot.bufferedWalkable(candidate)
                            || !policy.highSafety() && !snapshot.walkable(candidate)) continue;
                    // A client cannot traverse a diagonal height change as one movement edge.
                    // It must first enter the same-height horizontal cell, then step up/down.
                    // Reject the shortcut unless that transit cell is safe; reconstruction below
                    // inserts it into the returned route so the input driver can actually follow
                    // the edge instead of holding forward against a ledge forever.
                    if (dy != 0 && (policy.highSafety()
                            ? !snapshot.bufferedWalkable(horizontal)
                            : !snapshot.walkable(horizontal))) continue;
                    // A normal player can safely step down one block; larger drops are
                    // fall damage, not navigation. Adaptive intelligence keeps this rule
                    // even with balanced safety so planning quality cannot trade health for
                    // a shorter route.
                    if (current.position().getY() - candidate.getY() > 1) continue;
                    var nextCost = cost.get(current.position().asLong()) + edgeCost(current.position(), candidate, policy);
                    if (nextCost >= cost.getOrDefault(candidate.asLong(), Double.POSITIVE_INFINITY)) continue;
                    cost.put(candidate.asLong(), nextCost);
                    previous.put(candidate.asLong(), current.position().asLong());
                    queue.add(new Node(candidate.immutable(), nextCost,
                            nextCost + heuristic(candidate, targets)));
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
        if (path.size() < 2) return List.copyOf(path);
        var expanded = new ArrayList<BlockPos>(path.size() + 4);
        expanded.add(path.getFirst());
        for (int index = 1; index < path.size(); index++) {
            var from = path.get(index - 1);
            var to = path.get(index);
            if (from.getY() != to.getY()
                    && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                var transit = new BlockPos(to.getX(), from.getY(), to.getZ()).immutable();
                if (!expanded.getLast().equals(transit)) expanded.add(transit);
            }
            if (!expanded.getLast().equals(to)) expanded.add(to);
        }
        return List.copyOf(expanded);
    }

    /**
     * Find normal-input vertical progress when the requested target is materially below an
     * otherwise unreachable origin. Every edge uses the same walkability and one-block descent
     * rules as ordinary A*, so this cannot turn route recovery into an unsafe fall shortcut.
     */
    static List<BlockPos> findSafeDescent(ClientLevel level, BlockPos start, BlockPos target,
                                           NeoForgeGoalPolicy policy, java.util.Set<Long> rejected) {
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
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
                    var nextCost = current.cost() + edgeCost(position, candidate, policy);
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
                    && current.cost() + edgeCost(position, directBelow, policy)
                    < cost.getOrDefault(directBelow.asLong(), Double.POSITIVE_INFINITY)) {
                cost.put(directBelow.asLong(), current.cost() + edgeCost(position, directBelow, policy));
                previous.put(directBelow.asLong(), position.asLong());
                queue.add(new Node(directBelow.immutable(), current.cost() + edgeCost(position, directBelow, policy),
                        current.cost() + edgeCost(position, directBelow, policy)));
            }
        }
        if (best == null) return List.of();
        return reconstruct(previous, best);
    }

    /**
     * Emergency graph from the real submerged pose to dry buffered ground. It never descends,
     * crosses non-water fluid, invents an origin, or selects a route longer than the air reserve.
     */
    static List<BlockPos> findWaterRetreat(ClientLevel level, BlockPos start,
                                            NeoForgeGoalPolicy policy, int airSupply) {
        var maxEdges = NeoForgeSurvivalInvariant.maxWaterRetreatEdges(airSupply);
        if (maxEdges <= 0) return List.of();
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
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
    static List<BlockPos> findHazardRetreat(ClientLevel level, BlockPos start,
                                             NeoForgeGoalPolicy policy) {
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
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

    private static double edgeCost(BlockPos from, BlockPos to, NeoForgeGoalPolicy policy) {
        var vertical = Math.abs(to.getY() - from.getY());
        var cost = 1.0 + vertical * (policy.highSafety() ? 4.0 : 1.5);
        if (policy.safety() == NeoForgeGoalPolicy.Safety.BALANCED && to.getY() < from.getY()) cost += 1.0;
        if (policy.intelligence() == NeoForgeGoalPolicy.Intelligence.ADAPTIVE_V1
                && to.getY() < from.getY()) cost += 12.0;
        return cost;
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
