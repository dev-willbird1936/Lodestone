// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class NeoForgeNavigationGoalTest {
    @Test
    void mineTimeoutTicksIsZeroWithoutProgressAndOtherwiseTriplesTheEstimateWithAFixedFloor() {
        assertEquals(0, NeoForgeNavigationGoal.mineTimeoutTicks(0.0));
        assertEquals(0, NeoForgeNavigationGoal.mineTimeoutTicks(-0.1));
        // progress 1.0/tick -> estimate ceil(1)=1 tick -> 1*3+40 = 43
        assertEquals(43, NeoForgeNavigationGoal.mineTimeoutTicks(1.0));
        // progress 0.25/tick -> estimate ceil(4)=4 ticks -> 4*3+40 = 52
        assertEquals(52, NeoForgeNavigationGoal.mineTimeoutTicks(0.25));
        // progress 0.3/tick -> ceil(1/0.3)=4 ticks -> 4*3+40 = 52
        assertEquals(52, NeoForgeNavigationGoal.mineTimeoutTicks(0.3));
    }

    @Test
    void nearestReachablePointPicksTheClosestByRealThreeDimensionalDistanceAndNullOnAnEmptySet() {
        var target = new BlockPos(10, 64, 10);
        var near = new BlockPos(8, 64, 8);
        var far = new BlockPos(0, 64, 0);
        assertEquals(near, NeoForgeNavigationGoal.nearestReachablePoint(target, List.of(far, near)));
        assertEquals(near, NeoForgeNavigationGoal.nearestReachablePoint(target, List.of(near)));
        assertNull(NeoForgeNavigationGoal.nearestReachablePoint(target, List.of()));
    }

    @Test
    void nearestReachablePointWeighsVerticalDistanceLikeAnyOtherAxis() {
        var target = new BlockPos(0, 64, 0);
        // closer horizontally but far vertically vs. farther horizontally but level - the second
        // is nearer in real 3D distance (5^2=25 vs sqrt(3^2+10^2)^2=109).
        var horizontalOnly = new BlockPos(5, 64, 0);
        var verticalFar = new BlockPos(3, 74, 0);
        assertEquals(horizontalOnly,
                NeoForgeNavigationGoal.nearestReachablePoint(target, List.of(verticalFar, horizontalOnly)));
    }
}
