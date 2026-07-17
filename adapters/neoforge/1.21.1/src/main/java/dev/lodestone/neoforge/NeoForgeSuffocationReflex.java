// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/**
 * Debounced, bounded escape decision for a player embedded in solid terrain.
 *
 * <p>Vanilla suffocation damage is independent of air supply and water state, so the
 * water-retreat invariant never observes it. This reflex owns only the pure decision
 * state: confirm a sustained in-wall condition before acting, keep the escape bounded,
 * and return control with a distinct expiry signal instead of fighting terrain forever.
 * The supervisor owns the actual look/movement/mining input.
 */
final class NeoForgeSuffocationReflex {
    static final int CONFIRM_TICKS = 4;
    static final int MAX_ESCAPE_TICKS = 100;
    static final int REARM_COOLDOWN_TICKS = 40;

    private int confirmTicks;
    private int escapeTicks;
    private int cooldownTicks;
    private boolean escaping;

    Action tick(boolean inWall) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            if (!inWall) cooldownTicks = 0;
            return Action.NONE;
        }
        if (!escaping) {
            if (!inWall) {
                confirmTicks = 0;
                return Action.NONE;
            }
            if (++confirmTicks < CONFIRM_TICKS) return Action.NONE;
            escaping = true;
            escapeTicks = 0;
            return Action.ESCAPE;
        }
        if (!inWall) {
            reset();
            return Action.ESCAPED;
        }
        if (++escapeTicks >= MAX_ESCAPE_TICKS) {
            reset();
            cooldownTicks = REARM_COOLDOWN_TICKS;
            return Action.BUDGET_EXPIRED;
        }
        return Action.ESCAPE;
    }

    boolean escaping() {
        return escaping;
    }

    void reset() {
        confirmTicks = 0;
        escapeTicks = 0;
        escaping = false;
    }

    /** One decision per tick; ESCAPED and BUDGET_EXPIRED are one-shot terminal signals. */
    enum Action {
        NONE,
        ESCAPE,
        ESCAPED,
        BUDGET_EXPIRED
    }
}
