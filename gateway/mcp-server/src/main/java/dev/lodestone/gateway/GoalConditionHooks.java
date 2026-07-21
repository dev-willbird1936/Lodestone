// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Session-owned, polling-backed goal conditions. No adapter/JAR event hook is required. */
final class GoalConditionHooks {
    static final int MAX_HOOKS_PER_SESSION = 32;

    private final LodestoneRuntime runtime;
    private final ConcurrentMap<String, ConcurrentMap<String, InventoryHook>> hooksBySession =
            new ConcurrentHashMap<>();

    GoalConditionHooks(LodestoneRuntime runtime) {
        this.runtime = runtime;
    }

    Map<String, Object> createInventoryAtLeast(String callerSessionId, String item, int count, boolean once) {
        if (item == null || item.isBlank() || item.length() > 256 || !item.contains(":")) {
            throw new IllegalArgumentException("item must be a namespaced Minecraft item id");
        }
        if (count < 1 || count > 4096) {
            throw new IllegalArgumentException("count must be between 1 and 4096");
        }
        var sessionHooks = hooksBySession.computeIfAbsent(callerSessionId, ignored -> new ConcurrentHashMap<>());
        if (sessionHooks.size() >= MAX_HOOKS_PER_SESSION) {
            throw new IllegalArgumentException("session condition-hook limit reached");
        }
        var id = UUID.randomUUID().toString();
        var hook = new InventoryHook(id, item, count, once, System.currentTimeMillis());
        sessionHooks.put(id, hook);
        return hook.toMap(false, 0, null);
    }

    Map<String, Object> poll(String callerSessionId, String hookId, AuthorizationPolicy authorization) {
        var sessionHooks = hooksBySession.get(callerSessionId);
        if (sessionHooks == null || sessionHooks.isEmpty()) {
            if (hookId != null && !hookId.isBlank()) {
                throw new IllegalArgumentException("unknown condition hook: " + hookId);
            }
            return Map.of("hooks", List.of(), "pollSucceeded", true);
        }
        var selected = new ArrayList<InventoryHook>();
        if (hookId == null || hookId.isBlank()) {
            selected.addAll(sessionHooks.values());
        } else {
            var hook = sessionHooks.get(hookId);
            if (hook == null) {
                throw new IllegalArgumentException("unknown condition hook: " + hookId);
            }
            selected.add(hook);
        }

        var inventory = readInventory(callerSessionId, authorization);
        if (inventory.status() != ResultEnvelope.Status.OK) {
            return Map.of("hooks", List.of(), "inventoryResult", inventory, "pollSucceeded", false);
        }
        var counts = inventoryCounts(inventory.output());
        var rows = new ArrayList<Map<String, Object>>();
        for (var hook : selected) {
            var observed = counts.getOrDefault(hook.item(), 0);
            var firedNow = !hook.fired() && observed >= hook.count();
            if (firedNow) {
                hook.fire(observed);
            }
            rows.add(hook.toMap(firedNow, observed, null));
            if (hook.once() && hook.fired()) {
                sessionHooks.remove(hook.id(), hook);
            }
        }
        if (sessionHooks.isEmpty()) {
            hooksBySession.remove(callerSessionId, sessionHooks);
        }
        return Map.of("hooks", List.copyOf(rows), "pollSucceeded", true);
    }

    Map<String, Object> remove(String callerSessionId, String hookId) {
        var sessionHooks = hooksBySession.get(callerSessionId);
        var removed = sessionHooks != null && sessionHooks.remove(hookId) != null;
        if (sessionHooks != null && sessionHooks.isEmpty()) {
            hooksBySession.remove(callerSessionId, sessionHooks);
        }
        return Map.of("hookId", hookId, "removed", removed);
    }

    void removeSession(String callerSessionId) {
        hooksBySession.remove(callerSessionId);
    }

    private ResultEnvelope readInventory(String callerSessionId, AuthorizationPolicy authorization) {
        var requestId = UUID.randomUUID().toString();
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, requestId, runtime.sessionId(),
                "minecraft.inventory.read", "1.0", Map.of(), null, null, false);
        return runtime.invoke(request, callerSessionId, authorization).join();
    }

    private static Map<String, Integer> inventoryCounts(Map<String, Object> output) {
        var counts = new LinkedHashMap<String, Integer>();
        Iterable<?> rawSlots = List.of();
        if (output.get("slots") instanceof Iterable<?> slots) {
            rawSlots = slots;
        } else if (output.get("items") instanceof Iterable<?> items) {
            rawSlots = items;
        }
        for (var raw : rawSlots) {
            if (!(raw instanceof Map<?, ?> slot)) continue;
            var itemValue = first(slot, "item", "id", "itemId");
            if (itemValue == null) continue;
            var countValue = first(slot, "count", "quantity");
            var count = countValue instanceof Number number ? number.intValue() : 0;
            counts.merge(String.valueOf(itemValue), Math.max(0, count), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private static Object first(Map<?, ?> values, String... keys) {
        for (var key : keys) {
            if (values.containsKey(key) && values.get(key) != null) return values.get(key);
        }
        return null;
    }

    private static final class InventoryHook {
        private final String id;
        private final String item;
        private final int count;
        private final boolean once;
        private final long createdAtEpochMs;
        private volatile boolean fired;
        private volatile Integer firedCount;
        private volatile Long firedAtEpochMs;

        private InventoryHook(String id, String item, int count, boolean once, long createdAtEpochMs) {
            this.id = id;
            this.item = item;
            this.count = count;
            this.once = once;
            this.createdAtEpochMs = createdAtEpochMs;
        }

        private String id() { return id; }
        private String item() { return item; }
        private int count() { return count; }
        private boolean once() { return once; }
        private boolean fired() { return fired; }

        private synchronized void fire(int observed) {
            if (fired) return;
            fired = true;
            firedCount = observed;
            firedAtEpochMs = System.currentTimeMillis();
        }

        private Map<String, Object> toMap(boolean firedNow, int observed, String error) {
            var result = new LinkedHashMap<String, Object>();
            result.put("hookId", id);
            result.put("type", "inventory-item-at-least");
            result.put("item", item);
            result.put("count", count);
            result.put("once", once);
            result.put("createdAtEpochMs", createdAtEpochMs);
            result.put("observedCount", observed);
            result.put("fired", fired);
            result.put("firedNow", firedNow);
            if (firedCount != null) result.put("firedCount", firedCount);
            if (firedAtEpochMs != null) result.put("firedAtEpochMs", firedAtEpochMs);
            if (error != null) result.put("error", error);
            return Map.copyOf(result);
        }
    }
}
