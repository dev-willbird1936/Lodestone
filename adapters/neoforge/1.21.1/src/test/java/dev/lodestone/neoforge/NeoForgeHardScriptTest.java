// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeHardScriptTest {
    @Test
    void fingerprintsAreCanonicalAndScopedToPositionDimensionAndState() {
        var pos = new BlockPos(3, 64, -8);
        var stone = NeoForgeHardScript.fingerprint("minecraft:overworld", pos, "minecraft:stone", null);
        var same = NeoForgeHardScript.fingerprint("minecraft:overworld", pos, "minecraft:stone", null);
        var otherDimension = NeoForgeHardScript.fingerprint("minecraft:the_nether", pos, "minecraft:stone", null);

        assertEquals(stone, same);
        assertNotEquals(stone, otherDimension);
        assertTrue(stone.matches("^b1:[0-9a-f]{64}$"));
    }

    @Test
    void fingerprintsChangeWhenStateOrFaceChanges() {
        var pos = new BlockPos(0, 70, 0);
        var stone = NeoForgeHardScript.fingerprint("minecraft:overworld", pos, "minecraft:stone", null);
        assertNotEquals(stone, NeoForgeHardScript.fingerprint("minecraft:overworld", pos,
                "minecraft:dirt", null));
        assertNotEquals(stone, NeoForgeHardScript.fingerprint("minecraft:overworld", pos,
                "minecraft:stone", "up"));
    }
}
