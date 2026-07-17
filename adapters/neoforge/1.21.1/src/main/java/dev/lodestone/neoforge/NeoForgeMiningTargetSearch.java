// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, resumable target validation with revision-aware rejection caching. */
final class NeoForgeMiningTargetSearch {
    private static final int MAX_CACHE_ENTRIES = 2_048;

    private final LinkedHashMap<CandidateKey, String> rejectionCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CandidateKey, String> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private List<BlockPos> candidates = List.of();
    private int cursor;
    private int enumerated;
    private int prefilterRejected;
    private int validated;
    private int cacheHits;
    private final LinkedHashMap<String, Integer> rejectionHistogram = new LinkedHashMap<>();

    void begin(List<BlockPos> candidates) {
        this.candidates = List.copyOf(candidates);
        cursor = 0;
        enumerated = 0;
        prefilterRejected = 0;
        validated = 0;
        cacheHits = 0;
        rejectionHistogram.clear();
    }

    BatchResult poll(Validator validator, int workBudget) {
        if (workBudget <= 0) throw new IllegalArgumentException("workBudget must be positive");
        var work = 0;
        while (cursor < candidates.size() && work++ < workBudget) {
            var candidate = candidates.get(cursor++);
            enumerated++;
            var key = new CandidateKey(candidate.asLong(), validator.worldRevision(candidate),
                    validator.poseRevision());
            var cached = rejectionCache.get(key);
            if (cached != null) {
                cacheHits++;
                reject(cached);
                continue;
            }
            var prefilter = validator.prefilter(candidate);
            if (prefilter != null) {
                prefilterRejected++;
                rejectionCache.put(key, prefilter);
                reject(prefilter);
                continue;
            }
            validated++;
            var validation = validator.validate(candidate);
            if (validation.vantage() != null) {
                return result(Outcome.SAFE_TARGET, candidate, validation.vantage());
            }
            var rejection = validation.rejection() == null ? "EXACT_SAFETY_VETO" : validation.rejection();
            rejectionCache.put(key, rejection);
            reject(rejection);
        }
        return result(cursor < candidates.size() ? Outcome.SEARCH_INCOMPLETE : Outcome.LOCAL_SCOPE_EXHAUSTED,
                null, null);
    }

    boolean active() {
        return !candidates.isEmpty() && cursor < candidates.size();
    }

    int candidateCount() {
        return candidates.size();
    }

    private void reject(String reason) {
        rejectionHistogram.merge(reason, 1, Integer::sum);
    }

    private BatchResult result(Outcome outcome, BlockPos target, BlockPos vantage) {
        return new BatchResult(outcome, target, vantage, cursor, candidates.size(), enumerated,
                prefilterRejected, validated, cacheHits, Map.copyOf(rejectionHistogram));
    }

    enum Outcome {
        SAFE_TARGET,
        SEARCH_INCOMPLETE,
        LOCAL_SCOPE_EXHAUSTED
    }

    interface Validator {
        long worldRevision(BlockPos candidate);

        long poseRevision();

        /** Returns a stable rejection reason, or null when exact validation should run. */
        String prefilter(BlockPos candidate);

        Validation validate(BlockPos candidate);
    }

    record Validation(BlockPos vantage, String rejection) {
        static Validation safe(BlockPos vantage) {
            return new Validation(vantage.immutable(), null);
        }

        static Validation rejected(String reason) {
            return new Validation(null, reason);
        }
    }

    record BatchResult(Outcome outcome, BlockPos target, BlockPos vantage, int cursor, int candidateCount,
                       int enumerated, int prefilterRejected, int validated, int cacheHits,
                       Map<String, Integer> rejectionHistogram) {
    }

    private record CandidateKey(long candidate, long worldRevision, long poseRevision) {
    }
}
