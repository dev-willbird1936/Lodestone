// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure ordering logic - no threads, no live client - so priority-vs-arrival-order is trivially testable. */
class GoalQueueOrderTest {
    @Test
    void priorityTicketOrdersAheadOfEarlierNonPriorityTicket() {
        var earlyNonPriority = new GoalQueueTicket(0, false, "early");
        var laterPriority = new GoalQueueTicket(1, true, "later");

        assertTrue(GoalQueueOrder.INSTANCE.compare(laterPriority, earlyNonPriority) < 0);
        assertTrue(GoalQueueOrder.INSTANCE.compare(earlyNonPriority, laterPriority) > 0);
    }

    @Test
    void sameTierTicketsOrderByArrivalSequence() {
        var first = new GoalQueueTicket(0, false, "first");
        var second = new GoalQueueTicket(1, false, "second");
        assertTrue(GoalQueueOrder.INSTANCE.compare(first, second) < 0);
        assertTrue(GoalQueueOrder.INSTANCE.compare(second, first) > 0);

        var firstPriority = new GoalQueueTicket(2, true, "firstPriority");
        var secondPriority = new GoalQueueTicket(3, true, "secondPriority");
        assertTrue(GoalQueueOrder.INSTANCE.compare(firstPriority, secondPriority) < 0);
    }

    @Test
    void identicalTicketComparesEqual() {
        var ticket = new GoalQueueTicket(5, true, "x");
        assertEquals(0, GoalQueueOrder.INSTANCE.compare(ticket, ticket));
    }

    @Test
    void treeSetOrdersManyPriorityAndNonPriorityTicketsAsTwoClassFifo() {
        var waiting = new TreeSet<>(GoalQueueOrder.INSTANCE);
        var a = new GoalQueueTicket(0, false, "a");
        var b = new GoalQueueTicket(1, false, "b");
        var c = new GoalQueueTicket(2, true, "c");
        var d = new GoalQueueTicket(3, true, "d");
        var e = new GoalQueueTicket(4, false, "e");
        var f = new GoalQueueTicket(5, true, "f");

        // Deliberately inserted out of order to prove sorting, not insertion order, drives iteration.
        waiting.add(e);
        waiting.add(c);
        waiting.add(a);
        waiting.add(f);
        waiting.add(b);
        waiting.add(d);

        // All priority tickets first (by arrival: c, d, f), then all non-priority (by arrival: a, b, e).
        assertEquals(List.of(c, d, f, a, b, e), List.copyOf(waiting));
    }
}
