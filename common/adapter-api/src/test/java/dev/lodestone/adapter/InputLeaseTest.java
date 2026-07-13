// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InputLeaseTest {
    @Test
    void expiryReleasesOnlyOwnedBindings() {
        var lease = new InputLease();
        lease.replace(Set.of("forward", "sprint"), 1_000, 500);
        assertTrue(lease.releaseExpired(1_499).isEmpty());
        assertEquals(Set.of("forward", "sprint"), lease.releaseExpired(1_500));
        assertTrue(lease.owned().isEmpty());
    }

    @Test
    void replacementSupersedesOldExpiry() {
        var lease = new InputLease();
        lease.replace(Set.of("forward"), 1_000, 100);
        lease.replace(Set.of("left"), 1_050, 500);
        assertTrue(lease.releaseExpired(1_100).isEmpty());
        assertEquals(Set.of("left"), lease.releaseExpired(1_550));
    }
}
