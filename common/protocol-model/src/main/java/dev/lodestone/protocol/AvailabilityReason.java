// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Map;

public record AvailabilityReason(String code, String message, Map<String, Object> details) {
    public AvailabilityReason {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("reason.code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("reason.message must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
