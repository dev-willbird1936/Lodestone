// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;

/** Deterministic action vetoes for normal-input goal actors. */
final class NeoForgeGoalActionGuard {
    private NeoForgeGoalActionGuard() { }

    static boolean canBreakObstruction(ClientLevel level, LocalPlayer player, BlockPos position,
                                       NeoForgeGoalPolicy policy) {
        if (!policy.allowBlockBreaking()) return false;
        var state = level.getBlockState(position);
        if (state.isAir() || state.getDestroySpeed(level, position) < 0) return false;
        if (position.equals(player.blockPosition()) || position.equals(player.blockPosition().below())) return false;
        if (policy.highSafety()) {
            var snapshot = NeoForgeWorldSnapshot.capture(level, policy, player);
            if (snapshot.hazard(position) || snapshot.hazard(position.above())
                    || snapshot.hazard(position.below())) return false;
        }
        return true;
    }

    /** Returns a diagnostic when an intelligent attack would punch a hard block without its tool. */
    static String toolRequiredAttackTarget(ClientLevel level, LocalPlayer player) {
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) return null;
        var state = level.getBlockState(blockHit.getBlockPos());
        if (state.isAir() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || player.getMainHandItem().isCorrectToolForDrops(state)) return null;
        return state.getBlock().getName().getString();
    }

    static boolean unsafeForwardDrop(Minecraft client, LocalPlayer player) {
        var feet = player.blockPosition();
        var ahead = feet.relative(player.getDirection());
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, NeoForgeGoalPolicy.from(Map.of(
                "intelligence", "guarded-v1", "safety", "high")), player);
        if (snapshot.walkable(ahead)) return false;
        for (var dy = -1; dy >= -4; dy--) {
            var candidate = new BlockPos(ahead.getX(), feet.getY() + dy, ahead.getZ());
            if (snapshot.walkable(candidate)) return dy <= -2;
        }
        return true;
    }
}
