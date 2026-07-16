// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;

import java.util.Collection;
import java.util.Comparator;

/** Deterministic tick-level preemption layer shared by survival actors. */
final class NeoForgeGoalSupervisor {
    private final NeoForgeGoalPolicy policy;
    private final Collection<String> actions;
    private final Collection<String> diagnostics;
    private int recoveryTicks;
    private int brakeTicks;

    NeoForgeGoalSupervisor(NeoForgeGoalPolicy policy, Collection<String> actions,
                           Collection<String> diagnostics) {
        this.policy = policy;
        this.actions = actions;
        this.diagnostics = diagnostics;
    }

    boolean tick(Minecraft client) {
        if (!policy.supervisorEnabled() || client.level == null || client.player == null
                || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || client.screen != null) return false;
        var player = client.player;

        if (brakeTicks > 0) {
            client.options.keyShift.setDown(true);
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            brakeTicks--;
            actions.add("safety:brake-fall");
            if (brakeTicks == 0) client.options.keyShift.setDown(false);
            return true;
        }

        if (player.fallDistance > 2.0F && !player.onGround() && policy.highSafety()) {
            brakeTicks = 5;
            diagnostics.add("fall-risk:braking-inputs");
            return true;
        }

        if (player.isInLava() || player.isOnFire() || player.isInWater()) {
            var target = NeoForgeWorldSnapshot.capture(client.level, policy)
                    .nearestSafeSurface(player.blockPosition(), policy.highSafety() ? 12 : 8);
            if (target != null) {
                lookAt(player, target);
                setRecoveryMovement(client, true);
                actions.add(player.isInWater() ? "safety:escape-water" : "safety:escape-fire-or-lava");
                diagnostics.add("hazard-recovery:" + target);
            } else {
                setRecoveryMovement(client, true);
                actions.add("safety:emergency-jump");
                diagnostics.add("hazard-recovery:no-safe-surface-observed");
            }
            recoveryTicks = 10;
            return true;
        }

        var threat = findThreat(player);
        if (threat != null && (!policy.combatPolicy().equals("none") || policy.highSafety())) {
            if (recoveryTicks > 0) recoveryTicks--;
            if (policy.combatPolicy().equals("avoid") || policy.combatPolicy().equals("none")) {
                retreat(client, player, threat);
            }
            else defendOrRetreat(client, player, threat);
            return true;
        }
        if (recoveryTicks > 0) {
            recoveryTicks--;
            setRecoveryMovement(client, false);
            return true;
        }
        return false;
    }

    private LivingEntity findThreat(LocalPlayer player) {
        var last = player.getLastHurtByMob();
        if (last != null && last.isAlive() && player.distanceToSqr(last) <= 144.0) return last;
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12.0),
                        mob -> mob.isAlive() && mob.getTarget() == player)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private void defendOrRetreat(Minecraft client, LocalPlayer player, LivingEntity threat) {
        var close = player.distanceToSqr(threat) <= 12.25;
        var weak = player.getHealth() <= 4.0F;
        if (close && !weak && !player.getMainHandItem().isEmpty()) {
            lookAt(player, threat.position());
            client.options.keyAttack.setDown(true);
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(!policy.highSafety() && player.onGround());
            actions.add("safety:defend-active-threat");
            diagnostics.add("threat-response:attack:" + threat.getType());
            recoveryTicks = 8;
            return;
        }
        var dx = player.getX() - threat.getX();
        var dz = player.getZ() - threat.getZ();
        var length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        var retreat = new BlockPos((int) Math.floor(player.getX() + dx / length * 5),
                player.blockPosition().getY(), (int) Math.floor(player.getZ() + dz / length * 5));
        lookAt(player, retreat);
        client.options.keyAttack.setDown(false);
        setRecoveryMovement(client, !policy.highSafety());
        actions.add("safety:retreat-from-active-threat");
        diagnostics.add("threat-response:retreat:" + threat.getType());
        recoveryTicks = 10;
    }

    private void retreat(Minecraft client, LocalPlayer player, LivingEntity threat) {
        var dx = player.getX() - threat.getX();
        var dz = player.getZ() - threat.getZ();
        var length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        lookAt(player, new BlockPos((int) Math.floor(player.getX() + dx / length * 5),
                player.blockPosition().getY(), (int) Math.floor(player.getZ() + dz / length * 5)));
        client.options.keyAttack.setDown(false);
        setRecoveryMovement(client, !policy.highSafety());
        actions.add("safety:avoid-active-threat");
        diagnostics.add("threat-response:avoid:" + threat.getType());
        recoveryTicks = 10;
    }

    private static void setRecoveryMovement(Minecraft client, boolean jump) {
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(false);
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        lookAt(player, new net.minecraft.world.phys.Vec3(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static void lookAt(LocalPlayer player, net.minecraft.world.phys.Vec3 target) {
        var eye = player.getEyePosition();
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setYHeadRot(player.getYRot());
        player.setXRot((float) Math.max(-89.0, Math.min(89.0,
                -Math.toDegrees(Math.atan2(dy, horizontal)))));
    }
}
