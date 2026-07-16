// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Read-only local world projection used by planning and safety decisions. */
final class NeoForgeWorldSnapshot {
    private final ClientLevel level;
    private final NeoForgeGoalPolicy policy;

    private NeoForgeWorldSnapshot(ClientLevel level, NeoForgeGoalPolicy policy) {
        this.level = level;
        this.policy = policy;
    }

    static NeoForgeWorldSnapshot capture(ClientLevel level, NeoForgeGoalPolicy policy) {
        return new NeoForgeWorldSnapshot(level, policy);
    }

    boolean walkable(BlockPos feet) {
        if (!level.hasChunkAt(feet)) return false;
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(feet.above());
        var supportPos = feet.below();
        var supportState = level.getBlockState(supportPos);
        if (!feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (supportState.getCollisionShape(level, supportPos).isEmpty()) return false;
        if (policy.highSafety()) {
            return !hazard(feet) && !hazard(feet.above()) && !hazard(supportPos)
                    && level.getFluidState(feet).isEmpty();
        }
        return true;
    }

    boolean hazard(BlockPos position) {
        if (!level.hasChunkAt(position)) return true;
        var state = level.getBlockState(position);
        return !level.getFluidState(position).isEmpty()
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW);
    }

    BlockPos nearestSafeSurface(BlockPos origin, int radius) {
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (var x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (var z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                for (var y = origin.getY() - radius; y <= origin.getY() + radius; y++) {
                    var candidate = new BlockPos(x, y, z);
                    if (!walkable(candidate)) continue;
                    var distance = candidate.distSqr(origin);
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }
}
