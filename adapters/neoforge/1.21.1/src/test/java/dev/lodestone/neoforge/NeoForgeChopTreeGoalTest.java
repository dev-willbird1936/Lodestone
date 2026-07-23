// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeChopTreeGoalTest {
    @Test
    void singleColumnTreeReturnsOnlyItsOwnPosition() {
        var origin = new BlockPos(0, 64, 0);
        var columns = NeoForgeChopTreeGoal.trunkColumnsAt(origin, pos -> pos.equals(origin));

        assertEquals(1, columns.size());
        assertEquals(origin, columns.get(0));
    }

    @Test
    void twoByTwoDarkOakTrunkIsDetectedRegardlessOfWhichCornerIsTheFoundLog() {
        // A real dark oak trunk: logs at (0,64,0), (1,64,0), (0,64,1), (1,64,1).
        var trunk = Set.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                new BlockPos(0, 64, 1), new BlockPos(1, 64, 1));

        for (var origin : trunk) {
            var columns = NeoForgeChopTreeGoalTest.asSet(
                    NeoForgeChopTreeGoal.trunkColumnsAt(origin, trunk::contains));
            assertEquals(trunk, columns, "every corner of the 2x2 trunk must resolve to the full square, starting from " + origin);
        }
    }

    @Test
    void isolatedSingleLogNextToUnrelatedLogsIsNotMisidentifiedAsA2x2Trunk() {
        var origin = new BlockPos(5, 64, 5);
        // Two unrelated single-column trees standing diagonally adjacent must not be fused into a
        // false 2x2 trunk: only one neighbor of the required square is actually a log.
        var otherTrees = Set.of(origin, new BlockPos(7, 64, 7));
        var columns = NeoForgeChopTreeGoal.trunkColumnsAt(origin, otherTrees::contains);

        assertEquals(1, columns.size());
        assertEquals(origin, columns.get(0));
    }

    @Test
    void logMatchesSpeciesAcceptsLogAndWoodVariantsForTheRequestedSpecies() {
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:oak_log", "oak"));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:stripped_oak_log", "oak"));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:oak_wood", "oak"));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:stripped_oak_wood", "oak"));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:DARK_OAK_LOG", "dark_oak"));
    }

    @Test
    void logMatchesSpeciesRejectsOtherSpeciesAndPrefixCollisions() {
        assertFalse(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:birch_log", "oak"));
        // "oak" must not match "dark_oak_log" as a substring false-positive.
        assertFalse(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:dark_oak_log", "oak"));
    }

    @Test
    void nullOrBlankSpeciesMatchesAnyLog() {
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:jungle_log", null));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:jungle_log", ""));
        assertTrue(NeoForgeChopTreeGoal.logMatchesSpecies("minecraft:jungle_log", "  "));
    }

    private static Set<BlockPos> asSet(java.util.List<BlockPos> positions) {
        return Set.copyOf(positions);
    }
}
