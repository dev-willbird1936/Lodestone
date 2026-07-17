// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeSafePathPlannerTest {
    @Test
    void cornerClearRequiresOnlyOneFlankUnderLowOrBalancedSafetyButBothUnderHighSafety() {
        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, false, false));
        assertTrue(NeoForgeSafePathPlanner.cornerClear(false, true, false));
        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, true, false));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, false, false));

        assertTrue(NeoForgeSafePathPlanner.cornerClear(true, true, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(true, false, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, true, true));
        assertFalse(NeoForgeSafePathPlanner.cornerClear(false, false, true));
    }

    @Test
    void diagonalEdgesCostSqrtTwoInsteadOfOneHorizontally() {
        var policy = policy("guarded-v1", "balanced");
        var origin = new BlockPos(0, 64, 0);
        var cardinal = origin.relative(Direction.NORTH);
        var diagonal = origin.relative(Direction.NORTH).relative(Direction.EAST);

        assertEquals(1.0, NeoForgeSafePathPlanner.edgeCost(origin, cardinal, policy), 1e-9);
        assertEquals(Math.sqrt(2.0), NeoForgeSafePathPlanner.edgeCost(origin, diagonal, policy), 1e-9);
    }

    @Test
    void descentPenaltiesStackWithSafetyAndIntelligenceExactlyAsBefore() {
        var origin = new BlockPos(0, 64, 0);
        var below = origin.below();

        // balanced + guarded: base 1.0 + 1 vertical * 1.5 (not high-safety) + 1.0 balanced-descent
        assertEquals(3.5, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "balanced")), 1e-9);
        // high safety + guarded: base 1.0 + 1 vertical * 4.0 (high-safety), no balanced/adaptive add-on
        assertEquals(5.0, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("guarded-v1", "high")), 1e-9);
        // low safety + adaptive: base 1.0 + 1 vertical * 1.5 (not high-safety) + 12.0 adaptive-descent
        assertEquals(14.5, NeoForgeSafePathPlanner.edgeCost(origin, below, policy("adaptive-v1", "low")), 1e-9);
    }

    private static NeoForgeGoalPolicy policy(String intelligence, String safety) {
        return NeoForgeGoalPolicy.from(Map.of("intelligence", intelligence, "safety", safety));
    }
}
