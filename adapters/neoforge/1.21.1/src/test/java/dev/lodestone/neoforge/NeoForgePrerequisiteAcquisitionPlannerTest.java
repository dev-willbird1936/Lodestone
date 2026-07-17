// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgePrerequisiteAcquisitionPlannerTest {
    @Test
    void selectsUndergroundInferenceOnlyForAdaptiveAfterExhaustion() {
        assertEquals(NeoForgePrerequisiteAcquisitionPlanner.Strategy.DIRECT_OBSERVED_ONLY,
                choose("raw-v1", true));
        assertEquals(NeoForgePrerequisiteAcquisitionPlanner.Strategy.SAFE_EXPOSED_SEARCH,
                choose("guarded-v1", true));
        assertEquals(NeoForgePrerequisiteAcquisitionPlanner.Strategy.SAFE_EXPOSED_SEARCH,
                choose("adaptive-v1", false));
        assertEquals(NeoForgePrerequisiteAcquisitionPlanner.Strategy.SAFE_DESCENDING_STAIRCASE,
                choose("adaptive-v1", true));
    }

    @Test
    void acceptsOnlyHorizontalOneBlockSafeDescentsWithRetreat() {
        var origin = new BlockPos(0, 65, 0);
        for (var direction : Direction.Plane.HORIZONTAL) {
            var first = step(origin, origin.relative(direction).below(), true, true, true, true);
            var second = step(first.toFeet(), first.toFeet().relative(direction).below(),
                    true, true, true, true);
            assertTrue(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                    new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, direction, List.of(first, second))));
        }

        var straightDown = step(origin, origin.below(), true, true, true, true);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.DOWN, List.of(straightDown))));
        var hazardous = step(origin, new BlockPos(1, 64, 0), true, false, true, true);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.EAST, List.of(hazardous))));
        var noRetreat = step(origin, new BlockPos(1, 64, 0), true, true, true, false);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.EAST, List.of(noRetreat))));
        var unloaded = step(origin, new BlockPos(1, 64, 0), false, true, true, true);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.EAST, List.of(unloaded))));
        var unsupported = step(origin, new BlockPos(1, 64, 0), true, true, false, true);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.EAST, List.of(unsupported))));
        var discontinuous = step(new BlockPos(9, 64, 9), new BlockPos(10, 63, 9),
                true, true, true, true);
        assertFalse(NeoForgePrerequisiteAcquisitionPlanner.validHighSafetyStaircase(
                new NeoForgePrerequisiteAcquisitionPlanner.Plan(origin, Direction.EAST, List.of(discontinuous))));
    }

    @Test
    void routeMaterialAllowsGenericStableTerrainAndRejectsFluidsAndFallingBlocks() {
        assertTrue(material(false, true, false, false, false, 0.5F, false, false)); // dirt
        assertTrue(material(false, true, false, false, false, 1.5F, true, true)); // stone + pick
        assertFalse(material(false, true, false, false, false, 1.5F, true, false)); // stone, no pick
        assertFalse(material(false, false, true, false, false, 100.0F, false, false)); // lava
        assertFalse(material(false, true, false, true, false, 0.6F, false, false)); // gravel
        assertFalse(material(false, true, false, true, false, 0.5F, false, false)); // sand
    }

    @Test
    void swingCommitLatchCountsOnlyObservedBreaksCausedByActiveMining() {
        assertEquals(0, NeoForgePrerequisiteAcquisitionPlanner.committedBreakDelta(false, true));
        assertEquals(0, NeoForgePrerequisiteAcquisitionPlanner.committedBreakDelta(true, false));
        assertEquals(1, NeoForgePrerequisiteAcquisitionPlanner.committedBreakDelta(true, true));
    }

    private static NeoForgePrerequisiteAcquisitionPlanner.Strategy choose(String intelligence, boolean exhausted) {
        var policy = NeoForgeGoalPolicy.from(Map.of("intelligence", intelligence, "safety", "high"));
        return NeoForgePrerequisiteAcquisitionPlanner.choose(policy, exhausted);
    }

    private static boolean material(boolean air, boolean fluidEmpty, boolean hazard,
                                    boolean fallingBlock, boolean hasBlockEntity, float destroySpeed,
                                    boolean requiresCorrectTool, boolean correctTool) {
        return NeoForgePrerequisiteAcquisitionPlanner.admissibleRouteMaterial(air, fluidEmpty, hazard,
                fallingBlock, hasBlockEntity, destroySpeed, requiresCorrectTool, correctTool);
    }

    private static NeoForgePrerequisiteAcquisitionPlanner.StairStep step(BlockPos from, BlockPos to,
                                                                          boolean loaded, boolean hazardFree,
                                                                          boolean support, boolean retreat) {
        return new NeoForgePrerequisiteAcquisitionPlanner.StairStep(from, to, to.below(),
                List.of(to.above(), to), loaded, hazardFree, support, retreat);
    }
}
