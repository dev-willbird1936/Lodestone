// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact read-only world/player facts shared by native intelligent goal outputs. */
final class NeoForgeGoalObservation {
    private static final int MAX_INVENTORY_ENTRIES = 48;
    private static final int MAX_THREATS = 8;

    private NeoForgeGoalObservation() {
    }

    static Map<String, Object> capture(Minecraft client, NeoForgeGoalPolicy policy) {
        var result = new LinkedHashMap<String, Object>();
        result.put("intelligence", policy.intelligence().id());
        result.put("safety", policy.safety().id());
        result.put("worldReady", client.level != null && client.player != null && client.gameMode != null);
        if (client.level == null || client.player == null || client.gameMode == null) {
            result.put("dimension", "");
            result.put("gameMode", "unknown");
            return Map.copyOf(result);
        }

        var player = client.player;
        result.put("dimension", client.level.dimension().location().toString());
        result.put("gameMode", gameMode(client.gameMode.getPlayerMode()));
        result.put("loadedChunk", client.level.hasChunkAt(player.blockPosition()));
        result.put("player", playerState(client, player));
        result.put("inventory", inventory(player));
        result.put("nearbyThreats", threats(player));
        result.put("targetBlock", targetBlock(player, client));
        return Map.copyOf(result);
    }

    private static Map<String, Object> playerState(Minecraft client, LocalPlayer player) {
        return Map.ofEntries(
                Map.entry("position", position(player.blockPosition())),
                Map.entry("health", player.getHealth()),
                Map.entry("maxHealth", player.getMaxHealth()),
                Map.entry("food", player.getFoodData().getFoodLevel()),
                Map.entry("onGround", player.onGround()),
                Map.entry("fallDistance", player.fallDistance),
                Map.entry("inWater", player.isInWater()),
                Map.entry("inLava", player.isInLava()),
                Map.entry("onFire", player.isOnFire()),
                Map.entry("sprinting", player.isSprinting()),
                Map.entry("heldItem", itemId(player.getMainHandItem())),
                Map.entry("heldItemCorrectForTarget", heldItemCorrectForTarget(player, client)));
    }

    private static Map<String, Object> inventory(LocalPlayer player) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            var id = itemId(stack);
            counts.merge(id, stack.getCount(), Integer::sum);
            if (counts.size() >= MAX_INVENTORY_ENTRIES) break;
        }
        return Map.of("selectedHotbarSlot", player.getInventory().selected,
                "items", Map.copyOf(counts));
    }

    private static List<Map<String, Object>> threats(LocalPlayer player) {
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12.0),
                        mob -> mob.isAlive() && (mob.getTarget() == player || mob instanceof net.minecraft.world.entity.monster.Monster))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr))
                .limit(MAX_THREATS)
                .map(mob -> Map.<String, Object>ofEntries(
                        Map.entry("type", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString()),
                        Map.entry("position", position(mob.blockPosition())),
                        Map.entry("distance", Math.sqrt(player.distanceToSqr(mob))),
                        Map.entry("health", mob.getHealth()),
                        Map.entry("targetingPlayer", mob.getTarget() == player)))
                .toList();
    }

    private static Map<String, Object> targetBlock(LocalPlayer player, Minecraft client) {
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return Map.of("observed", false);
        }
        var position = blockHit.getBlockPos();
        var state = client.level.getBlockState(position);
        return Map.ofEntries(
                Map.entry("observed", true),
                Map.entry("position", position(position)),
                Map.entry("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                Map.entry("air", state.isAir()),
                Map.entry("fluid", !state.getFluidState().isEmpty()),
                Map.entry("correctTool", player.getMainHandItem().isCorrectToolForDrops(state)));
    }

    private static boolean heldItemCorrectForTarget(LocalPlayer player, Minecraft client) {
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return false;
        return player.getMainHandItem().isCorrectToolForDrops(client.level.getBlockState(blockHit.getBlockPos()));
    }

    private static String gameMode(GameType type) {
        return type == null ? "unknown" : type.getName();
    }

    private static String itemId(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }
}
