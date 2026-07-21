// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricContactHazardsTest {
    @Test
    void classifiesVanillaContactDamageBlocks() {
        for (var blockId : java.util.List.of("minecraft:sweet_berry_bush", "minecraft:wither_rose",
                "minecraft:cactus", "minecraft:magma_block", "minecraft:campfire",
                "minecraft:soul_campfire", "minecraft:pointed_dripstone", "minecraft:powder_snow",
                "minecraft:fire", "minecraft:soul_fire", "minecraft:lava")) {
            assertTrue(FabricContactHazards.isDamageBlockId(blockId), blockId);
        }
        assertFalse(FabricContactHazards.isDamageBlockId("minecraft:grass_block"));
        assertFalse(FabricContactHazards.isDamageBlockId("minecraft:stone"));
    }
}
