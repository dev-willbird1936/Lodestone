// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeMiningTargetSearchTest {
    @Test
    void batchesWorkAndReturnsTriStateOutcome() {
        var search = new NeoForgeMiningTargetSearch();
        var candidates = List.of(new BlockPos(1, 2, 3), new BlockPos(2, 2, 3), new BlockPos(3, 2, 3));
        search.begin(candidates);
        var validator = validator(10, 20, candidates.getLast());

        assertEquals(NeoForgeMiningTargetSearch.Outcome.SEARCH_INCOMPLETE,
                search.poll(validator, 2).outcome());
        var result = search.poll(validator, 2);
        assertEquals(NeoForgeMiningTargetSearch.Outcome.SAFE_TARGET, result.outcome());
        assertEquals(candidates.getLast(), result.target());
        assertEquals(3, result.enumerated());
    }

    @Test
    void unchangedRejectedCandidateIsNotValidatedTwice() {
        var search = new NeoForgeMiningTargetSearch();
        var candidate = new BlockPos(1, 2, 3);
        var validations = new int[1];
        var validator = new NeoForgeMiningTargetSearch.Validator() {
            @Override public long worldRevision(BlockPos ignored) { return 10; }
            @Override public long poseRevision() { return 20; }
            @Override public String prefilter(BlockPos ignored) { return null; }
            @Override public NeoForgeMiningTargetSearch.Validation validate(BlockPos ignored) {
                validations[0]++;
                return NeoForgeMiningTargetSearch.Validation.rejected("NO_SAFE_VANTAGE");
            }
        };

        search.begin(List.of(candidate));
        assertEquals(NeoForgeMiningTargetSearch.Outcome.LOCAL_SCOPE_EXHAUSTED,
                search.poll(validator, 1).outcome());
        search.begin(List.of(candidate));
        var cached = search.poll(validator, 1);
        assertEquals(1, validations[0]);
        assertEquals(1, cached.cacheHits());
    }

    @Test
    void poseOrWorldRevisionInvalidatesRejection() {
        var search = new NeoForgeMiningTargetSearch();
        var candidate = new BlockPos(1, 2, 3);
        var revisions = new long[]{10, 20};
        var validations = new int[1];
        var validator = new NeoForgeMiningTargetSearch.Validator() {
            @Override public long worldRevision(BlockPos ignored) { return revisions[0]; }
            @Override public long poseRevision() { return revisions[1]; }
            @Override public String prefilter(BlockPos ignored) { return null; }
            @Override public NeoForgeMiningTargetSearch.Validation validate(BlockPos ignored) {
                validations[0]++;
                return NeoForgeMiningTargetSearch.Validation.rejected("NO_SAFE_VANTAGE");
            }
        };

        search.begin(List.of(candidate));
        search.poll(validator, 1);
        revisions[1]++;
        search.begin(List.of(candidate));
        search.poll(validator, 1);
        revisions[0]++;
        search.begin(List.of(candidate));
        search.poll(validator, 1);
        assertEquals(3, validations[0]);
    }

    private static NeoForgeMiningTargetSearch.Validator validator(long world, long pose, BlockPos safe) {
        return new NeoForgeMiningTargetSearch.Validator() {
            @Override public long worldRevision(BlockPos ignored) { return world; }
            @Override public long poseRevision() { return pose; }
            @Override public String prefilter(BlockPos ignored) { return null; }
            @Override public NeoForgeMiningTargetSearch.Validation validate(BlockPos candidate) {
                return candidate.equals(safe)
                        ? NeoForgeMiningTargetSearch.Validation.safe(candidate.above())
                        : NeoForgeMiningTargetSearch.Validation.rejected("NO_SAFE_VANTAGE");
            }
        };
    }
}
