// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.EventEnvelope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EventHub {
    private static final int MAX_SUBSCRIPTIONS = 256;
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 32;
    private static final int MAX_RESERVED_BUFFER_EVENTS = 131_072;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionSubscriptionCounts = new HashMap<>();
    private int reservedBufferEvents;
    private final AtomicLong sequence = new AtomicLong();
    private static final Runnable NO_MUTATION_COMMIT = () -> { };

    public synchronized SubscriptionInfo subscribe(String sessionId, String eventPrefix, int bufferLimit) {
        return subscribe(sessionId, sessionId, eventPrefix, bufferLimit);
    }

    public synchronized SubscriptionInfo subscribe(String ownerSessionId, String eventSessionId,
                                                   String eventPrefix, int bufferLimit) {
        return subscribe(ownerSessionId, eventSessionId, eventPrefix, bufferLimit, NO_MUTATION_COMMIT);
    }

    public synchronized SubscriptionInfo subscribe(String ownerSessionId, String eventSessionId,
                                                   String eventPrefix, int bufferLimit,
                                                   Runnable mutationCommit) {
        if (ownerSessionId == null || ownerSessionId.isBlank()
                || eventSessionId == null || eventSessionId.isBlank()) {
            throw new IllegalArgumentException("subscription session IDs must not be blank");
        }
        var normalizedPrefix = eventPrefix == null ? "" : eventPrefix;
        if (normalizedPrefix.length() > 256) {
            throw new IllegalArgumentException("eventPrefix must not exceed 256 characters");
        }
        if (bufferLimit < 1 || bufferLimit > 10_000) {
            throw new IllegalArgumentException("bufferLimit must be between 1 and 10000");
        }
        Objects.requireNonNull(mutationCommit, "mutationCommit");
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("event subscription capacity reached");
        }
        var sessionCount = sessionSubscriptionCounts.getOrDefault(ownerSessionId, 0);
        if (sessionCount >= MAX_SUBSCRIPTIONS_PER_SESSION) {
            throw new IllegalStateException("event subscription capacity reached for session");
        }
        if (reservedBufferEvents > MAX_RESERVED_BUFFER_EVENTS - bufferLimit) {
            throw new IllegalStateException("event buffer capacity reached");
        }
        mutationCommit.run();
        var id = UUID.randomUUID().toString();
        subscriptions.put(id, new Subscription(id, ownerSessionId, eventSessionId,
                normalizedPrefix,
                bufferLimit));
        sessionSubscriptionCounts.put(ownerSessionId, sessionCount + 1);
        reservedBufferEvents += bufferLimit;
        return new SubscriptionInfo(id, ownerSessionId, normalizedPrefix, bufferLimit);
    }

    public synchronized void publish(String sessionId, String event, Map<String, Object> payload, long gameTick) {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event must not be blank");
        }
        if (event.startsWith("minecraft.input.")) {
            return;
        }
        var envelope = new EventEnvelope("1.0", sessionId, event, sequence.getAndIncrement(), payload,
                gameTick, null);
        subscriptions.values().forEach(subscription -> subscription.offer(envelope));
    }

    public List<EventEnvelope> poll(String subscriptionId, int maxEvents) {
        validateSubscriptionId(subscriptionId);
        validatePollLimit(maxEvents);
        var subscription = subscriptions.get(subscriptionId);
        if (subscription == null) {
            return List.of();
        }
        if (!subscription.ownerSessionId.equals(subscription.eventSessionId)) {
            throw new EventOwnershipException("owner-aware access is required for this event subscription");
        }
        return subscription.poll(maxEvents, NO_MUTATION_COMMIT);
    }

    public List<EventEnvelope> poll(String ownerSessionId, String subscriptionId, int maxEvents) {
        return poll(ownerSessionId, subscriptionId, maxEvents, NO_MUTATION_COMMIT);
    }

    public List<EventEnvelope> poll(String ownerSessionId, String subscriptionId, int maxEvents,
                                    Runnable mutationCommit) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            throw new IllegalArgumentException("ownerSessionId must not be blank");
        }
        validateSubscriptionId(subscriptionId);
        validatePollLimit(maxEvents);
        Objects.requireNonNull(mutationCommit, "mutationCommit");
        var subscription = subscriptions.get(subscriptionId);
        if (subscription == null) {
            return List.of();
        }
        if (!subscription.ownerSessionId.equals(ownerSessionId)) {
            throw new EventOwnershipException("event subscription does not belong to this caller");
        }
        return subscription.poll(maxEvents, mutationCommit);
    }

    public synchronized boolean unsubscribe(String subscriptionId) {
        validateSubscriptionId(subscriptionId);
        var existing = subscriptions.get(subscriptionId);
        if (existing == null) {
            return false;
        }
        if (!existing.ownerSessionId.equals(existing.eventSessionId)) {
            throw new EventOwnershipException("owner-aware access is required for this event subscription");
        }
        return removeSubscription(subscriptionId);
    }

    public synchronized boolean unsubscribe(String ownerSessionId, String subscriptionId) {
        return unsubscribe(ownerSessionId, subscriptionId, NO_MUTATION_COMMIT);
    }

    public synchronized boolean unsubscribe(String ownerSessionId, String subscriptionId,
                                            Runnable mutationCommit) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            throw new IllegalArgumentException("ownerSessionId must not be blank");
        }
        validateSubscriptionId(subscriptionId);
        Objects.requireNonNull(mutationCommit, "mutationCommit");
        var existing = subscriptions.get(subscriptionId);
        if (existing != null && !existing.ownerSessionId.equals(ownerSessionId)) {
            throw new EventOwnershipException("event subscription does not belong to this caller");
        }
        if (existing == null) {
            return false;
        }
        mutationCommit.run();
        return removeSubscription(subscriptionId);
    }

    private boolean removeSubscription(String subscriptionId) {
        var removed = subscriptions.remove(subscriptionId);
        if (removed == null) {
            return false;
        }
        var sessionCount = sessionSubscriptionCounts.getOrDefault(removed.ownerSessionId, 1) - 1;
        if (sessionCount <= 0) {
            sessionSubscriptionCounts.remove(removed.ownerSessionId);
        } else {
            sessionSubscriptionCounts.put(removed.ownerSessionId, sessionCount);
        }
        reservedBufferEvents -= removed.bufferLimit;
        return true;
    }

    private static void validatePollLimit(int maxEvents) {
        if (maxEvents < 1 || maxEvents > 1000) {
            throw new IllegalArgumentException("maxEvents must be between 1 and 1000");
        }
    }

    private static void validateSubscriptionId(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank() || subscriptionId.length() > 128) {
            throw new IllegalArgumentException("subscriptionId must contain between 1 and 128 characters");
        }
    }

    public long queuedEvents() {
        return subscriptions.values().stream().mapToLong(Subscription::size).sum();
    }

    public record SubscriptionInfo(String id, String sessionId, String eventPrefix, int bufferLimit) {
    }

    private static final class Subscription {
        private final String id;
        private final String ownerSessionId;
        private final String eventSessionId;
        private final String eventPrefix;
        private final int bufferLimit;
        private final ArrayDeque<EventEnvelope> queue = new ArrayDeque<>();
        private long droppedEvents;
        private long firstDroppedSequence = -1;
        private long lastDroppedSequence = -1;

        private Subscription(String id, String ownerSessionId, String eventSessionId,
                             String eventPrefix, int bufferLimit) {
            this.id = id;
            this.ownerSessionId = ownerSessionId;
            this.eventSessionId = eventSessionId;
            this.eventPrefix = eventPrefix;
            this.bufferLimit = bufferLimit;
        }

        private synchronized void offer(EventEnvelope event) {
            if (!eventSessionId.equals(event.sessionId())
                    || (!eventPrefix.isBlank() && !event.event().startsWith(eventPrefix))) {
                return;
            }
            if (queue.size() == bufferLimit) {
                var dropped = queue.removeFirst();
                if (droppedEvents == 0) {
                    firstDroppedSequence = dropped.sequence();
                }
                lastDroppedSequence = dropped.sequence();
                droppedEvents++;
            }
            queue.addLast(event);
        }

        private synchronized List<EventEnvelope> poll(int maxEvents, Runnable mutationCommit) {
            if (droppedEvents == 0 && queue.isEmpty()) {
                return List.of();
            }
            mutationCommit.run();
            var result = new ArrayList<EventEnvelope>(Math.min(maxEvents, queue.size()));
            if (droppedEvents > 0 && result.size() < maxEvents) {
                var dropped = droppedEvents;
                droppedEvents = 0;
                result.add(new EventEnvelope("1.0", eventSessionId, "lodestone.events.lost",
                        firstDroppedSequence, Map.of(
                        "subscriptionId", id,
                        "dropped", dropped,
                        "firstSequence", firstDroppedSequence,
                        "lastSequence", lastDroppedSequence), -1, null));
                firstDroppedSequence = -1;
                lastDroppedSequence = -1;
            }
            while (!queue.isEmpty() && result.size() < maxEvents) {
                result.add(queue.removeFirst());
            }
            return List.copyOf(result);
        }

        private synchronized int size() {
            return queue.size();
        }
    }
}
