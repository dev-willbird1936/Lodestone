// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Serializes access to the single native Minecraft goal actor. Before this queue existed,
 * {@code minecraft_goal} (and {@code minecraft_goal_benchmark}) calls reached {@code GoalEngine.run}
 * with zero coordination: a second call arriving while a first was still in flight raced the same
 * client/world state, with no defined outcome - at worst it could hit the native "a native Minecraft
 * goal actor is already running" guard deep inside the adapter mid-execution. Every live-client
 * caller that drives {@code GoalEngine} must go through the same {@code GoalExecutionQueue} instance
 * for that guarantee to hold.
 *
 * <p>Every caller now waits its turn on its own calling thread, preserving the synchronous
 * {@code minecraft_goal} contract callers already depend on: nothing here hands work off to another
 * thread or returns before the goal is done. Waiting callers are ordered by {@link GoalQueueOrder}: a
 * priority ticket always sorts ahead of every non-priority ticket, but arrival order is preserved
 * within each tier (a two-class FIFO) - priority tickets never reorder relative to each other, and
 * neither do non-priority ones. A priority ticket never preempts a goal that is already running; only
 * one goal is ever active at a time regardless of priority. A sustained stream of priority=true
 * callers can therefore starve non-priority callers indefinitely; that is an accepted trade-off for
 * this single-user local tool, not a bug - fixing it with aging/fairness boosts is deliberately out
 * of scope.
 *
 * <p>A caller that is interrupted while still waiting (e.g. cancelled, or its HTTP request thread
 * died because the client disconnected) withdraws its ticket before it can ever run and never reaches
 * {@code action}; {@code cancelledWhileQueued} supplies the result instead. Total queue wait is also
 * bounded by {@code maxWaitMs} so a caller can never silently pile an unbounded, invisible wait on top
 * of the goal's own execution budget - if the deadline elapses first, the ticket is likewise withdrawn
 * without ever running and {@code queueWaitTimedOut} supplies the result.
 */
final class GoalExecutionQueue {
    /**
     * Snapshot of one caller's queue experience, handed to whichever outcome function fires.
     * {@code activeLabel} - the label of the ticket currently holding the run slot - is only
     * populated for the timeout outcome; it is null when granted (nothing else is running by
     * definition) and when cancelled (irrelevant to that outcome).
     */
    record Context(GoalQueueTicket ticket, long waitedMs, int positionAtEnqueue, String activeLabel) {
    }

    private final Object lock = new Object();
    private final TreeSet<GoalQueueTicket> waiting = new TreeSet<>(GoalQueueOrder.INSTANCE);
    private final AtomicLong sequence = new AtomicLong();
    private GoalQueueTicket runningTicket;

    <T> T run(String label, boolean priority, long maxWaitMs, Function<Context, T> action,
              Function<Context, T> cancelledWhileQueued, Function<Context, T> queueWaitTimedOut) {
        var ticket = new GoalQueueTicket(sequence.getAndIncrement(), priority, label);
        var enqueuedAtNanos = System.nanoTime();
        var deadlineNanos = enqueuedAtNanos + Math.max(0, maxWaitMs) * 1_000_000L;
        Context grantContext;
        synchronized (lock) {
            waiting.add(ticket);
            var positionAtEnqueue = waiting.headSet(ticket).size();
            var interrupted = false;
            var timedOut = false;
            while (!interrupted && !timedOut && (runningTicket != null || waiting.first() != ticket)) {
                var remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    timedOut = true;
                    break;
                }
                try {
                    lock.wait(Math.max(1, remainingNanos / 1_000_000L));
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
            var waitedMs = (System.nanoTime() - enqueuedAtNanos) / 1_000_000L;
            if (interrupted) {
                waiting.remove(ticket);
                lock.notifyAll();
                Thread.currentThread().interrupt();
                return cancelledWhileQueued.apply(new Context(ticket, waitedMs, positionAtEnqueue, null));
            }
            if (timedOut) {
                waiting.remove(ticket);
                var activeLabel = runningTicket == null ? null : runningTicket.label();
                lock.notifyAll();
                return queueWaitTimedOut.apply(new Context(ticket, waitedMs, positionAtEnqueue, activeLabel));
            }
            waiting.remove(ticket);
            runningTicket = ticket;
            grantContext = new Context(ticket, waitedMs, positionAtEnqueue, null);
        }
        try {
            return action.apply(grantContext);
        } finally {
            synchronized (lock) {
                runningTicket = null;
                lock.notifyAll();
            }
        }
    }

    /** Observability/test hook: how many callers are currently waiting (not yet running). */
    int waitingCount() {
        synchronized (lock) {
            return waiting.size();
        }
    }
}
