// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventHubContractTest {
    @Test
    void overflowMarkerUsesRuntimeSessionAndPreservesSequenceOrder() {
        var hub = new EventHub();
        var subscription = hub.subscribe("transport-caller", "runtime-session", "minecraft.", 2);
        hub.publish("runtime-session", "minecraft.one", Map.of(), 1);
        hub.publish("runtime-session", "minecraft.two", Map.of(), 2);
        hub.publish("runtime-session", "minecraft.three", Map.of(), 3);

        var events = hub.poll("transport-caller", subscription.id(), 10);

        assertEquals(3, events.size());
        assertEquals("lodestone.events.lost", events.get(0).event());
        assertTrue(events.stream().allMatch(event -> "runtime-session".equals(event.sessionId())));
        for (var index = 1; index < events.size(); index++) {
            assertTrue(events.get(index - 1).sequence() < events.get(index).sequence(),
                    "event sequences must be strictly increasing");
        }
    }

    @Test
    void concurrentPublishDeliversEventsInMonotonicSequenceOrder() throws Exception {
        var hub = new EventHub();
        var subscription = hub.subscribe("runtime-session", "minecraft.", 10_000);
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
        for (var thread = 0; thread < 16; thread++) {
            var publisher = thread;
            tasks.add(() -> {
                start.await();
                for (var event = 0; event < 625; event++) {
                    hub.publish("runtime-session", "minecraft.concurrent",
                            Map.of("publisher", publisher, "event", event), event);
                }
                return null;
            });
        }

        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        var events = new ArrayList<dev.lodestone.protocol.EventEnvelope>();
        while (events.size() < 10_000) {
            events.addAll(hub.poll(subscription.id(), 1_000));
        }
        assertEquals(10_000, events.size());
        for (var index = 1; index < events.size(); index++) {
            assertTrue(events.get(index - 1).sequence() < events.get(index).sequence(),
                    "concurrent publication must preserve sequence assignment order");
        }
    }

    @Test
    void ownerlessAccessIsLimitedToLocalSubscriptions() {
        var hub = new EventHub();
        var remote = hub.subscribe("transport-caller", "runtime-session", "minecraft.", 10);

        assertThrows(SecurityException.class, () -> hub.poll(remote.id(), 10));
        assertThrows(SecurityException.class, () -> hub.unsubscribe(remote.id()));
        assertTrue(hub.unsubscribe("transport-caller", remote.id()));

        var local = hub.subscribe("runtime-session", "minecraft.", 10);
        assertTrue(hub.poll(local.id(), 10).isEmpty());
        assertTrue(hub.unsubscribe(local.id()));
        assertFalse(hub.unsubscribe(local.id()));
    }

    @Test
    void mutationCommitRunsOnlyAfterSubscribeValidation() {
        var hub = new EventHub();
        var commits = new AtomicInteger();

        assertThrows(IllegalArgumentException.class,
                () -> hub.subscribe("caller", "runtime", "x".repeat(257), 10, commits::incrementAndGet));
        assertEquals(0, commits.get());

        var subscription = hub.subscribe("caller", "runtime", "minecraft.", 10, commits::incrementAndGet);
        assertEquals(1, commits.get());
        assertEquals("caller", subscription.sessionId());

        for (var index = 1; index < 32; index++) {
            hub.subscribe("caller", "runtime", "minecraft.", 10);
        }
        assertThrows(IllegalStateException.class,
                () -> hub.subscribe("caller", "runtime", "minecraft.", 10, commits::incrementAndGet));
        assertEquals(1, commits.get());
    }

    @Test
    void mutationCommitRunsOnlyForAuthorizedExistingUnsubscribe() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller", "runtime", "minecraft.", 10);
        var commits = new AtomicInteger();

        assertThrows(SecurityException.class,
                () -> hub.unsubscribe("other-caller", subscription.id(), commits::incrementAndGet));
        assertFalse(hub.unsubscribe("caller", "missing", commits::incrementAndGet));
        assertEquals(0, commits.get());

        assertTrue(hub.unsubscribe("caller", subscription.id(), commits::incrementAndGet));
        assertEquals(1, commits.get());
        assertFalse(hub.unsubscribe("caller", subscription.id(), commits::incrementAndGet));
        assertEquals(1, commits.get());
    }

    @Test
    void cancelledPollPreservesQueuedEventsAndOverflowMarker() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller", "runtime", "minecraft.", 2);
        hub.publish("runtime", "minecraft.one", Map.of(), 1);
        hub.publish("runtime", "minecraft.two", Map.of(), 2);
        hub.publish("runtime", "minecraft.three", Map.of(), 3);
        var cancellation = new IllegalStateException("cancelled before mutation");

        var thrown = assertThrows(IllegalStateException.class,
                () -> hub.poll("caller", subscription.id(), 10, () -> {
                    throw cancellation;
                }));

        assertEquals(cancellation, thrown);
        assertEquals(2, hub.queuedEvents());
        var events = hub.poll("caller", subscription.id(), 10);
        assertEquals(3, events.size());
        assertEquals("lodestone.events.lost", events.get(0).event());
        assertEquals(1L, events.get(0).payload().get("dropped"));
        assertEquals("minecraft.two", events.get(1).event());
        assertEquals("minecraft.three", events.get(2).event());
    }

    @Test
    void emptyPollDoesNotCommitAndSubscriptionRemainsUsable() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller", "runtime", "minecraft.", 10);
        var commits = new AtomicInteger();

        assertTrue(hub.poll("caller", subscription.id(), 10, commits::incrementAndGet).isEmpty());
        assertEquals(0, commits.get());

        hub.publish("runtime", "minecraft.ready", Map.of(), 1);
        var events = hub.poll("caller", subscription.id(), 10, commits::incrementAndGet);
        assertEquals(1, events.size());
        assertEquals("minecraft.ready", events.get(0).event());
        assertEquals(1, commits.get());
        assertTrue(hub.poll("caller", subscription.id(), 10, commits::incrementAndGet).isEmpty());
        assertEquals(1, commits.get());
    }

    @Test
    void pollCommitRunsOnlyAfterRequestAndOwnershipValidation() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller", "runtime", "minecraft.", 10);
        hub.publish("runtime", "minecraft.ready", Map.of(), 1);
        var commits = new AtomicInteger();

        assertThrows(IllegalArgumentException.class,
                () -> hub.poll("", subscription.id(), 10, commits::incrementAndGet));
        assertThrows(IllegalArgumentException.class,
                () -> hub.poll("caller", "", 10, commits::incrementAndGet));
        assertThrows(IllegalArgumentException.class,
                () -> hub.poll("caller", subscription.id(), 0, commits::incrementAndGet));
        assertThrows(SecurityException.class,
                () -> hub.poll("other", subscription.id(), 10, commits::incrementAndGet));
        assertEquals(0, commits.get());

        var events = hub.poll("caller", subscription.id(), 10, commits::incrementAndGet);
        assertEquals(1, events.size());
        assertEquals(1, commits.get());
    }

    @Test
    void broadSubscriptionsFailClosedForSensitiveRawInputEvents() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller", "runtime", "", 10);

        hub.publish("runtime", "minecraft.input.key.received", Map.of("key", 65), 10);
        hub.publish("runtime", "minecraft.lifecycle.server.started", Map.of(), 11);

        var events = hub.poll("caller", subscription.id(), 10);
        assertEquals(1, events.size());
        assertEquals("minecraft.lifecycle.server.started", events.get(0).event());
    }
}
