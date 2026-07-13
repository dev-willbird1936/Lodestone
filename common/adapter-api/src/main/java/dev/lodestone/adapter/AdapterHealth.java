// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.time.Instant;

public record AdapterHealth(State state, String message, Instant observedAt) {
    public enum State {
        STARTING,
        READY,
        NO_WORLD,
        STOPPING,
        FAILED
    }

    public AdapterHealth {
        if (state == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("adapter health state and message are required");
        }
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }

    public static AdapterHealth starting() {
        return new AdapterHealth(State.STARTING, "adapter is starting", Instant.now());
    }
}
