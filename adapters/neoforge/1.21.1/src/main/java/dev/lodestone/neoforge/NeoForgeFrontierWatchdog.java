// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/** Bounded liveness-recovery epoch; ordinary goal liveness is paused while this is active. */
final class NeoForgeFrontierWatchdog {
    static final int NO_PROGRESS_TICKS = 90;
    static final int MAX_TOTAL_TICKS = 180;
    static final int MAX_FAILURES = 3;
    private static final double MIN_DISTANCE_IMPROVEMENT = 0.25;

    private long frontierId;
    private double bestDistance;
    private int noProgressTicks;
    private int totalTicks;
    private int failures;
    private boolean active;

    void begin(long id, double distance) {
        frontierId = id;
        bestDistance = distance;
        noProgressTicks = 0;
        totalTicks = 0;
        active = true;
    }

    Action tick(long id, double distance) {
        if (!active || id != frontierId) return Action.NONE;
        totalTicks++;
        if (distance <= bestDistance - MIN_DISTANCE_IMPROVEMENT) {
            bestDistance = distance;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
        if (noProgressTicks < NO_PROGRESS_TICKS && totalTicks < MAX_TOTAL_TICKS) return Action.NONE;
        active = false;
        failures++;
        return failures >= MAX_FAILURES ? Action.EXHAUSTED : Action.RETRY;
    }

    void reached(long id) {
        if (!active || id != frontierId) return;
        active = false;
        failures = 0;
    }

    Action noCandidate() {
        active = false;
        failures++;
        return failures >= MAX_FAILURES ? Action.EXHAUSTED : Action.RETRY;
    }

    boolean active() {
        return active;
    }

    void reset() {
        frontierId = 0L;
        bestDistance = Double.POSITIVE_INFINITY;
        noProgressTicks = 0;
        totalTicks = 0;
        failures = 0;
        active = false;
    }

    int failures() {
        return failures;
    }

    enum Action {
        NONE,
        RETRY,
        EXHAUSTED
    }
}
