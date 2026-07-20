// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the live-caught bug where {@code minecraft.player.interact}'s "attack"
 * case could not break any block with nonzero hardness: the old implementation issued a single
 * momentary {@code KeyMapping.click(...)}, which never sets {@code isDown()}, so vanilla's
 * destroy-progress accumulation was never driven and the attack silently no-oped (see
 * verification/evidence/goal-orchestrator-milestone1/trace-23da1de26e5c.jsonl turn 35/36 - an
 * attack on a leaves block reported {@code {"queued":true}} yet the identical block was still
 * targeted on the very next observation).
 *
 * <p>{@link NeoForgeAttackHold#tick} itself needs a live {@code Minecraft}/{@code ClientLevel}/
 * {@code LocalPlayer} it cannot construct outside a running client, so - matching this module's
 * existing convention for exercising tick-driven logic without a live world (see
 * {@code NeoForgeNavigationGoalTest.mineTimeoutTicksIsZeroWithoutProgressAndOtherwiseTriplesTheEstimateWithAFixedFloor}
 * and {@code NeoForgeMiningAccountingTest}, both of which test pure static decision logic instead
 * of constructing a real world) - this drives {@link NeoForgeAttackHold#nextTick}, the pure
 * per-tick decision function {@code tick()} defers to, directly.
 */
final class NeoForgeAttackHoldTest {
    // Representative low-hardness block a held attack must actually break: oak leaves broken by
    // hand accumulate roughly 0.05 destroy progress per tick (~20 ticks/1s), the exact class of
    // block the live trace above found silently un-breakable under the old single-click code.
    private static final double LOW_HARDNESS_PROGRESS_PER_TICK = 0.05;

    @Test
    void heldAttackKeepsHoldingUntilTheBlockBreaksWithinItsBoundedTimeout() {
        var timeoutTicks = Math.min(NeoForgeNavigationGoal.mineTimeoutTicks(LOW_HARDNESS_PROGRESS_PER_TICK),
                NeoForgeAttackHold.MAX_HOLD_TICKS);
        var ticksToBreak = (int) Math.ceil(1.0 / LOW_HARDNESS_PROGRESS_PER_TICK);
        assertTrue(ticksToBreak <= timeoutTicks,
                "test setup sanity: the block must be able to break inside the timeout budget");

        for (var tick = 1; tick < ticksToBreak; tick++) {
            var outcome = NeoForgeAttackHold.nextTick(false, true, tick, timeoutTicks);
            assertEquals(NeoForgeAttackHold.TickOutcome.HOLDING, outcome,
                    "must keep holding attack while the block is still present and aimed at, tick " + tick);
        }
        // The block finally goes air on the tick the accumulated progress reaches 1.0.
        var broken = NeoForgeAttackHold.nextTick(true, true, ticksToBreak, timeoutTicks);
        assertEquals(NeoForgeAttackHold.TickOutcome.BROKEN, broken,
                "a held attack must recognize and report the break as soon as the block clears");
    }

    @Test
    void heldAttackTimesOutInsteadOfHangingWhenTheBlockNeverClears() {
        var timeoutTicks = Math.min(NeoForgeNavigationGoal.mineTimeoutTicks(LOW_HARDNESS_PROGRESS_PER_TICK),
                NeoForgeAttackHold.MAX_HOLD_TICKS);

        for (var tick = 1; tick <= timeoutTicks; tick++) {
            var outcome = NeoForgeAttackHold.nextTick(false, true, tick, timeoutTicks);
            assertEquals(NeoForgeAttackHold.TickOutcome.HOLDING, outcome);
        }
        var timedOut = NeoForgeAttackHold.nextTick(false, true, timeoutTicks + 1, timeoutTicks);
        assertEquals(NeoForgeAttackHold.TickOutcome.TIMED_OUT, timedOut);
    }

    @Test
    void heldAttackNeverWaitsLongerThanTheBoundedHoldBudgetEvenForAnAbsurdlySlowBlock() {
        // A block technically breakable but only at a glacial pace (e.g. bare-handed obsidian)
        // must still fail fast - MAX_HOLD_TICKS bounds every hold well inside interact's own
        // capability timeout instead of hanging the call for minutes like a native goal actor may.
        var timeoutTicks = Math.min(NeoForgeNavigationGoal.mineTimeoutTicks(0.0002), NeoForgeAttackHold.MAX_HOLD_TICKS);
        assertEquals(NeoForgeAttackHold.MAX_HOLD_TICKS, timeoutTicks);
    }

    @Test
    void losingAimPausesHoldingInsteadOfAbandoningTheAttempt() {
        var outcome = NeoForgeAttackHold.nextTick(false, false, 5, 100);
        assertEquals(NeoForgeAttackHold.TickOutcome.WAITING_FOR_AIM, outcome,
                "a momentary aim loss must pause holding, not fail the whole held attack");
    }

    @Test
    void aBrokenBlockTakesPriorityOverATimedOutBudget() {
        // Even on the exact tick the timeout budget is exhausted, a block that cleared this same
        // tick must still be reported as a successful break, not a timeout.
        var outcome = NeoForgeAttackHold.nextTick(true, true, 101, 100);
        assertEquals(NeoForgeAttackHold.TickOutcome.BROKEN, outcome);
    }
}
