// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(String protocolVersion, String sessionId, String event, long sequence,
                            Map<String, Object> payload, long gameTick, Instant occurredAt) {
    public EventEnvelope {
        if (!ProtocolVersion.CURRENT.equals(protocolVersion)) {
            throw new IllegalArgumentException("unsupported protocol version: " + protocolVersion);
        }
        if (sessionId == null || sessionId.isBlank() || event == null || event.isBlank()) {
            throw new IllegalArgumentException("event session and name must not be blank");
        }
        if (sequence < 0 || gameTick < -1) {
            throw new IllegalArgumentException("event sequence and tick are invalid");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
