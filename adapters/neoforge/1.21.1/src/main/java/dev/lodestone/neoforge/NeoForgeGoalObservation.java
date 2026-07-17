// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
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
    // Package-private: also the bound for NeoForgeWorldSnapshot's precomputed mob-proximity facts.
    static final int MAX_THREATS = 8;

    private NeoForgeGoalObservation() {
    }

    static Map<String, Object> capture(Minecraft client) {
        return capture(client, NeoForgeGoalPolicy.from(Map.of()));
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
        result.put("localNavigation", localNavigation(client, player, policy));
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
                        mob -> isThreat(mob, player))
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

    /** Shared hostile/targeting-mob filter, also reused by the safe-path planner's mob-proximity precompute. */
    static boolean isThreat(Mob mob, LocalPlayer player) {
        return mob.isAlive() && (mob.getTarget() == player || mob instanceof net.minecraft.world.entity.monster.Monster);
    }

    private static Map<String, Object> targetBlock(LocalPlayer player, Minecraft client) {
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return Map.of("observed", false);
        }
        var position = blockHit.getBlockPos();
        var state = client.level.getBlockState(position);
        var handHarvestable = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
        return Map.ofEntries(
                Map.entry("observed", true),
                Map.entry("position", position(position)),
                Map.entry("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                Map.entry("air", state.isAir()),
                Map.entry("fluid", !state.getFluidState().isEmpty()),
                Map.entry("collision", !state.getCollisionShape(client.level, position).isEmpty()),
                Map.entry("destroySpeed", state.getDestroySpeed(client.level, position)),
                Map.entry("handHarvestable", handHarvestable),
                Map.entry("requiresCorrectTool", !state.isAir() && !handHarvestable),
                Map.entry("correctTool", player.getMainHandItem().isCorrectToolForDrops(state)));
    }

    /**
     * Small, deterministic collision projection for realtime decisions. Native actors still read
     * the full loaded chunk directly; this bounded view gives a model enough local geometry to
     * choose between moving, jumping, retreating, and mining an obstruction.
     */
    private static Map<String, Object> localNavigation(Minecraft client, LocalPlayer player,
                                                        NeoForgeGoalPolicy policy) {
        var origin = player.blockPosition();
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var cells = new java.util.ArrayList<Map<String, Object>>();
        for (var y = -1; y <= 2; y++) {
            for (var x = -1; x <= 1; x++) {
                for (var z = -1; z <= 1; z++) {
                    var position = origin.offset(x, y, z);
                    var cell = new LinkedHashMap<String, Object>();
                    var loaded = client.level.hasChunkAt(position);
                    cell.put("position", position(position));
                    cell.put("loaded", loaded);
                    if (!loaded) {
                        cell.put("block", "");
                        cell.put("collision", false);
                        cell.put("fluid", false);
                        cell.put("hazard", true);
                        cell.put("walkable", false);
                    } else {
                        var state = client.level.getBlockState(position);
                        cell.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        cell.put("collision", !state.getCollisionShape(client.level, position).isEmpty());
                        cell.put("fluid", !state.getFluidState().isEmpty());
                        cell.put("hazard", snapshot.hazard(position));
                        cell.put("walkable", snapshot.walkable(position));
                    }
                    cells.add(Map.copyOf(cell));
                }
            }
        }

        var neighbors = new java.util.ArrayList<Map<String, Object>>();
        for (var direction : Direction.Plane.HORIZONTAL) {
            var candidate = origin.relative(direction);
            neighbors.add(Map.of(
                    "direction", direction.getName(),
                    "position", position(candidate),
                    "walkable", snapshot.walkable(candidate),
                    "hazard", snapshot.hazard(candidate),
                    "verticalDelta", candidate.getY() - origin.getY()));
        }

        var ahead = origin.relative(player.getDirection());
        var forwardDropRisk = true;
        var forwardLanding = ahead;
        for (var dy = 0; dy >= -4; dy--) {
            var candidate = new BlockPos(ahead.getX(), origin.getY() + dy, ahead.getZ());
            if (!snapshot.walkable(candidate)) continue;
            forwardLanding = candidate;
            forwardDropRisk = origin.getY() - candidate.getY() >= 2;
            break;
        }
        return Map.of(
                "origin", position(origin),
                "currentWalkable", snapshot.walkable(origin),
                "safeNeighbors", List.copyOf(neighbors),
                "forwardPosition", position(ahead),
                "forwardDropRisk", forwardDropRisk,
                "forwardLanding", position(forwardLanding),
                "cells", List.copyOf(cells));
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
