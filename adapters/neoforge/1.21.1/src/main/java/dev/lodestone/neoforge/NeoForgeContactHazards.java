// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/** Vanilla blocks whose contact or immediate surface effect can damage a survival player. */
final class NeoForgeContactHazards {
    private static final Set<String> DAMAGE_BLOCK_IDS = Set.of(
            "minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:powder_snow", "minecraft:sweet_berry_bush",
            "minecraft:wither_rose", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:pointed_dripstone");

    private NeoForgeContactHazards() {
    }

    static boolean isDamageBlock(Block block) {
        return isDamageBlockId(BuiltInRegistries.BLOCK.getKey(block).toString());
    }

    static boolean isDamageBlockId(String blockId) {
        return DAMAGE_BLOCK_IDS.contains(blockId);
    }
}
