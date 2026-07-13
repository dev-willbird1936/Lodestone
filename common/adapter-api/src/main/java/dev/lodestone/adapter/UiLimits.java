// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

/** Hard limits shared by every exact-version UI projection. */
public record UiLimits(int maxDepth, int maxNodes, int maxChildren) {
    public static final UiLimits DEFAULT = new UiLimits(8, 512, 50);

    public UiLimits {
        if (maxDepth < 1 || maxDepth > 32 || maxNodes < 1 || maxNodes > 4096
                || maxChildren < 1 || maxChildren > 512) {
            throw new IllegalArgumentException("UI limits are outside global safety bounds");
        }
    }
}
