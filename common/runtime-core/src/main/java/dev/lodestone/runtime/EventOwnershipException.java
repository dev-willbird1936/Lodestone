// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

/** Stable internal signal for caller/subscription ownership violations. */
final class EventOwnershipException extends SecurityException {
    EventOwnershipException(String message) {
        super(message);
    }
}
