// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bounded ordinary-input combat actor with live target and death readback. */
final class NeoForgeCombatGoal {
    private static final int MAX_TOTAL_TICKS = 2_400;
    private static final double TARGET_RADIUS = 24.0;
    private static final double ATTACK_DISTANCE = 3.2;

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final boolean suppressInGameMessages;
    private final NeoForgeGoalPolicy policy;
    private final LinkedHashSet<String> inputActions = new LinkedHashSet<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final NeoForgeGoalSupervisor supervisor;

    private Stage stage = Stage.WAIT_WORLD;
    private LivingEntity target;
    private int totalTicks;
    private int stageTicks;
    private int attackTicks;
    private int replans;
    private int pathIndex;
    private int stuckTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private List<BlockPos> path = List.of();
    private String targetType = "";
    private int targetEntityId = -1;
    private String targetUuid = "";
    private boolean targetObserved;
    private boolean weaponSelected;
    private int selectedWeaponSlot = -1;
    private boolean playerAlive;
    private int attackActions;
    private boolean pathComputed;

    NeoForgeCombatGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.suppressInGameMessages = Boolean.TRUE.equals(
                invocation.request().input().get("suppressInGameMessages"));
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
        this.supervisor = new NeoForgeGoalSupervisor(policy, inputActions, diagnostics);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("combat goal exceeded its bounded input budget");
            }
            if (supervisor.tick(client)) return;
            if (stage == Stage.WAIT_WORLD) {
                waitForWorld(client);
                return;
            }
            if (stage == Stage.FIND_TARGET) {
                findTarget(client);
                return;
            }
            if (target == null || !target.isAlive()) {
                stage = Stage.VERIFY;
            }
            switch (stage) {
                case APPROACH -> approach(client);
                case ATTACK -> attack(client);
                case VERIFY -> verify(client);
                case COMPLETE_DELAY -> complete(client);
                default -> { }
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void waitForWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("combat task requires survival mode");
        }
        if (policy.allowCommands()) {
            throw new IllegalStateException("survival combat workflow refuses allowCommands=true");
        }
        playerAlive = client.player.isAlive() && client.player.getHealth() > 0.0F;
        if (!playerAlive) throw new IllegalStateException("combat task started with a dead player");
        announce(client, "COMBAT READY - observing nearby hostile entities");
        stage = Stage.FIND_TARGET;
        stageTicks = 0;
    }

    private void findTarget(Minecraft client) {
        var player = requirePlayer(client);
        var candidates = client.level.getEntitiesOfClass(Monster.class,
                        player.getBoundingBox().inflate(TARGET_RADIUS), entity -> entity.isAlive()
                                && !entity.isRemoved())
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)
                        .thenComparing(entity -> entity.getUUID().toString())).toList();
        if (candidates.isEmpty()) {
            if (++stageTicks > 180) {
                throw new IllegalStateException("no nearby hostile entity was observed in loaded chunks");
            }
            return;
        }
        target = candidates.getFirst();
        targetObserved = true;
        targetEntityId = target.getId();
        targetUuid = target.getUUID().toString();
        targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        selectBestHotbarWeapon(client);
        announce(client, "Observed hostile target " + targetType + " at " + target.blockPosition());
        stage = Stage.APPROACH;
        stageTicks = 0;
        path = List.of();
        pathIndex = 0;
        pathComputed = false;
        lastDistance = Double.POSITIVE_INFINITY;
    }

    private void approach(Minecraft client) {
        var player = requirePlayer(client);
        if (player.distanceToSqr(target) <= ATTACK_DISTANCE * ATTACK_DISTANCE) {
            stopMovement(client);
            stage = Stage.ATTACK;
            stageTicks = 0;
            return;
        }
        if (!pathComputed || (!path.isEmpty() && pathIndex >= path.size()) || stageTicks % 30 == 1) {
            path = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), target.blockPosition(), policy);
            pathIndex = path.size() > 1 ? 1 : 0;
            pathComputed = true;
            replans++;
            inputActions.add(path.isEmpty() ? "observe:combat-no-safe-path" : "observe:combat-safe-path");
            if (path.isEmpty() && policy.smartNavigation()) {
                throw new IllegalStateException("safe intelligent route unavailable to hostile target");
            }
        }
        while (pathIndex < path.size() && closeTo(player, path.get(pathIndex), 0.8)) {
            pathIndex++;
            stuckTicks = 0;
        }
        var distance = Math.sqrt(player.distanceToSqr(target));
        if (distance >= lastDistance - 0.01) stuckTicks++;
        else stuckTicks = 0;
        lastDistance = distance;
        if (stuckTicks > 45) {
            replans++;
            path = List.of();
            pathComputed = false;
            stuckTicks = 0;
            diagnostics.add("combat-navigation-replan:" + target.blockPosition());
            return;
        }
        var waypoint = pathIndex < path.size() ? path.get(pathIndex) : target.blockPosition();
        lookAt(player, Vec3.atCenterOf(waypoint));
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(player.onGround() && waypoint.getY() > player.getY() + 0.35);
        inputActions.add("move:key.forward-held-to-target");
        if (++stageTicks > 1_200) throw new IllegalStateException("could not reach hostile target with normal movement input");
    }

    private void attack(Minecraft client) {
        var player = requirePlayer(client);
        if (!target.isAlive()) {
            stopAttack(client);
            stage = Stage.VERIFY;
            stageTicks = 0;
            return;
        }
        if (player.distanceToSqr(target) > (ATTACK_DISTANCE + 0.75) * (ATTACK_DISTANCE + 0.75)) {
            stopAttack(client);
            stage = Stage.APPROACH;
            stageTicks = 0;
            return;
        }
        if (policy.toolPrerequisiteGuardEnabled() && selectedWeaponSlot >= 0
                && (player.getInventory().selected != selectedWeaponSlot
                || weaponScore(player.getMainHandItem()) <= 0)) {
            KeyMapping.click(client.options.keyHotbarSlots[selectedWeaponSlot].getKey());
            inputActions.add("select:verify-hotbar-combat-weapon-" + (selectedWeaponSlot + 1));
            return;
        }
        stopMovement(client);
        lookAt(player, target.position().add(0.0, Math.max(0.4, target.getBbHeight() * 0.45), 0.0));
        if (++attackTicks % 10 == 1) {
            KeyMapping.click(client.options.keyAttack.getKey());
            inputActions.add(weaponSelected
                    ? "attack:key.attack-click-with-hotbar-weapon"
                    : "attack:key.attack-click-unarmed-or-held-item");
            attackActions++;
        }
        if (attackTicks > 1_200) {
            stopAttack(client);
            throw new IllegalStateException("normal attack input did not produce target death readback: " + targetType);
        }
    }

    private void verify(Minecraft client) {
        stopAttack(client);
        playerAlive = requirePlayer(client).isAlive() && requirePlayer(client).getHealth() > 0.0F;
        if (!playerAlive) throw new IllegalStateException("player died before hostile target terminal readback");
        if (target == null || target.isAlive()) {
            throw new IllegalStateException("hostile target death was not observed");
        }
        announce(client, "HOSTILE TARGET DEFEATED - terminal entity readback passed");
        stage = Stage.COMPLETE_DELAY;
        stageTicks = 0;
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        playerAlive = requirePlayer(client).isAlive() && requirePlayer(client).getHealth() > 0.0F;
        result.complete(Map.ofEntries(
                Map.entry("targetObserved", targetObserved),
                Map.entry("targetKilled", target != null && !target.isAlive()),
                Map.entry("targetEntityId", targetEntityId),
                Map.entry("targetUuid", targetUuid),
                Map.entry("targetType", targetType),
                Map.entry("weaponSelected", weaponSelected),
                Map.entry("attackActions", attackActions),
                Map.entry("replans", replans),
                Map.entry("playerAlive", playerAlive),
                Map.entry("healthAtEnd", requirePlayer(client).getHealth()),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("observation", policy.observation()),
                Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowBlockBreaking", policy.allowBlockBreaking()),
                Map.entry("allowBlockPlacing", policy.allowBlockPlacing()),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("safetyInterventions", List.copyOf(diagnostics)),
                Map.entry("commandsUsed", false),
                Map.entry("directMutationUsed", false),
                Map.entry("inputActions", List.copyOf(inputActions))));
    }

    private void selectBestHotbarWeapon(Minecraft client) {
        if (!policy.toolPrerequisiteGuardEnabled()) return;
        var player = requirePlayer(client);
        var bestSlot = player.getInventory().selected;
        var bestScore = weaponScore(player.getInventory().getItem(bestSlot));
        for (var slot = 0; slot < 9; slot++) {
            var score = weaponScore(player.getInventory().getItem(slot));
            if (score > bestScore) {
                bestSlot = slot;
                bestScore = score;
            }
        }
        if (bestScore <= 0) return;
        if (player.getInventory().selected != bestSlot) {
            KeyMapping.click(client.options.keyHotbarSlots[bestSlot].getKey());
            inputActions.add("select:best-hotbar-combat-weapon-" + (bestSlot + 1));
        }
        selectedWeaponSlot = bestSlot;
        weaponSelected = true;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof SwordItem) return 100;
        if (item instanceof AxeItem) return 80;
        return 0;
    }

    private static boolean closeTo(LocalPlayer player, BlockPos position, double radius) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz) <= radius
                && Math.abs(player.blockPosition().getY() - position.getY()) <= 1;
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
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

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    private static void releaseInput(Minecraft client) {
        stopMovement(client);
        stopAttack(client);
        client.options.keyUse.setDown(false);
    }

    private void announce(Minecraft client, String message) {
        if (suppressInGameMessages) return;
        if (client.player != null) client.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("[Lodestone Goal] " + message), true);
    }

    private static LocalPlayer requirePlayer(Minecraft client) {
        if (client.level == null || client.player == null) {
            throw new IllegalStateException("client player/world unavailable");
        }
        return client.player;
    }

    private enum Stage { WAIT_WORLD, FIND_TARGET, APPROACH, ATTACK, VERIFY, COMPLETE_DELAY }
}
