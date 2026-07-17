// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Policy and geometry contract for inferred prerequisite-resource acquisition. */
final class NeoForgePrerequisiteAcquisitionPlanner {
    enum Strategy {
        DIRECT_OBSERVED_ONLY,
        SAFE_EXPOSED_SEARCH,
        SAFE_DESCENDING_STAIRCASE
    }

    record StairStep(BlockPos fromFeet, BlockPos toFeet, BlockPos support,
                     List<BlockPos> breakTargets, boolean loaded, boolean hazardFree,
                     boolean supportSolid, boolean retreatVerified) {
    }

    record Plan(BlockPos origin, Direction direction, List<StairStep> steps) {
    }

    private NeoForgePrerequisiteAcquisitionPlanner() {
    }

    static Strategy choose(NeoForgeGoalPolicy policy, boolean exposedScopeExhausted) {
        if (policy.directObservedActionsOnly()) return Strategy.DIRECT_OBSERVED_ONLY;
        if (exposedScopeExhausted && policy.undergroundPrerequisiteAcquisitionEnabled()) {
            return Strategy.SAFE_DESCENDING_STAIRCASE;
        }
        return Strategy.SAFE_EXPOSED_SEARCH;
    }

    static boolean validHighSafetyStaircase(Plan plan) {
        if (plan == null || plan.steps().isEmpty()) return false;
        var expectedFrom = plan.origin();
        for (var step : plan.steps()) {
            if (!step.fromFeet().equals(expectedFrom) || !step.support().equals(step.toFeet().below())) return false;
            var dx = Math.abs(step.toFeet().getX() - step.fromFeet().getX());
            var dz = Math.abs(step.toFeet().getZ() - step.fromFeet().getZ());
            if (dx + dz != 1 || step.toFeet().getY() != step.fromFeet().getY() - 1) return false;
            if (!step.breakTargets().contains(step.toFeet()) || step.breakTargets().size() > 2) return false;
            if (!step.loaded() || !step.hazardFree() || !step.supportSolid() || !step.retreatVerified()) return false;
            expectedFrom = step.toFeet();
        }
        return true;
    }

    /** Resource-neutral route-material veto; discovery and harvest predicates stay separate. */
    static boolean admissibleRouteMaterial(BlockState state, float destroySpeed,
                                           boolean hazard, boolean correctTool) {
        return admissibleRouteMaterial(state.isAir(), state.getFluidState().isEmpty(), hazard,
                state.getBlock() instanceof FallingBlock, state.hasBlockEntity(), destroySpeed,
                state.requiresCorrectToolForDrops(), correctTool);
    }

    static boolean admissibleRouteMaterial(boolean air, boolean fluidEmpty, boolean hazard,
                                           boolean fallingBlock, boolean hasBlockEntity,
                                           float destroySpeed, boolean requiresCorrectTool,
                                           boolean correctTool) {
        if (air) return true;
        return fluidEmpty && !hazard && !fallingBlock && !hasBlockEntity && destroySpeed >= 0.0F
                && (!requiresCorrectTool || correctTool);
    }

    /** A block is committed to the mining budget only after an active swing produced air. */
    static int committedBreakDelta(boolean swingActive, boolean blockNowAir) {
        return swingActive && blockNowAir ? 1 : 0;
    }
}
