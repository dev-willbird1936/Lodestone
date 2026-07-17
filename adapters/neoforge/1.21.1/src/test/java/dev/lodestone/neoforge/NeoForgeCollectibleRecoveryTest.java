// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeCollectibleRecoveryTest {
    @Test
    void ordersSafeAlternativesBeforeEquivalentRetarget() {
        assertEquals(NeoForgeCollectibleRecovery.Alternative.SAFE_VANTAGE,
                choose(true, true, true, true, true));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.CLEAR_BREAKABLE_BLOCKER,
                choose(false, true, true, true, false));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.PLACE_SUPPORT,
                choose(false, false, true, true, false));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.DIRECT_PICKUP_ORIGIN,
                choose(false, false, false, false, true));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.DIRECT_PICKUP_ORIGIN,
                choose(false, true, true, true, true));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.RETARGET_EQUIVALENT,
                choose(false, false, false, true, false));
        assertEquals(NeoForgeCollectibleRecovery.Alternative.EXHAUSTED,
                choose(false, false, false, false, false));
    }

    private static NeoForgeCollectibleRecovery.Alternative choose(boolean vantage, boolean blocker,
                                                                    boolean support, boolean equivalent,
                                                                    boolean directPickupOrigin) {
        return NeoForgeCollectibleRecovery.choose(
                new NeoForgeCollectibleRecovery.Options(vantage, blocker, support, equivalent, directPickupOrigin));
    }
}
