// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Map;

public record ResultEnvelope(String protocolVersion, String requestId, Status status,
                             Map<String, Object> output, StructuredError error,
                             Map<String, Object> progress) {
    public enum Status {
        OK("ok"),
        ERROR("error"),
        CANCELLED("cancelled"),
        TIMED_OUT("timed-out");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    public ResultEnvelope {
        if (!ProtocolVersion.CURRENT.equals(protocolVersion)) {
            throw new IllegalArgumentException("unsupported protocol version: " + protocolVersion);
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        output = output == null ? Map.of() : Map.copyOf(output);
        progress = progress == null ? Map.of() : Map.copyOf(progress);
        if (status == Status.OK && error != null) {
            throw new IllegalArgumentException("successful result must not contain an error");
        }
        if (status != Status.OK && error == null) {
            throw new IllegalArgumentException("unsuccessful result requires an error");
        }
    }

    public static ResultEnvelope ok(String requestId, Map<String, Object> output) {
        return new ResultEnvelope(ProtocolVersion.CURRENT, requestId, Status.OK, output, null, Map.of());
    }

    public static ResultEnvelope error(String requestId, Status status, StructuredError error) {
        if (status == Status.OK) {
            throw new IllegalArgumentException("error result cannot be OK");
        }
        return new ResultEnvelope(ProtocolVersion.CURRENT, requestId, status, Map.of(), error, Map.of());
    }

    public ResultEnvelope withRequestId(String replacement) {
        return new ResultEnvelope(protocolVersion, replacement, status, output, error, progress);
    }
}
