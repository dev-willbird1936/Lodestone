// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Regression coverage for the live-caught "dancing" bug: the walker used to aim at the current
     * path node's own block Y (feet level) every tick, which - from an eye position roughly 1.6
     * blocks higher, at close range - produced a steep down-pitch that never settled. The fix aims
     * at the player's own eye height instead, so the aim target's Y never depends on the node's Y.
     */
    @Test
    void eyeLevelAimTargetAlwaysUsesTheEyeHeightRegardlessOfTheNodesOwnY() {
        var groundLevelNode = new BlockPos(10, 64, -3);
        var elevatedNode = new BlockPos(10, 90, -3);

        var fromGround = NeoForgeGotoMovement.eyeLevelAimTarget(groundLevelNode, 72.62);
        var fromElevated = NeoForgeGotoMovement.eyeLevelAimTarget(elevatedNode, 72.62);

        assertEquals(72.62, fromGround.y, 1e-9);
        assertEquals(72.62, fromElevated.y, 1e-9, "aim Y must never track the node's own Y");
        assertEquals(10.5, fromGround.x, 1e-9);
        assertEquals(-2.5, fromGround.z, 1e-9);
    }

    /** End-to-end composition of {@link NeoForgeGotoMovement#eyeLevelAimTarget} and {@link
     * NeoForgeGotoMovement#computeLookAngles}: walking toward a node below, level with, or above the
     * player must always compute a near-level pitch, since the aim target is always at eye height. */
    @Test
    void walkingAimStaysLevelRegardlessOfTheNodesElevation() {
        var eye = new Vec3(0.0, 72.62, 0.0);
        for (var nodeY : new int[]{50, 70, 71, 72, 100}) {
            var node = new BlockPos(5, nodeY, 0);
            var aim = NeoForgeGotoMovement.eyeLevelAimTarget(node, eye.y);
            var angles = NeoForgeGotoMovement.computeLookAngles(eye, aim);
            assertEquals(0.0F, angles.pitch(), 1e-6, "pitch must stay level for node Y " + nodeY);
        }
    }

    @Test
    void reaimHysteresisIgnoresErrorsAtOrBelowTenDegrees() {
        assertFalse(NeoForgeGotoMovement.yawNeedsReaim(0.0F, 10.0F));
        assertFalse(NeoForgeGotoMovement.yawNeedsReaim(0.0F, -10.0F));
        assertFalse(NeoForgeGotoMovement.yawNeedsReaim(45.0F, 45.0F));
        assertTrue(NeoForgeGotoMovement.yawNeedsReaim(0.0F, 10.01F));
        assertTrue(NeoForgeGotoMovement.yawNeedsReaim(0.0F, 90.0F));
    }

    @Test
    void reaimHysteresisWrapsAroundTheYawSeamInsteadOfSeeingA350DegreeError() {
        // 175 -> -175 is really only a 10-degree turn the short way around, not 350.
        assertFalse(NeoForgeGotoMovement.yawNeedsReaim(175.0F, -175.0F));
        assertTrue(NeoForgeGotoMovement.yawNeedsReaim(175.0F, -164.0F));
    }

    @Test
    void nodeConsumedNeedsALooseHorizontalRadiusAndOnlyOneStepHeightVertically() {
        assertTrue(NeoForgeGotoMovement.nodeConsumed(0.0, 0.0));
        assertTrue(NeoForgeGotoMovement.nodeConsumed(0.69, 1.0));
        // The horizontal bound is strict (<), not inclusive - matches the "< 0.7" spec exactly.
        assertFalse(NeoForgeGotoMovement.nodeConsumed(0.7, 0.0));
        assertFalse(NeoForgeGotoMovement.nodeConsumed(0.5, 1.01));
        // Never demands exact-cell occupancy: comfortably inside the loose radius still counts,
        // it doesn't need to be dead center.
        assertTrue(NeoForgeGotoMovement.nodeConsumed(0.3, 0.5));
    }

    @Test
    void sprintOnlyKicksInForRoutesLongerThanSixBlocks() {
        assertFalse(NeoForgeGotoMovement.shouldSprint(6.0));
        assertFalse(NeoForgeGotoMovement.shouldSprint(2.0));
        assertTrue(NeoForgeGotoMovement.shouldSprint(6.01));
        assertTrue(NeoForgeGotoMovement.shouldSprint(20.0));
    }

    /**
     * Regression coverage for the live-caught bug where the old stuck handler did nothing for 45
     * ticks and then silently forced a bare replan forever (looking, combined with the aiming bug,
     * like the player "circles/spins at the node"). The new ladder is a strict, one-shot escalation:
     * two bounded jump-assists, then exactly one replan, then an honest give-up - never repeating.
     */
    @Test
    void stuckActionEscalatesThroughJumpAssistsThenOneReplanThenGivesUpHonestly() {
        var stuckTicksBeforeJumpAssist = 15;
        var maxJumpAssists = 2;

        // Not stuck long enough yet: keep walking normally.
        assertEquals(NeoForgeGotoMovement.StuckAction.CONTINUE,
                NeoForgeGotoMovement.nextStuckAction(0, 0, false, stuckTicksBeforeJumpAssist, maxJumpAssists));
        assertEquals(NeoForgeGotoMovement.StuckAction.CONTINUE,
                NeoForgeGotoMovement.nextStuckAction(14, 0, false, stuckTicksBeforeJumpAssist, maxJumpAssists));

        // First and second stuck episodes: bounded jump-assists, never a yaw-scan/spin action.
        assertEquals(NeoForgeGotoMovement.StuckAction.JUMP_ASSIST,
                NeoForgeGotoMovement.nextStuckAction(15, 0, false, stuckTicksBeforeJumpAssist, maxJumpAssists));
        assertEquals(NeoForgeGotoMovement.StuckAction.JUMP_ASSIST,
                NeoForgeGotoMovement.nextStuckAction(15, 1, false, stuckTicksBeforeJumpAssist, maxJumpAssists));

        // Both jump-assists spent, no replan used yet: exactly one replan.
        assertEquals(NeoForgeGotoMovement.StuckAction.REPLAN,
                NeoForgeGotoMovement.nextStuckAction(15, 2, false, stuckTicksBeforeJumpAssist, maxJumpAssists));

        // Still stuck after the one allowed replan: give up honestly, never loop back to jumping
        // or replanning again.
        assertEquals(NeoForgeGotoMovement.StuckAction.GIVE_UP,
                NeoForgeGotoMovement.nextStuckAction(15, 2, true, stuckTicksBeforeJumpAssist, maxJumpAssists));
        assertEquals(NeoForgeGotoMovement.StuckAction.GIVE_UP,
                NeoForgeGotoMovement.nextStuckAction(15, 0, true, stuckTicksBeforeJumpAssist, maxJumpAssists));
    }

    /**
     * Regression coverage for the live-caught "grinds in place until timeout" bug: the old formula
     * tied its vertical tolerance to {@code arriveRadius} itself ({@code Math.max(2, ceil(radius))})
     * instead of to the real source of vertical error - a heightmap-derived {@code targetY} commonly
     * off by 1-2 blocks. The live repro was {@code arriveRadius=2} with the player standing beside
     * the target column at roughly horizontal 1.9-2.0 and {@code dy=2}; for that exact radius the old
     * formula happened to also tolerate {@code dy<=2}, so this test also exercises the case the old
     * per-invocation search couldn't reach in the first place (see {@link
     * NeoForgeSafePathPlanner.ArrivalSpec}'s own use in {@code replan}).
     */
    @Test
    void arrivalUsesHorizontalDistanceWithALenientFixedVerticalTolerance() {
        // Exactly the live case: horizontal ~1.9, dy 2, radius 2 -> arrived.
        assertTrue(NeoForgeGotoMovement.arrivalReached(1.9, 2.0, 2.0));
        // Horizontal alone exceeds the radius: never arrived, regardless of how forgiving dy is.
        assertFalse(NeoForgeGotoMovement.arrivalReached(2.6, 0.0, 2.0));
        // The exact live boundary: horizontal ~2, dy ~2, radius 2 -> arrived (inclusive bounds).
        assertTrue(NeoForgeGotoMovement.arrivalReached(2.0, 2.0, 2.0));
        // Vertical tolerance is fixed at 2 blocks regardless of arriveRadius - a larger radius must
        // not also widen how far off the target's Y is allowed to be.
        assertFalse(NeoForgeGotoMovement.arrivalReached(1.0, 2.01, 2.0));
        assertFalse(NeoForgeGotoMovement.arrivalReached(1.0, 3.0, 8.0));
    }
}
