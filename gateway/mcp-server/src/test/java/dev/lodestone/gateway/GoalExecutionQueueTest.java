// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrency plumbing tests - real Java threads, no live Minecraft client. Every scenario is driven
 * by latches so ordering is deterministic rather than timing-dependent.
 */
class GoalExecutionQueueTest {
    @Test
    void noContentionRunsImmediatelyAndReturnsTheActionResult() {
        var queue = new GoalExecutionQueue();
        var result = queue.run("solo", false, 5_000,
                context -> "ran:" + context.waitedMs() + ":" + context.positionAtEnqueue(),
                context -> "cancelled", context -> "timedOut");
        assertTrue(result.startsWith("ran:"));
        assertTrue(result.endsWith(":0"), "a caller with nothing ahead of it has queue position 0: " + result);
    }

    @Test
    void concurrentCallersNeverExecuteSimultaneously() throws InterruptedException {
        var queue = new GoalExecutionQueue();
        var concurrent = new AtomicInteger();
        var maxObservedConcurrent = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (var i = 0; i < 6; i++) {
            var thread = new Thread(() -> queue.run("caller", false, 5_000, context -> {
                var now = concurrent.incrementAndGet();
                maxObservedConcurrent.updateAndGet(previous -> Math.max(previous, now));
                sleepQuietly(15);
                concurrent.decrementAndGet();
                return null;
            }, context -> null, context -> null));
            threads.add(thread);
        }
        threads.forEach(Thread::start);
        for (var thread : threads) thread.join(5_000);
        assertEquals(1, maxObservedConcurrent.get(), "no two callers should ever run at the same time");
    }

    @Test
    void priorityCallerJumpsAheadOfAnAlreadyQueuedNonPriorityCallerButNotTheRunningGoal() throws InterruptedException {
        var queue = new GoalExecutionQueue();
        var order = Collections.synchronizedList(new ArrayList<String>());
        var runningStarted = new CountDownLatch(1);
        var releaseRunning = new CountDownLatch(1);

        var running = new Thread(() -> queue.run("running", false, 5_000, context -> {
            order.add("running");
            runningStarted.countDown();
            awaitQuietly(releaseRunning);
            return null;
        }, context -> null, context -> null));
        running.start();
        assertTrue(runningStarted.await(2, TimeUnit.SECONDS));

        var plain = new Thread(() -> queue.run("plain", false, 5_000, context -> {
            order.add("plain");
            return null;
        }, context -> null, context -> null));
        plain.start();
        waitUntilWaitingCountAtLeast(queue, 1);

        var priority = new Thread(() -> queue.run("priority", true, 5_000, context -> {
            order.add("priority");
            return null;
        }, context -> null, context -> null));
        priority.start();
        waitUntilWaitingCountAtLeast(queue, 2);

        releaseRunning.countDown();
        running.join(2_000);
        priority.join(2_000);
        plain.join(2_000);

        // The already-running goal is never preempted; the priority goal jumps the already-queued plain one.
        assertEquals(List.of("running", "priority", "plain"), order);
    }

    @Test
    void interruptedWhileWaitingWithdrawsTheTicketAndNeverRunsTheAction() throws InterruptedException {
        var queue = new GoalExecutionQueue();
        var runningStarted = new CountDownLatch(1);
        var releaseRunning = new CountDownLatch(1);
        var actionRan = new AtomicInteger();
        var waiterResult = new AtomicReference<String>();

        var running = new Thread(() -> queue.run("running", false, 5_000, context -> {
            runningStarted.countDown();
            awaitQuietly(releaseRunning);
            return "ran";
        }, context -> "cancelled", context -> "timedOut"));
        running.start();
        assertTrue(runningStarted.await(2, TimeUnit.SECONDS));

        var waiter = new Thread(() -> waiterResult.set(queue.run("waiter", false, 5_000, context -> {
            actionRan.incrementAndGet();
            return "ran";
        }, context -> "cancelled:pos=" + context.positionAtEnqueue(), context -> "timedOut")));
        waiter.start();
        waitUntilWaitingCountAtLeast(queue, 1);

        waiter.interrupt();
        waiter.join(2_000);

        assertEquals("cancelled:pos=0", waiterResult.get());
        assertEquals(0, actionRan.get(), "the goal must never start once its ticket is withdrawn");
        assertTrue(waiter.isInterrupted(), "interrupt status must be restored before returning");

        releaseRunning.countDown();
        running.join(2_000);
    }

    @Test
    void queueWaitTimeoutWithdrawsTheTicketBeforeItCanRunAndNamesTheActiveGoal() throws InterruptedException {
        var queue = new GoalExecutionQueue();
        var runningStarted = new CountDownLatch(1);
        var releaseRunning = new CountDownLatch(1);
        var actionRan = new AtomicInteger();

        var running = new Thread(() -> queue.run("the-running-goal", false, 5_000, context -> {
            runningStarted.countDown();
            awaitQuietly(releaseRunning);
            return "ran";
        }, context -> "cancelled", context -> "timedOut"));
        running.start();
        assertTrue(runningStarted.await(2, TimeUnit.SECONDS));

        // A tiny wait budget guarantees the waiter times out long before releaseRunning fires.
        var result = queue.run("waiter", false, 30, context -> {
            actionRan.incrementAndGet();
            return "ran";
        }, context -> "cancelled", context -> "timedOut:" + context.activeLabel());

        assertEquals("timedOut:the-running-goal", result);
        assertEquals(0, actionRan.get(), "a goal that timed out waiting must never start");

        releaseRunning.countDown();
        running.join(2_000);
    }

    @Test
    void afterARunCompletesTheNextWaiterIsGrantedAndPreviousTicketsAreGone() throws InterruptedException {
        var queue = new GoalExecutionQueue();
        assertEquals(0, queue.waitingCount());
        queue.run("first", false, 5_000, context -> "ok", context -> "cancelled", context -> "timedOut");
        assertEquals(0, queue.waitingCount(), "a finished ticket must not linger in the waiting set");
    }

    private static void waitUntilWaitingCountAtLeast(GoalExecutionQueue queue, int expected) {
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queue.waitingCount() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                fail("timed out waiting for waitingCount >= " + expected + " (was " + queue.waitingCount() + ")");
            }
            sleepQuietly(5);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
