// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Set;

/** Session-local ownership and expiry state for continuous client input bindings. */
public final class InputLease {
    private long generation;
    private long expiresAtMillis = Long.MAX_VALUE;
    private Set<String> owned = Set.of();

    public synchronized long replace(Set<String> nextOwned, long nowMillis, long durationMillis) {
        if (durationMillis < 0 || durationMillis > 10_000) {
            throw new IllegalArgumentException("input lease duration must be between 0 and 10000 ms");
        }
        generation++;
        owned = nextOwned == null ? Set.of() : Set.copyOf(nextOwned);
        expiresAtMillis = durationMillis == 0 ? Long.MAX_VALUE : Math.addExact(nowMillis, durationMillis);
        return generation;
    }

    public synchronized Set<String> releaseExpired(long nowMillis) {
        if (owned.isEmpty() || nowMillis < expiresAtMillis) return Set.of();
        return releaseAll();
    }

    public synchronized Set<String> releaseAll() {
        generation++;
        var released = owned;
        owned = Set.of();
        expiresAtMillis = Long.MAX_VALUE;
        return released;
    }

    public synchronized Set<String> owned() {
        return Set.copyOf(owned);
    }

    public synchronized long generation() {
        return generation;
    }
}
