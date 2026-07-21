// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricSurvivalInvariantTest {
    @Test
    void deathOverridesWaterRecoveryAndNormalGoalLogic() {
        assertEquals(FabricSurvivalInvariant.Action.PLAYER_DIED,
                FabricSurvivalInvariant.decide(true, 0.0F, true, true, 0, 300));
    }

    @Test
    void waterOrDepletedAirPreemptsNormalGoalLogic() {
        assertEquals(FabricSurvivalInvariant.Action.WATER_RETREAT,
                FabricSurvivalInvariant.decide(false, 20.0F, false, true, 300, 300));
        assertEquals(FabricSurvivalInvariant.Action.WATER_RETREAT,
                FabricSurvivalInvariant.decide(false, 20.0F, false, false, 299, 300));
    }

    @Test
    void retreatBudgetKeepsConservativeAirReserve() {
        assertEquals(10, FabricSurvivalInvariant.maxWaterRetreatEdges(300));
        assertEquals(0, FabricSurvivalInvariant.maxWaterRetreatEdges(100));
        assertEquals(0, FabricSurvivalInvariant.maxWaterRetreatEdges(40));
    }

    @Test
    void highSafetyNormalRoutesCannotInventOrUseUnbufferedOrigins() {
        assertFalse(FabricSurvivalInvariant.normalRouteOriginAllowed(false, true, true));
        assertFalse(FabricSurvivalInvariant.normalRouteOriginAllowed(true, false, true));
        assertTrue(FabricSurvivalInvariant.normalRouteOriginAllowed(true, true, true));
        assertTrue(FabricSurvivalInvariant.normalRouteOriginAllowed(true, false, false));
    }

    @Test
    void verticalRecoveryAllowsSafeUnbufferedOriginOnly() {
        assertTrue(FabricSurvivalInvariant.verticalRecoveryOriginAllowed(true, false, false));
        assertFalse(FabricSurvivalInvariant.verticalRecoveryOriginAllowed(false, false, false));
        assertFalse(FabricSurvivalInvariant.verticalRecoveryOriginAllowed(true, true, false));
        assertFalse(FabricSurvivalInvariant.verticalRecoveryOriginAllowed(true, false, true));
    }

    @Test
    void horizontalWaterInputRequiresVerifiedRetreatEdge() {
        assertFalse(FabricSurvivalInvariant.allowHorizontalWaterInput(false, true));
        assertFalse(FabricSurvivalInvariant.allowHorizontalWaterInput(true, false));
        assertTrue(FabricSurvivalInvariant.allowHorizontalWaterInput(true, true));
    }
}
