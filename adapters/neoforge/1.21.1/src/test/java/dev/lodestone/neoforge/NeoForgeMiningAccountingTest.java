// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeMiningAccountingTest {
    @Test
    void visibleAirTransitionIsOnlySuccessfulBreak() {
        var progress = NeoForgeMiningAccounting.blockBecameAir(4, 2);

        assertEquals(5, progress.nextMineIndex());
        assertEquals(3, progress.handMinedLogs());
        assertTrue(progress.blockBroken());
        assertEquals("block-broken-by-hand", progress.reason());
    }

    @Test
    void skippedTargetAdvancesCursorWithoutClaimingCollection() {
        var progress = NeoForgeMiningAccounting.skippedTarget(4, 2, "occluded-vantage");

        assertEquals(5, progress.nextMineIndex());
        assertEquals(2, progress.handMinedLogs());
        assertFalse(progress.blockBroken());
        assertEquals("occluded-vantage", progress.reason());
    }
}
