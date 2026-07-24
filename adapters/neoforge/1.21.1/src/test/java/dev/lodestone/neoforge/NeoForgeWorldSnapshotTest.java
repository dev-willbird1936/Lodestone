// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the live-caught bug where a player standing/wading in shallow water (a
 * pond, a stream edge) saw {@code minecraft.goal.move.goto} and
 * {@code minecraft.goal.gather.collect-drops} fail with {@code no-route}/zero movement on tick one:
 * both {@link NeoForgeWorldSnapshot#walkable} and {@link NeoForgeWorldSnapshot#originStandable}
 * required the feet cell to be fully dry, so a wet origin - and every equally-wet neighbor cell
 * around it - was rejected outright, leaving the search with no admissible graph cell to even start
 * from. See {@link NeoForgeWorldSnapshot#wadableFluid}'s own doc for the fix.
 */
final class NeoForgeWorldSnapshotTest {
    @Test
    void dryFeetAndDryHeadAreAlwaysWadable() {
        assertTrue(NeoForgeWorldSnapshot.wadableFluid(true, true));
    }

    @Test
    void shallowWaterAtTheFeetIsWadableAsLongAsTheHeadStaysDry() {
        // waterOrEmpty(feet) is true for both plain air and water - this is the wading case.
        assertTrue(NeoForgeWorldSnapshot.wadableFluid(true, true));
    }

    @Test
    void aSubmergedHeadIsSwimmingNotWalkingAndNeverAdmissible() {
        assertFalse(NeoForgeWorldSnapshot.wadableFluid(true, false));
    }

    @Test
    void anyNonWaterFluidAtTheFeetLikeLavaIsNeverAdmissibleRegardlessOfTheHead() {
        assertFalse(NeoForgeWorldSnapshot.wadableFluid(false, true));
        assertFalse(NeoForgeWorldSnapshot.wadableFluid(false, false));
    }
}
