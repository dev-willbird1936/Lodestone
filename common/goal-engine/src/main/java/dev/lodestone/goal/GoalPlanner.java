// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

public interface GoalPlanner {
    PlanResult plan(GoalSpec spec);

    record PlanResult(GoalPlan plan, String unsupportedReason) {
        public boolean supported() {
            return plan != null;
        }

        public static PlanResult supported(GoalPlan plan) {
            return new PlanResult(plan, null);
        }

        public static PlanResult unsupported(String reason) {
            return new PlanResult(null, reason);
        }
    }
}
