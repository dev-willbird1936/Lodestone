// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Fabric1201ApiMappingTest {
    @Test
    void lightEmissionIsStateAwareWithoutNeoForgeContextOverload() throws Exception {
        assertEquals(int.class, BlockState.class.getMethod("getLightEmission").getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> BlockState.class.getMethod("getLightEmission", BlockGetter.class, BlockPos.class));
    }
}
