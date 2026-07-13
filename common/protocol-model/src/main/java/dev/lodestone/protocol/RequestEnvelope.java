// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Map;

public record RequestEnvelope(String protocolVersion, String requestId, String sessionId,
                              String capability, String capabilityVersion, Map<String, Object> input,
                              Long deadlineEpochMs, String idempotencyKey, boolean dryRun) {
    public RequestEnvelope {
        if (!ProtocolVersion.CURRENT.equals(protocolVersion)) {
            throw new IllegalArgumentException("unsupported protocol version: " + protocolVersion);
        }
        requireText(requestId, "requestId");
        requireText(sessionId, "sessionId");
        requireText(capability, "capability");
        if (capabilityVersion != null && capabilityVersion.isBlank()) {
            throw new IllegalArgumentException("capabilityVersion must not be blank when provided");
        }
        if (deadlineEpochMs != null && deadlineEpochMs < 1) {
            throw new IllegalArgumentException("deadlineEpochMs must be positive when provided");
        }
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank when provided");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
