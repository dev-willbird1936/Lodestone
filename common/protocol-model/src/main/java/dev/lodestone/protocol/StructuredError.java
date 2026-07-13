// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Map;

public record StructuredError(String code, String message, boolean retryable, Map<String, Object> details) {
    public StructuredError {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("error code and message must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static StructuredError of(String code, String message, boolean retryable) {
        return new StructuredError(code, message, retryable, Map.of());
    }
}
