// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared "walk to the nearest matching drop and let vanilla auto-pickup collect it, repeat until
 * none remain" engine backing both {@code minecraft.goal.gather.collect-drops}
 * ({@link NeoForgeCollectDropsGoal}) and the collection phase of
 * {@code minecraft.goal.gather.chop-tree} ({@link NeoForgeChopTreeGoal}). Deliberately a plain
 * tickable object, not a goal actor with its own {@link java.util.concurrent.CompletableFuture} -
 * only one native goal actor may own client input at a time, so a nested actor cannot work here;
 * the owning goal ticks this once per its own tick and owns the timeout budget itself. Reuses
 * {@link NeoForgeGotoMovement} for the actual walking rather than forking movement logic.
 */
final class NeoForgeDropCollector {
    enum Outcome { MOVING, DONE }

    private final double radius;
    private final String itemFilter;
    private final Set<Integer> unreachableEntityIds = new HashSet<>();
    private Map<String, Integer> initialInventory;
    private boolean initialized;
    private NeoForgeGotoMovement movement;
    private int currentTargetEntityId = -1;

    /** @param itemFilter an already-normalized namespaced item id, or {@code null} to match any item. */
    NeoForgeDropCollector(double radius, String itemFilter) {
        this.radius = radius;
        this.itemFilter = itemFilter;
    }

    Outcome tick(Minecraft client, LocalPlayer player) {
        if (!initialized) {
            initialInventory = snapshotInventory(player);
            initialized = true;
        }
        if (movement != null) {
            var target = findItemEntity(client, currentTargetEntityId);
            if (target == null) {
                // Already picked up (or despawned) mid-approach - stop walking to a stale position
                // and let the rescan below pick the next nearest candidate this same tick.
                movement.releaseInput(client);
                movement = null;
            } else {
                var outcome = movement.tick(client, player, target.blockPosition(), 1.0);
                switch (outcome) {
                    case ARRIVED -> movement = null;
                    case NO_ROUTE, MUTATION_FAILURE -> {
                        unreachableEntityIds.add(currentTargetEntityId);
                        movement = null;
                    }
                    case MOVING -> {
                        return Outcome.MOVING;
                    }
                }
            }
        }
        var candidates = scanCandidates(client, player);
        var next = NeoForgeDropSelector.selectNext(candidates, unreachableEntityIds);
        if (next == null) return Outcome.DONE;
        currentTargetEntityId = next.entityId();
        movement = new NeoForgeGotoMovement(false, true);
        return Outcome.MOVING;
    }

    /** Bounded scan of every currently matching, in-radius drop regardless of reachability - used
     * both to pick the next target and, once {@link #tick} reports {@link Outcome#DONE}, to tell
     * "genuinely collected everything" apart from "abandoned unreachable remainder". */
    int itemsRemaining(Minecraft client, LocalPlayer player) {
        return scanCandidates(client, player).size();
    }

    /** Per-item-id count gained since this collector started, computed from an inventory snapshot
     * taken on its first tick rather than tracked incrementally - immune to stack-splitting/merging
     * noise along the way. */
    Map<String, Integer> collectedDelta(LocalPlayer player) {
        var current = snapshotInventory(player);
        var delta = new LinkedHashMap<String, Integer>();
        for (var entry : current.entrySet()) {
            var before = initialInventory == null ? 0 : initialInventory.getOrDefault(entry.getKey(), 0);
            var diff = entry.getValue() - before;
            if (diff > 0) delta.put(entry.getKey(), diff);
        }
        return delta;
    }

    void releaseInput(Minecraft client) {
        if (movement != null) movement.releaseInput(client);
    }

    private List<NeoForgeDropSelector.Candidate> scanCandidates(Minecraft client, LocalPlayer player) {
        var candidates = new ArrayList<NeoForgeDropSelector.Candidate>();
        for (var entity : client.level.getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(radius), ItemEntity::isAlive)) {
            var distance = Math.sqrt(player.distanceToSqr(entity));
            if (distance > radius) continue;
            var itemId = itemId(entity.getItem());
            if (itemFilter != null && !itemFilter.equals(itemId)) continue;
            candidates.add(new NeoForgeDropSelector.Candidate(entity.getId(), itemId, distance));
        }
        return candidates;
    }

    private static ItemEntity findItemEntity(Minecraft client, int entityId) {
        return client.level.getEntity(entityId) instanceof ItemEntity item && item.isAlive() ? item : null;
    }

    private static Map<String, Integer> snapshotInventory(LocalPlayer player) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            counts.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
