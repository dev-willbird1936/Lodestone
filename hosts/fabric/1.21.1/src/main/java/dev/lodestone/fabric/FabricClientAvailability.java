// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

/** Client-thread snapshots safe to consult from gateway threads. */
final class FabricClientAvailability {
    private volatile boolean hasLevel;
    private volatile boolean hasPlayer;

    boolean update(boolean nextHasLevel, boolean nextHasPlayer) {
        var changed = hasLevel != nextHasLevel || hasPlayer != nextHasPlayer;
        hasLevel = nextHasLevel;
        hasPlayer = nextHasPlayer;
        return changed;
    }

    boolean levelCapabilityAvailable() {
        return hasLevel;
    }

    boolean playerCapabilityAvailable() {
        return hasLevel && hasPlayer;
    }
}
