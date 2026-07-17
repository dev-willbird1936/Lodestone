// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import java.util.List;

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
        var head = feet.above();
        var supportPos = feet.below();
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(supportPos)) return false;
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(head);
        var supportState = level.getBlockState(supportPos);
        if (!feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (supportState.getCollisionShape(level, supportPos).isEmpty()) return false;
        // A recovery/path feet block must be dry even for balanced policy. Water and lava are
        // valid observations and bucket targets, but never valid surfaces to walk or recover to.
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (policy.highSafety()) {
            return !hazard(feet) && !hazard(feet.above()) && !hazard(supportPos)
                    && level.getFluidState(supportPos).isEmpty();
        }
        return true;
    }

    boolean hazard(BlockPos position) {
        if (!level.hasChunkAt(position)) return true;
        var state = level.getBlockState(position);
        return !level.getFluidState(position).isEmpty()
                || NeoForgeContactHazards.isDamageBlock(state.getBlock());
    }

    /** High-safety work surface with a full 3D fluid/fire/hazard buffer around the body. */
    boolean bufferedWalkable(BlockPos feet) {
        if (!walkable(feet)) return false;
        for (var x = feet.getX() - 1; x <= feet.getX() + 1; x++) {
            for (var z = feet.getZ() - 1; z <= feet.getZ() + 1; z++) {
                for (var y = feet.getY() - 1; y <= feet.getY() + 2; y++) {
                    var probe = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(probe) || hazard(probe)) return false;
                }
            }
        }
        return true;
    }

    /** Collision-free water/ascent cell used only by the bounded emergency retreat graph. */
    boolean waterRetreatPassable(BlockPos feet) {
        var head = feet.above();
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(head)) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
        if (!waterOrEmpty(feet) || !waterOrEmpty(head)
                || nonWaterHazard(feet) || nonWaterHazard(head)) return false;
        // Once fully out of water, every graph node must regain ordinary solid support.
        // This prevents the emergency search from treating unsupported air above water as land.
        var containsWater = level.getFluidState(feet).is(FluidTags.WATER)
                || level.getFluidState(head).is(FluidTags.WATER);
        return containsWater || walkable(feet);
    }

    private boolean waterOrEmpty(BlockPos position) {
        var fluid = level.getFluidState(position);
        return fluid.isEmpty() || fluid.is(FluidTags.WATER);
    }

    private boolean nonWaterHazard(BlockPos position) {
        var state = level.getBlockState(position);
        var fluid = level.getFluidState(position);
        return !fluid.isEmpty() && !fluid.is(FluidTags.WATER)
                || NeoForgeContactHazards.isDamageBlock(state.getBlock());
    }

    /** Breaking this block cannot immediately reveal or contact an observed fluid/fire hazard. */
    boolean breakExposureSafe(BlockPos target) {
        for (var x = target.getX() - 1; x <= target.getX() + 1; x++) {
            for (var z = target.getZ() - 1; z <= target.getZ() + 1; z++) {
                for (var y = target.getY() - 1; y <= target.getY() + 1; y++) {
                    var probe = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(probe) || hazard(probe)) return false;
                }
            }
        }
        return true;
    }

    /** Conservative voxel volume around a mining ray; stricter than fluid-ignored picking. */
    boolean hazardFreeRay(Vec3 start, Vec3 end) {
        var minX = (int) Math.floor(Math.min(start.x, end.x)) - 1;
        var maxX = (int) Math.floor(Math.max(start.x, end.x)) + 1;
        var minY = (int) Math.floor(Math.min(start.y, end.y)) - 1;
        var maxY = (int) Math.floor(Math.max(start.y, end.y)) + 1;
        var minZ = (int) Math.floor(Math.min(start.z, end.z)) - 1;
        var maxZ = (int) Math.floor(Math.max(start.z, end.z)) + 1;
        for (var x = minX; x <= maxX; x++) {
            for (var z = minZ; z <= maxZ; z++) {
                for (var y = minY; y <= maxY; y++) {
                    var probe = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(probe) || hazard(probe)) return false;
                }
            }
        }
        return true;
    }

    boolean safeMiningSite(BlockPos feet, BlockPos target, Vec3 eye, Vec3 aim) {
        return bufferedWalkable(feet) && !target.equals(feet.below())
                && breakExposureSafe(target) && hazardFreeRay(eye, aim);
    }

    /** Placement target and support must remain inside the same conservative hazard buffer. */
    boolean safePlacementSite(BlockPos target) {
        if (!level.hasChunkAt(target) || !level.hasChunkAt(target.below())) return false;
        if (hazard(target) || hazard(target.below())) return false;
        for (var x = target.getX() - 1; x <= target.getX() + 1; x++) {
            for (var z = target.getZ() - 1; z <= target.getZ() + 1; z++) {
                for (var y = target.getY() - 1; y <= target.getY() + 2; y++) {
                    if (hazard(new BlockPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    boolean safeMiningPath(List<BlockPos> path) {
        return !path.isEmpty() && path.stream().allMatch(this::bufferedWalkable);
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
