// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import java.time.Instant;
import java.util.List;

/** Additive audit view containing the transport caller and delegated capability ancestry. */
public record AuditTraceRecord(String requestId, String sessionId, String callerSessionId,
                               String capability, List<String> delegationPath, String outcome,
                               Instant occurredAt) {
    public AuditTraceRecord {
        delegationPath = delegationPath == null ? List.of() : List.copyOf(delegationPath);
    }
}
