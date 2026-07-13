// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class FabricLoadedChunkCacheTest {
    @Test
    void resolvesFloorDivChunkCoordinatesAndCachesLoadedChunks() {
        var calls = new ArrayList<String>();
        var loaded = new Object();
        var cache = new FabricLoadedChunkCache<>((chunkX, chunkZ) -> {
            calls.add(chunkX + ":" + chunkZ);
            return loaded;
        });

        assertSame(loaded, cache.get(-1, -1));
        assertSame(loaded, cache.get(-16, -16));
        assertEquals(java.util.List.of("-1:-1"), calls);
    }

    @Test
    void negativeCachesMissingChunksWithoutConsultingFallbackState() {
        var calls = new ArrayList<String>();
        var cache = new FabricLoadedChunkCache<Object>((chunkX, chunkZ) -> {
            calls.add(chunkX + ":" + chunkZ);
            return null;
        });

        assertNull(cache.get(31, 47));
        assertNull(cache.get(16, 32));
        assertEquals(java.util.List.of("1:2"), calls);
    }

    @Test
    void keepsDistinctLoadedAndMissingChunkKeys() {
        var loaded = new Object();
        var calls = new ArrayList<String>();
        var available = Map.of("0:0", loaded);
        var cache = new FabricLoadedChunkCache<>((chunkX, chunkZ) -> {
            var key = chunkX + ":" + chunkZ;
            calls.add(key);
            return available.get(key);
        });

        assertSame(loaded, cache.get(0, 0));
        assertNull(cache.get(16, 0));
        assertSame(loaded, cache.get(15, 15));
        assertNull(cache.get(31, 15));
        assertEquals(java.util.List.of("0:0", "1:0"), calls);
    }
}
