// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups observed equivalent starter-resource blocks without assuming a natural tree shape. */
final class NeoForgeStarterResourceCatalog {
    enum Provenance {
        NATURAL_TRUNK,
        STRUCTURAL_LOGS
    }

    record Source(BlockPos anchor, List<BlockPos> blocks, Provenance provenance, double distanceSquared) {
    }

    private NeoForgeStarterResourceCatalog() {
    }

    static List<Source> group(Collection<BlockPos> observed, BlockPos origin, Set<BlockPos> rejectedAnchors) {
        var unseen = new HashMap<Long, BlockPos>();
        for (var block : observed) unseen.put(block.asLong(), block.immutable());
        var sources = new ArrayList<Source>();
        while (!unseen.isEmpty()) {
            var seed = unseen.values().iterator().next();
            var queue = new ArrayDeque<BlockPos>();
            var component = new ArrayList<BlockPos>();
            queue.add(seed);
            unseen.remove(seed.asLong());
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                component.add(current);
                for (var neighbor : connectedNeighbors(current)) {
                    var observedNeighbor = unseen.remove(neighbor.asLong());
                    if (observedNeighbor != null) queue.addLast(observedNeighbor);
                }
            }
            component.sort(Comparator.comparingInt((BlockPos position) -> position.getY())
                    .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            // Stable identity: unlike a nearest-to-player anchor, this does not change as the actor moves.
            var anchor = component.getFirst();
            if (rejectedAnchors.contains(anchor)) continue;
            var provenance = trunkShaped(component) ? Provenance.NATURAL_TRUNK : Provenance.STRUCTURAL_LOGS;
            var distanceSquared = component.stream().mapToDouble(origin::distSqr).min().orElseThrow();
            sources.add(new Source(anchor.immutable(), List.copyOf(component), provenance,
                    distanceSquared));
        }
        sources.sort(Comparator.comparingDouble(Source::distanceSquared)
                .thenComparing(source -> source.provenance() == Provenance.NATURAL_TRUNK ? 0 : 1));
        return List.copyOf(sources);
    }

    private static Collection<BlockPos> connectedNeighbors(BlockPos position) {
        var neighbors = new HashSet<BlockPos>();
        for (var dx = -1; dx <= 1; dx++) {
            for (var dy = -1; dy <= 1; dy++) {
                for (var dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    neighbors.add(position.offset(dx, dy, dz));
                }
            }
        }
        return neighbors;
    }

    private static boolean trunkShaped(List<BlockPos> blocks) {
        if (blocks.size() < 3 || blocks.size() > 16) return false;
        var minX = blocks.stream().mapToInt(BlockPos::getX).min().orElse(0);
        var maxX = blocks.stream().mapToInt(BlockPos::getX).max().orElse(0);
        var minY = blocks.stream().mapToInt(BlockPos::getY).min().orElse(0);
        var maxY = blocks.stream().mapToInt(BlockPos::getY).max().orElse(0);
        var minZ = blocks.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        var maxZ = blocks.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        return maxX - minX <= 1 && maxZ - minZ <= 1 && maxY - minY <= 12;
    }
}
