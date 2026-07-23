// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import java.util.List;
import java.util.Set;

/**
 * Pure target-selection core shared by {@link NeoForgeDropCollector}: picks the nearest matching,
 * not-yet-abandoned item drop, or reports none remain. No Minecraft classes involved, so this is
 * directly unit-testable - see {@code NeoForgeDropSelectorTest}.
 */
final class NeoForgeDropSelector {
    private NeoForgeDropSelector() {
    }

    /** One observed candidate drop this tick: its live numeric entity id, namespaced item id, and
     * distance from the player. */
    record Candidate(int entityId, String itemId, double distance) {
    }

    /**
     * The nearest candidate whose {@code entityId} is not in {@code unreachableEntityIds}, or
     * {@code null} when every candidate has already been abandoned (or there are none at all) -
     * the caller's cue that collection is finished, one way or another.
     */
    static Candidate selectNext(List<Candidate> candidates, Set<Integer> unreachableEntityIds) {
        Candidate best = null;
        for (var candidate : candidates) {
            if (unreachableEntityIds.contains(candidate.entityId())) continue;
            if (best == null || candidate.distance() < best.distance()) best = candidate;
        }
        return best;
    }
}
