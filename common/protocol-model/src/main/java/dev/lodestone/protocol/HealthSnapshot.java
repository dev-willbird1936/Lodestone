// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.time.Instant;

public record HealthSnapshot(String state, String message, Instant observedAt, int activeSessions,
                             int registeredAdapters, long queuedEvents) {
    public HealthSnapshot {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("health state must not be blank");
        }
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
