// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

/**
 * One caller's position in {@link GoalExecutionQueue}. {@code sequence} is a strictly increasing
 * arrival index that breaks ties within a priority tier and guarantees every ticket is distinct, so
 * a {@link java.util.TreeSet} ordered by {@link GoalQueueOrder} never collapses two waiting callers
 * into the same slot. {@code label} is a short human-readable description of the call (e.g. the goal
 * text) used only for diagnostics such as a queue-wait-timeout message; it never affects ordering.
 */
record GoalQueueTicket(long sequence, boolean priority, String label) {
}
