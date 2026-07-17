// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeAcquisitionScopeTest {
    @Test
    void persistsLocalScopeAndAttemptedTargetsAcrossCountFlicker() {
        var scope = NeoForgeAcquisitionScope.mergeExhausted("minecraft:overworld",
                new BlockPos(0, 80, 0), 32, "logs", Set.of(11L),
                Set.of(new BlockPos(1, 80, 1), new BlockPos(2, 80, 2)), null);

        assertTrue(scope.blocksCandidate("minecraft:overworld", new BlockPos(8, 80, 7), "logs"));
        assertTrue(scope.attemptedTargets().contains(11L));
        assertTrue(scope.attemptedTargets().contains(new BlockPos(1, 80, 1).asLong()));
        assertTrue(scope.attemptedTargets().contains(new BlockPos(2, 80, 2).asLong()));
        assertFalse(scope.blocksCandidate("minecraft:the_nether", new BlockPos(8, 80, 7), "logs"));
    }

    @Test
    void rejectedSourceMergesTargetsIntoAnExistingLocalScope() {
        var first = NeoForgeAcquisitionScope.mergeExhausted("minecraft:overworld",
                new BlockPos(0, 80, 0), 32, "logs", Set.of(11L),
                Set.of(new BlockPos(1, 80, 1)), null);
        var merged = NeoForgeAcquisitionScope.mergeExhausted("minecraft:overworld",
                new BlockPos(4, 80, 4), 32, "logs", Set.of(26L),
                Set.of(new BlockPos(5, 80, 5)), first);

        assertTrue(merged.attemptedTargets().contains(11L));
        assertTrue(merged.attemptedTargets().contains(26L));
        assertTrue(merged.blocksCandidate("minecraft:overworld", new BlockPos(8, 80, 7), "logs"));
    }

    @Test
    void measuredExpansionIsRequiredBeforeReenteringSameResourceKind() {
        var scope = new NeoForgeAcquisitionScope.Scope("minecraft:overworld",
                new BlockPos(0, 80, 0), 32, "logs", Set.of());

        assertFalse(scope.measuredExpansionBeyond("minecraft:overworld", new BlockPos(40, 80, 0), "logs"));
        assertTrue(scope.measuredExpansionBeyond("minecraft:overworld", new BlockPos(41, 80, 0), "logs"));
    }

    @Test
    void noFrontierRecoveryHasABoundedBudget() {
        assertFalse(NeoForgeAcquisitionScope.boundedNoFrontier(3));
        assertTrue(NeoForgeAcquisitionScope.boundedNoFrontier(4));
    }
}
