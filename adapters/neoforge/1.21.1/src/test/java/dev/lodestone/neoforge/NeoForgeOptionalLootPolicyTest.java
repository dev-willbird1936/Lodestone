// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeOptionalLootPolicyTest {
    @Test
    void missingLocalChestImmediatelyYieldsToStarterResources() {
        assertEquals(NeoForgeOptionalLootPolicy.Action.STARTER_RESOURCE,
                NeoForgeOptionalLootPolicy.choose(false));
        assertEquals(NeoForgeOptionalLootPolicy.Action.USE_OBSERVED_CHEST,
                NeoForgeOptionalLootPolicy.choose(true));
    }
}
