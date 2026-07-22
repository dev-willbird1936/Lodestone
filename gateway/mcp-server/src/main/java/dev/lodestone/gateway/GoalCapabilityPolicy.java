// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import java.util.Set;

/** Keeps model-owned goals separate from reusable primitive MCP capabilities. */
final class GoalCapabilityPolicy {
    static final String SAFE_WAYPOINT = "minecraft.goal.navigation.safe-waypoint";
    private static final Set<String> FILTERED_DISCOVERY_CAPABILITIES = Set.of(
            "lodestone.system.handshake",
            "lodestone.system.capabilities.list",
            "lodestone.system.capabilities.get",
            "lodestone.system.capabilities.search");

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

    static void requireGenericInvokeAllowed(String capability) {
        requireModelPrimitive(capability);
        if (FILTERED_DISCOVERY_CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("system capability discovery must use the filtered MCP discovery tools");
        }
    }
}
