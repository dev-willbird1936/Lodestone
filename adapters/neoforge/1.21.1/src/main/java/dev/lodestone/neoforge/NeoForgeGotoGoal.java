// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backs {@code minecraft.goal.move.goto}: a bounded, agent-facing point-to-point movement
 * competency. Thin wrapper around the shared {@link NeoForgeGotoMovement} engine - see that class
 * for the actual pathfinding/soft-foliage/lateral-detour behavior; this class only owns the
 * capability's input/output contract and lifecycle.
 */
final class NeoForgeGotoGoal {
    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final BlockPos target;
    private final double arriveRadius;
    private final int timeoutTicks;
    private final NeoForgeGotoMovement movement;
    private int ticks;

    NeoForgeGotoGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.target = new BlockPos(requiredInt(input, "targetX"), requiredInt(input, "targetY"),
                requiredInt(input, "targetZ"));
        this.arriveRadius = clamp(numberOrDefault(input, "arriveRadius", 1), 1, 8);
        var allowBlockBreaking = !Boolean.FALSE.equals(input.get("allowBlockBreaking"));
        var allowMining = Boolean.TRUE.equals(input.get("allowMining"));
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 2400), 100, 24000);
        this.movement = new NeoForgeGotoMovement(allowMining, allowBlockBreaking);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++ticks > timeoutTicks) {
                complete(client, "timeout");
                return;
            }
            if (client.level == null || client.player == null || client.gameMode == null
                    || client.screen != null) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.goal.move.goto requires survival or adventure mode");
            }
            var outcome = movement.tick(client, client.player, target, arriveRadius);
            switch (outcome) {
                case ARRIVED -> complete(client, "arrived");
                case NO_ROUTE -> complete(client, "no-route");
                case MUTATION_FAILURE -> complete(client, "repeated-mutation-failure");
                case MOVING -> { }
            }
        } catch (Throwable failure) {
            movement.releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void complete(Minecraft client, String reason) {
        movement.releaseInput(client);
        var player = client.player;
        var arrived = "arrived".equals(reason);
        var output = new LinkedHashMap<String, Object>();
        output.put("arrived", arrived);
        output.put("finalPosition", NeoForgeGotoMovement.positionMap(player.blockPosition()));
        output.put("distanceRemaining", horizontalDistance(player, target));
        output.put("ticksElapsed", ticks);
        output.put("blocksMined", movement.blocksMined());
        output.put("reason", reason);
        if (!arrived) {
            var diagnosis = movement.diagnose(client, player, target);
            if (diagnosis.nearestReachable() != null) {
                output.put("nearestReachable", NeoForgeGotoMovement.positionMap(diagnosis.nearestReachable()));
            }
            if (!diagnosis.obstructionSample().isEmpty()) {
                output.put("obstructionSample", diagnosis.obstructionSample());
            }
        }
        result.complete(Map.copyOf(output));
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dy = player.getY() - position.getY();
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int requiredInt(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        return number.intValue();
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
