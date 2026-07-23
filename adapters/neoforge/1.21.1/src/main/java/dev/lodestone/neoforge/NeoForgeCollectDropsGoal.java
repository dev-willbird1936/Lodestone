// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backs {@code minecraft.goal.gather.collect-drops}. Thin wrapper around the shared
 * {@link NeoForgeDropCollector} engine - see that class for the actual approach/pickup loop; this
 * class only owns the capability's input/output contract, timeout budget, and lifecycle.
 */
final class NeoForgeCollectDropsGoal {
    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final int timeoutTicks;
    private final NeoForgeDropCollector collector;
    private int ticks;

    NeoForgeCollectDropsGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        var radius = clamp(numberOrDefault(input, "radius", 12), 1, 32);
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 600), 20, 2400);
        this.collector = new NeoForgeDropCollector(radius, normalizeItemFilter(input.get("itemFilter")));
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
            if (collector.tick(client, client.player) == NeoForgeDropCollector.Outcome.DONE) {
                var remaining = collector.itemsRemaining(client, client.player);
                complete(client, remaining > 0 ? "unreachable-remainder" : "collected-all");
            }
        } catch (Throwable failure) {
            collector.releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void complete(Minecraft client, String reason) {
        collector.releaseInput(client);
        var output = new LinkedHashMap<String, Object>();
        output.put("collected", Map.copyOf(collector.collectedDelta(client.player)));
        output.put("itemsRemaining", collector.itemsRemaining(client, client.player));
        output.put("ticksElapsed", ticks);
        output.put("reason", reason);
        result.complete(Map.copyOf(output));
    }

    private static String normalizeItemFilter(Object value) {
        if (value == null) return null;
        var text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) return null;
        return text.contains(":") ? text : "minecraft:" + text;
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
