// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import java.util.List;

final class McpProtocol {
    static final String CURRENT = "2025-11-25";
    private static final List<String> SUPPORTED = List.of(CURRENT, "2025-06-18", "2025-03-26");

    private McpProtocol() {
    }

    static String negotiate(String clientVersion) {
        if (clientVersion != null && SUPPORTED.contains(clientVersion)) {
            return clientVersion;
        }
        throw new IllegalArgumentException("unsupported MCP protocol version: " + clientVersion);
    }

    static boolean supports(String protocolVersion) {
        return protocolVersion != null && SUPPORTED.contains(protocolVersion);
    }
}
