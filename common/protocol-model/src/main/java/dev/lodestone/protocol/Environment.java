// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum Environment {
    CLIENT,
    INTEGRATED_SERVER,
    DEDICATED_SERVER,
    REMOTE;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
