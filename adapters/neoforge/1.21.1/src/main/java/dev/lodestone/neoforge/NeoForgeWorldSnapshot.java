// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only local world projection used by planning and safety decisions. */
final class NeoForgeWorldSnapshot {
    // These bounds keep the once-per-search mob/lava precompute cheap and independent of the
    // search radius; the cost formulas that will consume them (a later pass) only look a few
    // blocks further than this from any given edge.
    private static final double THREAT_SCAN_RADIUS = 12.0;
    private static final int LAVA_SCAN_HORIZONTAL_RADIUS = 8;
    private static final int LAVA_SCAN_VERTICAL_RADIUS = 5;

    private final ClientLevel level;
    private final NeoForgeGoalPolicy policy;
    private final int featherFallingLevel;
    private final boolean slowFalling;
    private final List<ThreatFact> nearbyThreats;
    private final List<BlockPos> nearbyLava;

    private NeoForgeWorldSnapshot(ClientLevel level, NeoForgeGoalPolicy policy, LocalPlayer player) {
        this.level = level;
        this.policy = policy;
        this.featherFallingLevel = computeFeatherFallingLevel(player);
        this.slowFalling = player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION);
        this.nearbyThreats = computeNearbyThreats(level, player);
        this.nearbyLava = computeNearbyLava(level, player.blockPosition());
    }

    static NeoForgeWorldSnapshot capture(ClientLevel level, NeoForgeGoalPolicy policy, LocalPlayer player) {
        return new NeoForgeWorldSnapshot(level, policy, player);
    }

    /** Feather Falling level on any currently equipped item (boots in vanilla), 0 if none. */
    int featherFallingLevel() {
        return featherFallingLevel;
    }

    /** True while Slow Falling or Levitation is active; both reset fall distance every tick. */
    boolean slowFalling() {
        return slowFalling;
    }

    /** Bounded, once-per-search snapshot of nearby hostile/targeting mobs. */
    List<ThreatFact> nearbyThreats() {
        return nearbyThreats;
    }

    /** Bounded, once-per-search snapshot of nearby lava-fluid positions. */
    List<BlockPos> nearbyLava() {
        return nearbyLava;
    }

    private static int computeFeatherFallingLevel(LocalPlayer player) {
        var featherFalling = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FEATHER_FALLING);
        return EnchantmentHelper.getEnchantmentLevel(featherFalling, player);
    }

    private static List<ThreatFact> computeNearbyThreats(ClientLevel level, LocalPlayer player) {
        return level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(THREAT_SCAN_RADIUS),
                        mob -> NeoForgeGoalObservation.isThreat(mob, player))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr))
                .limit(NeoForgeGoalObservation.MAX_THREATS)
                .map(mob -> new ThreatFact(mob.blockPosition().immutable(),
                        mob.getAttributeValue(Attributes.FOLLOW_RANGE), mob.getTarget() == player))
                .toList();
    }

    private static List<BlockPos> computeNearbyLava(ClientLevel level, BlockPos origin) {
        var result = new ArrayList<BlockPos>();
        for (var x = origin.getX() - LAVA_SCAN_HORIZONTAL_RADIUS; x <= origin.getX() + LAVA_SCAN_HORIZONTAL_RADIUS; x++) {
            for (var z = origin.getZ() - LAVA_SCAN_HORIZONTAL_RADIUS; z <= origin.getZ() + LAVA_SCAN_HORIZONTAL_RADIUS; z++) {
                if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) continue;
                for (var y = origin.getY() - LAVA_SCAN_VERTICAL_RADIUS; y <= origin.getY() + LAVA_SCAN_VERTICAL_RADIUS; y++) {
                    var probe = new BlockPos(x, y, z);
                    if (level.getFluidState(probe).is(FluidTags.LAVA)) result.add(probe.immutable());
                }
            }
        }
        return List.copyOf(result);
    }

    /** One precomputed nearby-mob fact: position, real follow range, and whether it targets the player. */
    record ThreatFact(BlockPos position, double followRange, boolean targetingPlayer) { }

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
