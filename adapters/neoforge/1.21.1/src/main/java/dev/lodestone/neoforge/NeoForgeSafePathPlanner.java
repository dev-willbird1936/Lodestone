// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
        var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
        var origin = start.immutable();
        if (!snapshot.walkable(origin)) {
            origin = findNearbyWalkable(snapshot, start, 3);
            if (origin == null) return List.of();
        }

        var queue = new PriorityQueue<Node>();
        var previous = new HashMap<Long, Long>();
        var cost = new HashMap<Long, Double>();
        queue.add(new Node(origin, 0.0, heuristic(origin, target)));
        previous.put(origin.asLong(), Long.MIN_VALUE);
        cost.put(origin.asLong(), 0.0);
        BlockPos reached = null;
        var visited = 0;

        while (!queue.isEmpty() && visited++ < MAX_VISITED) {
            var current = queue.remove();
            if (nearTarget(current.position(), target)) {
                reached = current.position();
                break;
            }
            for (var direction : Direction.Plane.HORIZONTAL) {
                var horizontal = current.position().relative(direction);
                for (var dy : new int[]{1, 0, -1, -2, -3}) {
                    var candidate = new BlockPos(horizontal.getX(), current.position().getY() + dy,
                            horizontal.getZ());
                    if (!withinBounds(origin, candidate) || !snapshot.walkable(candidate)) continue;
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
                            nextCost + heuristic(candidate, target)));
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

    private static BlockPos findNearbyWalkable(NeoForgeWorldSnapshot snapshot, BlockPos start, int radius) {
        for (var dy = 2; dy >= -radius; dy--) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    var candidate = start.offset(dx, dy, dz);
                    if (snapshot.walkable(candidate)) return candidate.immutable();
                }
            }
        }
        return null;
    }

    private static boolean nearTarget(BlockPos current, BlockPos target) {
        var dx = current.getX() + 0.5 - target.getX() - 0.5;
        var dz = current.getZ() + 0.5 - target.getZ() - 0.5;
        return dx * dx + dz * dz <= 9.0 && Math.abs(current.getY() - target.getY()) <= 5;
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

    private record Node(BlockPos position, double cost, double priority) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) { return Double.compare(priority, other.priority); }
    }
}
