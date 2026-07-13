// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricLegacyWorldApiMappingTest {
    @Test
    void exposesLoadedOnlyCovariantLevelChunkLookup() {
        assertTrue(Arrays.stream(ClientChunkCache.class.getDeclaredMethods())
                .filter(method -> !method.isBridge())
                .anyMatch(method -> method.getName().equals("getChunk")
                        && method.getReturnType().equals(LevelChunk.class)
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{
                        int.class, int.class, ChunkStatus.class, boolean.class})),
                "client cache must expose getChunk(x,z,status,load) returning LevelChunk");
    }

    @Test
    void lightEmissionIsStateAwareWithoutContextOverload() throws Exception {
        assertEquals(int.class, BlockState.class.getMethod("getLightEmission").getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> BlockState.class.getMethod("getLightEmission", BlockGetter.class, BlockPos.class));
    }
}
