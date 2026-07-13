// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.List;
import java.util.Objects;

public record Handshake(String protocolVersion, String sessionId, AdapterDescriptor adapter,
                        List<CapabilityDescriptor> capabilities, String serverRevision) {
    public Handshake {
        if (!ProtocolVersion.CURRENT.equals(protocolVersion)) {
            throw new IllegalArgumentException("unsupported protocol version: " + protocolVersion);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(adapter, "adapter");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
