// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class NeoForgeStarterResourceCatalogTest {
    @Test
    void includesStructuralHouseLogsAsEquivalentFallback() {
        var natural = List.of(new BlockPos(2, 64, 2), new BlockPos(2, 65, 2), new BlockPos(2, 66, 2));
        var house = List.of(new BlockPos(8, 64, 8), new BlockPos(9, 64, 8),
                new BlockPos(10, 64, 8), new BlockPos(10, 65, 8));

        var sources = NeoForgeStarterResourceCatalog.group(
                java.util.stream.Stream.concat(natural.stream(), house.stream()).toList(),
                new BlockPos(0, 64, 0), new HashSet<>());

        assertEquals(2, sources.size());
        assertEquals(NeoForgeStarterResourceCatalog.Provenance.NATURAL_TRUNK, sources.getFirst().provenance());
        assertEquals(NeoForgeStarterResourceCatalog.Provenance.STRUCTURAL_LOGS, sources.getLast().provenance());
    }

    @Test
    void rejectedNaturalSourceLeavesHouseLogsAvailable() {
        var natural = List.of(new BlockPos(2, 64, 2), new BlockPos(2, 65, 2), new BlockPos(2, 66, 2));
        var house = List.of(new BlockPos(8, 64, 8), new BlockPos(9, 64, 8),
                new BlockPos(10, 64, 8));
        var rejected = new HashSet<BlockPos>();
        rejected.add(natural.getFirst());

        var sources = NeoForgeStarterResourceCatalog.group(
                java.util.stream.Stream.concat(natural.stream(), house.stream()).toList(),
                new BlockPos(0, 64, 0), rejected);

        assertFalse(sources.isEmpty());
        assertEquals(NeoForgeStarterResourceCatalog.Provenance.STRUCTURAL_LOGS, sources.getFirst().provenance());
    }
}
