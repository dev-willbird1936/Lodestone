// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum Idempotency {
    IDEMPOTENT,
    NON_IDEMPOTENT,
    UNKNOWN;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
