// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public record RateLimit(int permits, long windowMs, int burst) {
    public RateLimit {
        if (permits < 1 || windowMs < 1 || burst < 1) {
            throw new IllegalArgumentException("rate limit values must be positive");
        }
    }
}
