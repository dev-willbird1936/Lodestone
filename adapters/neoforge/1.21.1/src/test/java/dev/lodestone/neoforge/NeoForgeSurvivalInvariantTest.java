// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSurvivalInvariantTest {
    @Test
    void deathOverridesWaterRecoveryAndNormalGoalLogic() {
        assertEquals(NeoForgeSurvivalInvariant.Action.PLAYER_DIED,
                NeoForgeSurvivalInvariant.decide(true, 0.0F, true, true, 0, 300));
    }

    @Test
    void waterOrDepletedAirPreemptsNormalGoalLogic() {
        assertEquals(NeoForgeSurvivalInvariant.Action.WATER_RETREAT,
                NeoForgeSurvivalInvariant.decide(false, 20.0F, false, true, 300, 300));
        assertEquals(NeoForgeSurvivalInvariant.Action.WATER_RETREAT,
                NeoForgeSurvivalInvariant.decide(false, 20.0F, false, false, 299, 300));
    }

    @Test
    void retreatBudgetKeepsConservativeAirReserve() {
        assertEquals(10, NeoForgeSurvivalInvariant.maxWaterRetreatEdges(300));
        assertEquals(0, NeoForgeSurvivalInvariant.maxWaterRetreatEdges(100));
        assertEquals(0, NeoForgeSurvivalInvariant.maxWaterRetreatEdges(40));
    }

    @Test
    void highSafetyNormalRoutesCannotInventOrUseUnbufferedOrigins() {
        assertFalse(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(false, true, true));
        assertFalse(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, false, true));
        assertTrue(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, true, true));
        assertTrue(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, false, false));
    }

    /**
     * Regression coverage for the live-caught bug where a player standing on a leaf block (a
     * vanilla-legal position, e.g. spawning atop tree canopy) saw every ordinary route search
     * refuse to even start: {@code normalRouteOriginAllowed} is now fed {@code
     * NeoForgeWorldSnapshot.originStandable}/{@code hazardBuffer} instead of {@code walkable}/
     * {@code bufferedWalkable} - see {@code originStandable}'s own doc. Those two origin-only
     * checks never look at what the support classifies as, so "standing on leaves" and "standing
     * inside tall/short grass" both compute {@code originStandable = true} (feet/head are
     * collision-free either way) regardless of what the old support-solidity contract would have
     * said about the block underneath - unlike a genuinely blocked origin (e.g. embedded in solid,
     * non-foliage terrain), which still correctly reports {@code originStandable = false} and
     * therefore still leaves the search refusing to start, no-route with an honest diagnosis.
     */
    @Test
    void normalRouteOriginNoLongerCaresWhatTheSupportBlockClassifiesAs() {
        // Standing on a leaf block: feet/head collision-free, so originStandable is true even
        // though the support underneath is foliage, not ordinary ground.
        assertTrue(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, true, false));
        assertTrue(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, true, true));
        // Standing with feet inside a tall-grass/short-grass cell: grass itself has no collision,
        // so this was already, and remains, originStandable = true.
        assertTrue(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(true, false, false));
        // A genuinely impossible origin (feet or head embedded in real, non-foliage collision) is
        // still correctly rejected regardless of support classification - this was never what the
        // fix relaxed, only the support-solidity requirement was.
        assertFalse(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(false, false, false));
        assertFalse(NeoForgeSurvivalInvariant.normalRouteOriginAllowed(false, true, false));
    }

    @Test
    void verticalRecoveryAllowsSafeUnbufferedOriginOnly() {
        assertTrue(NeoForgeSurvivalInvariant.verticalRecoveryOriginAllowed(true, false, false));
        assertFalse(NeoForgeSurvivalInvariant.verticalRecoveryOriginAllowed(false, false, false));
        assertFalse(NeoForgeSurvivalInvariant.verticalRecoveryOriginAllowed(true, true, false));
        assertFalse(NeoForgeSurvivalInvariant.verticalRecoveryOriginAllowed(true, false, true));
    }

    @Test
    void horizontalWaterInputRequiresVerifiedRetreatEdge() {
        assertFalse(NeoForgeSurvivalInvariant.allowHorizontalWaterInput(false, true));
        assertFalse(NeoForgeSurvivalInvariant.allowHorizontalWaterInput(true, false));
        assertTrue(NeoForgeSurvivalInvariant.allowHorizontalWaterInput(true, true));
    }
}
