// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/** Source-agnostic recovery ordering for an observed but unreachable collectible. */
final class NeoForgeCollectibleRecovery {
    enum Alternative {
        SAFE_VANTAGE,
        CLEAR_BREAKABLE_BLOCKER,
        PLACE_SUPPORT,
        RETARGET_EQUIVALENT,
        EXHAUSTED
    }

    record Options(boolean safeVantage, boolean legalVisibleBlocker, boolean safeSupportPlacement,
                   boolean equivalentTarget) {
    }

    private NeoForgeCollectibleRecovery() {
    }

    static Alternative choose(Options options) {
        if (options.safeVantage()) return Alternative.SAFE_VANTAGE;
        if (options.legalVisibleBlocker()) return Alternative.CLEAR_BREAKABLE_BLOCKER;
        if (options.safeSupportPlacement()) return Alternative.PLACE_SUPPORT;
        if (options.equivalentTarget()) return Alternative.RETARGET_EQUIVALENT;
        return Alternative.EXHAUSTED;
    }
}
