// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum Stability {
    EXPERIMENTAL,
    STABLE,
    DEPRECATED;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
