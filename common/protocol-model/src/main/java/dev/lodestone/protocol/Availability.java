// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum Availability {
    AVAILABLE,
    UNAVAILABLE,
    RESTRICTED,
    DEGRADED;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
