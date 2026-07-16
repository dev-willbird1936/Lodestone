// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

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
            var snapshot = NeoForgeWorldSnapshot.capture(level, policy);
            if (snapshot.hazard(position) || snapshot.hazard(position.above())
                    || snapshot.hazard(position.below())) return false;
        }
        return true;
    }
}
