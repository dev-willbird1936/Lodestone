// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded benchmark actor for testing ordinary-input navigation against constructed fixtures.
 *
 * <p>Ported from {@code NeoForgeNavigationGoal} for the {@code minecraft.goal.navigation.safe-waypoint}
 * capability; behavior is bit-for-bit identical to the NeoForge 1.21.1 original.
 */
final class FabricNavigationGoal {
    private static final int MAX_TOTAL_TICKS = 2_400;

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final FabricGoalPolicy policy;
    private final BlockPos target;
    private final List<String> actions = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final FabricGoalSupervisor supervisor;

    private List<BlockPos> path = List.of();
    private int pathIndex;
    private int totalTicks;
    private int replans;
    private int pathNodesVisited;
    private int stuckTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private boolean directFallback;

    FabricNavigationGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.policy = FabricGoalPolicy.from(invocation.request().input());
        this.target = new BlockPos(requiredInt(invocation, "targetX"),
                requiredInt(invocation, "targetY"), requiredInt(invocation, "targetZ"));
        this.supervisor = new FabricGoalSupervisor(policy, actions, diagnostics);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("benchmark navigation exceeded its bounded input budget");
            }
            if (client.level == null || client.player == null || client.gameMode == null
                    || client.screen != null) return;
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("benchmark navigation requires survival or adventure mode");
            }
            if (policy.allowCommands()) {
                throw new IllegalStateException("survival navigation workflow refuses allowCommands=true");
            }
            if (reached(client.player)) {
                complete(client);
                return;
            }
            if (supervisor.tick(client)) return;

            var player = client.player;
            if (!policy.smartNavigation()) {
                directFallback = true;
                lookAt(player, target);
                setMovement(client, true, false, target.getY() > player.blockPosition().getY() && player.onGround());
                actions.add("move:raw-direct-to-target");
                return;
            }

            if (path.isEmpty() || pathIndex >= path.size() || totalTicks % 20 == 0) {
                replan(client);
            }
            if (path.isEmpty()) {
                if (policy.smartNavigation()) {
                    throw new IllegalStateException("safe intelligent route unavailable to benchmark target " + target);
                }
                directFallback = true;
                lookAt(player, target);
                setMovement(client, true, false, false);
                actions.add("move:direct-fallback-to-target");
                return;
            }

            while (pathIndex < path.size() && closeTo(player, path.get(pathIndex), 0.8)) {
                pathNodesVisited++;
                pathIndex++;
                stuckTicks = 0;
                lastDistance = Double.POSITIVE_INFINITY;
            }
            if (reached(player)) {
                complete(client);
                return;
            }
            if (pathIndex >= path.size()) {
                replan(client);
                return;
            }

            var waypoint = path.get(pathIndex);
            var distance = horizontalDistance(player, waypoint);
            if (distance >= lastDistance - 0.01) stuckTicks++;
            else stuckTicks = 0;
            lastDistance = distance;
            if (stuckTicks > 45) {
                diagnostics.add("navigation-stuck:replanning-at-" + player.blockPosition());
                replan(client);
                return;
            }
            lookAt(player, waypoint);
            setMovement(client, true, false, waypoint.getY() > player.blockPosition().getY() && player.onGround());
            actions.add("move:key.forward-held");
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void replan(Minecraft client) {
        var player = client.player;
        path = FabricSafePathPlanner.find(client.level, player, player.blockPosition(), target, policy);
        pathIndex = path.size() > 1 ? 1 : 0;
        replans++;
        stuckTicks = 0;
        lastDistance = Double.POSITIVE_INFINITY;
        diagnostics.add("replan:" + replans + ":nodes=" + path.size() + ":from="
                + player.blockPosition() + ":to=" + target);
        actions.add(path.isEmpty() ? "observe:no-safe-path" : "observe:loaded-chunk-safe-path");
    }

    private boolean reached(LocalPlayer player) {
        return horizontalDistance(player, target) <= 2.7
                && Math.abs(player.blockPosition().getY() - target.getY()) <= 2;
    }

    private static boolean closeTo(LocalPlayer player, BlockPos position, double radius) {
        return horizontalDistance(player, position) <= radius
                && Math.abs(player.blockPosition().getY() - position.getY()) <= 1;
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        if (!client.player.isAlive() || client.player.getHealth() <= 0.0F) {
            throw new IllegalStateException("player died before navigation terminal readback");
        }
        result.complete(Map.ofEntries(
                Map.entry("reachedTarget", true),
                Map.entry("target", position(target)),
                Map.entry("finalPosition", position(client.player.blockPosition())),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("observation", policy.observation()),
                Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("replans", replans),
                Map.entry("plannedPathLength", path.size()),
                Map.entry("pathNodesVisited", pathNodesVisited),
                Map.entry("directFallback", directFallback),
                Map.entry("safetyInterventions", List.copyOf(diagnostics)),
                Map.entry("inputActions", List.copyOf(actions)),
                Map.entry("playerAlive", client.player.isAlive()),
                Map.entry("commandsUsed", false),
                Map.entry("directMutationUsed", false)));
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private static int requiredInt(InvocationContext invocation, String key) {
        var value = invocation.request().input().get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        return number.intValue();
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        var eye = player.getEyePosition();
        var dx = target.getX() + 0.5 - eye.x;
        var dy = target.getY() + 0.5 - eye.y;
        var dz = target.getZ() + 0.5 - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private static void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(false);
    }

    private static void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }
}
