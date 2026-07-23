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

    @Test
    void placementSucceedsWhenDestinationBecomesThePlacedItemBlockPreDispatch() {
        // Live-observed false failure: the destination started as a replaceable plant (grass) and
        // the placement landed before `dispatched` was ever observed true. The destination now
        // matches the placed item's block form, so this must complete successfully regardless of
        // whether the fingerprint also changed.
        var decision = NeoForgeHardScript.placeDecision(true, true);
        assertEquals(NeoForgeHardScript.PlaceDecision.SUCCESS, decision);

        // Matching the placed block always wins, even if the fingerprint happens to read unchanged.
        assertEquals(NeoForgeHardScript.PlaceDecision.SUCCESS,
                NeoForgeHardScript.placeDecision(true, false));
    }

    @Test
    void placementFailsWithTargetChangedOnlyWhenDestinationBecomesSomethingElse() {
        // Destination changed away from its pre-dispatch snapshot into a block that is not the
        // placed item's block form: something else (grief, decay, a neighbour update) claimed it.
        var decision = NeoForgeHardScript.placeDecision(false, true);
        assertEquals(NeoForgeHardScript.PlaceDecision.TARGET_CHANGED, decision);
    }

    @Test
    void placementContinuesWhileDestinationStillMatchesItsPreDispatchSnapshot() {
        var decision = NeoForgeHardScript.placeDecision(false, false);
        assertEquals(NeoForgeHardScript.PlaceDecision.CONTINUE, decision);
    }
}
