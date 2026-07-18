// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import java.util.Comparator;

/**
 * Pure ordering for {@link GoalQueueTicket}s waiting to run a Minecraft goal. A priority ticket
 * always orders ahead of every non-priority ticket regardless of arrival order; within the same
 * priority tier, earlier arrivals go first. This never reorders a goal that has already left the
 * queue and started executing - see {@link GoalExecutionQueue} for that boundary.
 */
final class GoalQueueOrder implements Comparator<GoalQueueTicket> {
    static final GoalQueueOrder INSTANCE = new GoalQueueOrder();

    private GoalQueueOrder() {
    }

    @Override
    public int compare(GoalQueueTicket left, GoalQueueTicket right) {
        if (left.priority() != right.priority()) {
            return left.priority() ? -1 : 1;
        }
        return Long.compare(left.sequence(), right.sequence());
    }
}
