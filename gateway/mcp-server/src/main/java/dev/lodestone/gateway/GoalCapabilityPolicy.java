// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

/** Keeps model-owned goals separate from reusable primitive MCP capabilities. */
final class GoalCapabilityPolicy {
    static final String SAFE_WAYPOINT = "minecraft.goal.navigation.safe-waypoint";

    private GoalCapabilityPolicy() {
    }

    static boolean isNativeGoalRoutine(String capability) {
        return capability != null
                && capability.startsWith("minecraft.goal.")
                && !SAFE_WAYPOINT.equals(capability);
    }

    static void requireModelPrimitive(String capability) {
        if (isNativeGoalRoutine(capability)) {
            throw new IllegalArgumentException("native Minecraft goal routines are internal test fixtures; "
                    + "the current agent or its subagent must plan primitive subactions");
        }
    }
}
