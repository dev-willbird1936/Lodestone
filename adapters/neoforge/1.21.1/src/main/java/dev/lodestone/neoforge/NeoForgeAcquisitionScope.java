// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Persistent identity for a bounded acquisition area across goal-stage recovery. */
final class NeoForgeAcquisitionScope {
    static final int HYSTERESIS_BLOCKS = 8;
    static final int MAX_NO_FRONTIER_ATTEMPTS = 4;

    private NeoForgeAcquisitionScope() {
    }

    record Scope(String dimension, BlockPos anchor, int radius, String resourceKind,
                 Set<Long> attemptedTargets) {
        Scope {
            if (dimension == null || anchor == null || resourceKind == null || radius < 0) {
                throw new IllegalArgumentException("invalid acquisition scope");
            }
            anchor = anchor.immutable();
            attemptedTargets = Set.copyOf(attemptedTargets == null ? Set.of() : attemptedTargets);
        }

        boolean blocksCandidate(String candidateDimension, BlockPos candidateAnchor, String candidateKind) {
            return dimension.equals(candidateDimension) && resourceKind.equals(candidateKind)
                    && horizontalDistanceSquared(anchor, candidateAnchor)
                    <= (long) (radius + HYSTERESIS_BLOCKS) * (radius + HYSTERESIS_BLOCKS);
        }

        boolean measuredExpansionBeyond(String currentDimension, BlockPos current,
                                        String currentKind) {
            return dimension.equals(currentDimension) && resourceKind.equals(currentKind)
                    && horizontalDistanceSquared(anchor, current)
                    > (long) (radius + HYSTERESIS_BLOCKS) * (radius + HYSTERESIS_BLOCKS);
        }
    }

    static boolean boundedNoFrontier(int attempts) {
        return attempts >= MAX_NO_FRONTIER_ATTEMPTS;
    }

    static Scope mergeExhausted(String dimension, BlockPos sourceAnchor, int radius, String resourceKind,
                                Set<Long> attemptedTargets, Collection<BlockPos> sourceBlocks, Scope previous) {
        var mergedTargets = new HashSet<Long>(attemptedTargets == null ? Set.of() : attemptedTargets);
        if (sourceBlocks != null) sourceBlocks.forEach(block -> mergedTargets.add(block.asLong()));
        if (previous != null && previous.blocksCandidate(dimension, sourceAnchor, resourceKind)) {
            mergedTargets.addAll(previous.attemptedTargets());
            return new Scope(previous.dimension(), previous.anchor(), previous.radius(),
                    previous.resourceKind(), mergedTargets);
        }
        return new Scope(dimension, sourceAnchor, radius, resourceKind, mergedTargets);
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
