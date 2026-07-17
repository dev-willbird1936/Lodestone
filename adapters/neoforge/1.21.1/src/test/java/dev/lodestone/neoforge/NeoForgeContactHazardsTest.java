// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeContactHazardsTest {
    @Test
    void classifiesVanillaContactDamageBlocks() {
        for (var blockId : java.util.List.of("minecraft:sweet_berry_bush", "minecraft:wither_rose",
                "minecraft:cactus", "minecraft:magma_block", "minecraft:campfire",
                "minecraft:soul_campfire", "minecraft:pointed_dripstone", "minecraft:powder_snow",
                "minecraft:fire", "minecraft:soul_fire", "minecraft:lava")) {
            assertTrue(NeoForgeContactHazards.isDamageBlockId(blockId), blockId);
        }
        assertFalse(NeoForgeContactHazards.isDamageBlockId("minecraft:grass_block"));
        assertFalse(NeoForgeContactHazards.isDamageBlockId("minecraft:stone"));
    }
}
