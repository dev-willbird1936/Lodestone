// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Per-invocation cache over a caller-supplied loaded-only chunk lookup. */
final class FabricLoadedChunkCache<T> {
    private final LoadedChunkLookup<T> lookup;
    private final Map<Long, T> loaded = new HashMap<>();
    private final Set<Long> unloaded = new HashSet<>();

    FabricLoadedChunkCache(LoadedChunkLookup<T> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    T get(int blockX, int blockZ) {
        var chunkX = Math.floorDiv(blockX, 16);
        var chunkZ = Math.floorDiv(blockZ, 16);
        var key = chunkKey(chunkX, chunkZ);
        var cached = loaded.get(key);
        if (cached != null) {
            return cached;
        }
        if (unloaded.contains(key)) {
            return null;
        }
        var chunk = lookup.find(chunkX, chunkZ);
        if (chunk == null) {
            unloaded.add(key);
            return null;
        }
        loaded.put(key, chunk);
        return chunk;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xffff_ffffL) | ((long) chunkZ << 32);
    }

    @FunctionalInterface
    interface LoadedChunkLookup<T> {
        T find(int chunkX, int chunkZ);
    }
}
