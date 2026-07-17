// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgePathCostExtensionsTest {
    @Test
    void mobProximityCostIsZeroAtOrBeyondFollowRangeAndQuadraticInsideIt() {
        assertEquals(0.0, NeoForgePathCostExtensions.mobProximityCost(16.0, 16.0, false), 1e-9);
        assertEquals(0.0, NeoForgePathCostExtensions.mobProximityCost(20.0, 16.0, false), 1e-9);
        // half-way to the mob: closeness=0.5, closeness^2=0.25, default weight 5.0 -> 1.25
        assertEquals(1.25, NeoForgePathCostExtensions.mobProximityCost(8.0, 16.0, false), 1e-9);
        // same distance, but the mob is already targeting the player: weight 10.0 -> 2.5
        assertEquals(2.5, NeoForgePathCostExtensions.mobProximityCost(8.0, 16.0, true), 1e-9);
        // standing on the mob itself: closeness=1.0, full weight.
        assertEquals(10.0, NeoForgePathCostExtensions.mobProximityCost(0.0, 16.0, true), 1e-9);
    }

    @Test
    void lavaProximityCostUsesAWiderFixedRadiusAndAHigherPeakWeightThanMobProximity() {
        assertEquals(0.0, NeoForgePathCostExtensions.lavaProximityCost(6.0), 1e-9);
        assertEquals(0.0, NeoForgePathCostExtensions.lavaProximityCost(9.0), 1e-9);
        // half-way to the lava within a radius-6 falloff: closeness=0.5, closeness^2=0.25, weight 8.0 -> 2.0
        assertEquals(2.0, NeoForgePathCostExtensions.lavaProximityCost(3.0), 1e-9);
        assertEquals(8.0, NeoForgePathCostExtensions.lavaProximityCost(0.0), 1e-9);
    }

    @Test
    void fallThroughHardExcludesPowderSnowSupportWithoutTheRealWalkCheckPassing() {
        assertEquals(Double.POSITIVE_INFINITY,
                NeoForgePathCostExtensions.fallThroughCost(true, false, false, false));
        // leather boots (or another item satisfying the real vanilla check): no exclusion.
        assertEquals(0.0, NeoForgePathCostExtensions.fallThroughCost(true, true, false, false), 1e-9);
    }

    @Test
    void fallThroughSoftCostsDescendingAScaffoldingColumnButNotWalkingAcrossItsTop() {
        assertEquals(3.0, NeoForgePathCostExtensions.fallThroughCost(false, false, true, true), 1e-9);
        assertEquals(0.0, NeoForgePathCostExtensions.fallThroughCost(false, false, true, false), 1e-9);
        assertEquals(0.0, NeoForgePathCostExtensions.fallThroughCost(false, false, false, false), 1e-9);
    }

    @Test
    void mineCostIsInfiniteWithoutProgressAndOtherwiseNormalizesRealBreakTimeToWalkedBlocks() {
        assertEquals(Double.POSITIVE_INFINITY, NeoForgePathCostExtensions.mineCost(0.0));
        assertEquals(Double.POSITIVE_INFINITY, NeoForgePathCostExtensions.mineCost(-0.1));
        // progress 1.0/tick -> breaks in exactly one tick -> ceil(1.0)/4.6
        assertEquals(1.0 / 4.6, NeoForgePathCostExtensions.mineCost(1.0), 1e-9);
        // progress 0.25/tick -> breaks in 4 ticks -> 4/4.6
        assertEquals(4.0 / 4.6, NeoForgePathCostExtensions.mineCost(0.25), 1e-9);
        // progress 0.3/tick -> ceil(1/0.3)=4 ticks -> 4/4.6
        assertEquals(4.0 / 4.6, NeoForgePathCostExtensions.mineCost(0.3), 1e-9);
    }

    @Test
    void placeCostIsInfiniteWithNothingOwnedAndOtherwisePenalizesLowQuantityMoreThanHigh() {
        assertEquals(Double.POSITIVE_INFINITY, NeoForgePathCostExtensions.placeCost(0));
        // base 1.0 + 8.0/64 = 1.125: cheap to give up one of a full stack.
        assertEquals(1.125, NeoForgePathCostExtensions.placeCost(64), 1e-9);
        // base 1.0 + 8.0/1 = 9.0: expensive to give up the very last one.
        assertEquals(9.0, NeoForgePathCostExtensions.placeCost(1), 1e-9);
        assertTrue(NeoForgePathCostExtensions.placeCost(1) > NeoForgePathCostExtensions.placeCost(64));
    }

    @Test
    void selectHighestCountPicksTheCandidateWithTheMostOwnedAndNullOnAnEmptyList() {
        var candidates = List.of(
                new NeoForgePathCostExtensions.PlacementCandidate(0, 3),
                new NeoForgePathCostExtensions.PlacementCandidate(1, 64),
                new NeoForgePathCostExtensions.PlacementCandidate(2, 12));
        var winner = NeoForgePathCostExtensions.selectHighestCount(candidates);
        assertEquals(1, winner.index());
        assertEquals(64, winner.count());

        assertNull(NeoForgePathCostExtensions.selectHighestCount(List.of()));
    }
}
