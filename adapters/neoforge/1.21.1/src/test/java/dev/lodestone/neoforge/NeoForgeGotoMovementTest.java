// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeGotoMovementTest {
    @Test
    void everyLeafVariantIsSoftFoliageRegardlessOfWoodSpecies() {
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:oak_leaves"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:dark_oak_leaves"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:azalea_leaves"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:cherry_leaves"));
    }

    @Test
    void namedGrassesFernsVinesAndFlowersAreSoftFoliage() {
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:short_grass"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:tall_grass"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:fern"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:large_fern"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:vine"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:dandelion"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:poppy"));
        assertTrue(NeoForgeGotoMovement.isSoftFoliageBlockId("MINECRAFT:POPPY"), "matching must be case-insensitive");
    }

    @Test
    void ordinaryTerrainAndUnrelatedBlocksAreNotSoftFoliage() {
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:stone"));
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:dirt"));
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:oak_log"));
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId("minecraft:oak_planks"));
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId(null));
        assertFalse(NeoForgeGotoMovement.isSoftFoliageBlockId(""));
    }

    @Test
    void lateralDetourCandidatesAreEmptyWhenFromAndTargetShareAHorizontalPosition() {
        var from = new BlockPos(0, 64, 0);
        var target = new BlockPos(0, 70, 0);
        assertTrue(NeoForgeGotoMovement.lateralDetourCandidates(from, target, 4).isEmpty());
    }

    @Test
    void lateralDetourCandidatesAreSymmetricAndPerpendicularToTheDirectLine() {
        var from = new BlockPos(0, 64, 0);
        var target = new BlockPos(10, 64, 0);
        var candidates = NeoForgeGotoMovement.lateralDetourCandidates(from, target, 4);

        assertEquals(2, candidates.size());
        var left = candidates.get(0);
        var right = candidates.get(1);
        // The direct line runs along +X, so the perpendicular offset is along Z.
        assertEquals(5, left.getX());
        assertEquals(5, right.getX());
        assertEquals(4, left.getZ());
        assertEquals(-4, right.getZ());
        assertEquals(64, left.getY());
    }

    @Test
    void lateralDetourCandidatesScaleWithTheRequestedOffset() {
        var from = new BlockPos(0, 64, 0);
        var target = new BlockPos(0, 64, 10);
        var candidates = NeoForgeGotoMovement.lateralDetourCandidates(from, target, 6);

        assertEquals(2, candidates.size());
        // The direct line runs along +Z, so the perpendicular offset is along X.
        assertTrue(candidates.stream().anyMatch(pos -> pos.getX() == 6));
        assertTrue(candidates.stream().anyMatch(pos -> pos.getX() == -6));
    }
}
