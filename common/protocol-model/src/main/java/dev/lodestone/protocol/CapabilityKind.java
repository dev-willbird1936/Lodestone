// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum CapabilityKind {
    COMMAND,
    INPUT,
    ACTION,
    QUERY,
    EVENT;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
