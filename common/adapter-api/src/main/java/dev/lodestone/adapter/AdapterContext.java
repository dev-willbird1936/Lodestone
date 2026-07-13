// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Objects;
import java.util.concurrent.Executor;

public record AdapterContext(String sessionId, Executor gameExecutor, EventSink eventSink) {
    public AdapterContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(gameExecutor, "gameExecutor");
        Objects.requireNonNull(eventSink, "eventSink");
    }
}
