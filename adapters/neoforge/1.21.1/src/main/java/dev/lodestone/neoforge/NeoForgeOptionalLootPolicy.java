// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/** Keeps optional natural loot from delaying mandatory survival prerequisites. */
final class NeoForgeOptionalLootPolicy {
    enum Action {
        USE_OBSERVED_CHEST,
        STARTER_RESOURCE
    }

    private NeoForgeOptionalLootPolicy() {
    }

    static Action choose(boolean localChestObserved) {
        return localChestObserved ? Action.USE_OBSERVED_CHEST : Action.STARTER_RESOURCE;
    }
}
