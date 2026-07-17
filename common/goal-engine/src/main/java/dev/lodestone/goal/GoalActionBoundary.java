// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

/**
 * Final, deterministic policy boundary before a planned action reaches a loader adapter.
 *
 * <p>This is deliberately separate from model selection. A model can propose an action, but the
 * boundary decides whether that typed action is legal for this goal and whether it has enough
 * verification attached to it.</p>
 */
public final class GoalActionBoundary {
    private GoalActionBoundary() {
    }

    public static Decision check(GoalSpec spec, GoalPlan plan, GoalStep step) {
        if (step == null || step.kind() == GoalStepKind.ASSERT) {
            return Decision.allowed(GoalActionKind.OBSERVATION, "assertion");
        }
        var contract = GoalActionContract.describe(step.capability(), step.input());
        if (!survivalScoped(plan, spec)) {
            return Decision.allowed(contract.kind(), "non-survival goal");
        }
        if (!contract.survivalAllowed()) {
            return Decision.denied(contract.kind(),
                    "survival goals may not use " + contract.kind().name().toLowerCase()
                            + " capability " + step.capability());
        }
        if (contract.requiresVerifiedPostcondition()
                && step.assertions().isEmpty() && !step.observeAfter()) {
            return Decision.denied(contract.kind(),
                    "survival action requires an assertion or post-action observation: " + step.id());
        }
        return Decision.allowed(contract.kind(), "typed survival action");
    }

    private static boolean survivalScoped(GoalPlan plan, GoalSpec spec) {
        return "survival".equals(String.valueOf(plan.metadata().get("gameMode")))
                || survivalId(plan.id()) || survivalId(spec.taskId());
    }

    private static boolean survivalId(String id) {
        return id != null && (id.startsWith("survival.") || id.startsWith("combat.")
                || id.equals("navigation.safe-waypoint") || id.equals("navigation.reach-waypoint"));
    }

    public record Decision(boolean allowed, GoalActionKind kind, String reason) {
        public Decision {
            kind = kind == null ? GoalActionKind.UNKNOWN : kind;
            reason = reason == null ? "" : reason;
        }

        static Decision allowed(GoalActionKind kind, String reason) {
            return new Decision(true, kind, reason);
        }

        static Decision denied(GoalActionKind kind, String reason) {
            return new Decision(false, kind, reason);
        }
    }
}
