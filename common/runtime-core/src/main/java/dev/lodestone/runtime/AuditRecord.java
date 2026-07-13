// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import java.time.Instant;

public record AuditRecord(String requestId, String sessionId, String capability, String outcome,
                          Instant occurredAt) {
}
