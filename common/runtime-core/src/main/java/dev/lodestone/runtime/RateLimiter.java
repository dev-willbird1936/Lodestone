// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.RateLimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-session capability token buckets. */
final class RateLimiter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    boolean allow(String key, RateLimit limit) {
        return buckets.computeIfAbsent(key, ignored -> new Bucket(limit)).allow(limit);
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos = System.nanoTime();

        private Bucket(RateLimit limit) {
            tokens = limit.burst();
        }

        private synchronized boolean allow(RateLimit limit) {
            var now = System.nanoTime();
            var elapsed = Math.max(0L, now - lastNanos);
            var refill = elapsed / 1_000_000_000d * limit.permits() / limit.windowMs() * 1000d;
            tokens = Math.min(limit.burst(), tokens + refill);
            lastNanos = now;
            if (tokens < 1d) {
                return false;
            }
            tokens -= 1d;
            return true;
        }
    }
}
