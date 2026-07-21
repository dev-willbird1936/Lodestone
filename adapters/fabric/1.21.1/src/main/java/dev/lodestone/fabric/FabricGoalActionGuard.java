// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Deterministic action vetoes for normal-input goal actors.
 *
 * <p>Ported from {@code NeoForgeGoalActionGuard} for the {@code minecraft.goal.navigation.safe-waypoint}
 * capability. Only {@code canBreakObstruction} is ported: it is the only method of the NeoForge
 * original that the navigation goal's transitive dependencies ({@code FabricGoalSupervisor} and
 * {@code FabricPathCostExtensions}) actually call. {@code toolRequiredAttackTarget} and
 * {@code unsafeForwardDrop} exist on the NeoForge side only to support the combat/other goal
 * actors that are out of scope for this port.
 */
final class FabricGoalActionGuard {
    private FabricGoalActionGuard() { }

    static boolean canBreakObstruction(ClientLevel level, LocalPlayer player, BlockPos position,
                                       FabricGoalPolicy policy) {
        if (!policy.allowBlockBreaking()) return false;
        var state = level.getBlockState(position);
        if (state.isAir() || state.getDestroySpeed(level, position) < 0) return false;
        if (position.equals(player.blockPosition()) || position.equals(player.blockPosition().below())) return false;
        if (policy.highSafety()) {
            var snapshot = FabricWorldSnapshot.capture(level, policy, player);
            if (snapshot.hazard(position) || snapshot.hazard(position.above())
                    || snapshot.hazard(position.below())) return false;
        }
        return true;
    }
}
