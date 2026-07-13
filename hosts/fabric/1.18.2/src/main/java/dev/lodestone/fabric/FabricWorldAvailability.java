// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

/** Tick-cached client-level presence safe for off-thread capability negotiation. */
final class FabricWorldAvailability {
    private volatile boolean available;

    boolean available() {
        return available;
    }

    boolean update(boolean next) {
        var changed = available != next;
        available = next;
        return changed;
    }
}
