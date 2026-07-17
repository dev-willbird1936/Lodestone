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
