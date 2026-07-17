// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/** Keeps cursor movement separate from truthful hand-mined block accounting. */
final class NeoForgeMiningAccounting {
    record Progress(int nextMineIndex, int handMinedLogs, boolean blockBroken, String reason) {
    }

    private NeoForgeMiningAccounting() {
    }

    static Progress blockBecameAir(int mineIndex, int handMinedLogs) {
        return new Progress(mineIndex + 1, handMinedLogs + 1, true, "block-broken-by-hand");
    }

    static Progress skippedTarget(int mineIndex, int handMinedLogs, String reason) {
        return new Progress(mineIndex + 1, handMinedLogs, false, reason);
    }
}
