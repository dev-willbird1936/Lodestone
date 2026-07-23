// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class NeoForgeDropSelectorTest {
    @Test
    void selectsTheNearestCandidateAmongSeveral() {
        var candidates = List.of(
                new NeoForgeDropSelector.Candidate(1, "minecraft:oak_log", 8.0),
                new NeoForgeDropSelector.Candidate(2, "minecraft:oak_log", 2.5),
                new NeoForgeDropSelector.Candidate(3, "minecraft:stick", 5.0));

        var next = NeoForgeDropSelector.selectNext(candidates, Set.of());

        assertEquals(2, next.entityId());
        assertEquals(2.5, next.distance());
    }

    @Test
    void skipsCandidatesAlreadyMarkedUnreachable() {
        var candidates = List.of(
                new NeoForgeDropSelector.Candidate(1, "minecraft:oak_log", 2.0),
                new NeoForgeDropSelector.Candidate(2, "minecraft:oak_log", 6.0));

        var next = NeoForgeDropSelector.selectNext(candidates, Set.of(1));

        assertEquals(2, next.entityId());
    }

    @Test
    void returnsNullWhenNoCandidatesRemainOrEveryCandidateIsUnreachable() {
        assertNull(NeoForgeDropSelector.selectNext(List.of(), Set.of()));

        var candidates = List.of(new NeoForgeDropSelector.Candidate(1, "minecraft:oak_log", 2.0));
        assertNull(NeoForgeDropSelector.selectNext(candidates, Set.of(1)));
    }
}
