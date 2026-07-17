// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Map;

/**
 * Loader-neutral description of an MCP capability at the verified-action seam.
 *
 * <p>The contract intentionally describes capabilities rather than accepting arbitrary generated
 * functions. Loader adapters remain responsible for the actual Minecraft-side preconditions and
 * postcondition readback.</p>
 */
public record GoalActionContract(String capability, GoalActionKind kind,
                                 boolean survivalAllowed,
                                 boolean requiresVerifiedPostcondition) {
    public GoalActionContract {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("action contract capability is required");
        }
        capability = capability.trim();
        kind = kind == null ? GoalActionKind.UNKNOWN : kind;
    }

    public static GoalActionContract describe(String capability, Map<String, Object> input) {
        if (capability == null || capability.isBlank()) {
            return new GoalActionContract("<missing>", GoalActionKind.UNKNOWN, false, true);
        }
        var normalized = capability.trim().toLowerCase(java.util.Locale.ROOT);
        if (isObservation(normalized)) {
            return new GoalActionContract(capability, GoalActionKind.OBSERVATION, true, false);
        }
        if (normalized.startsWith("minecraft.goal.")) {
            return new GoalActionContract(capability, GoalActionKind.NATIVE_GOAL, true, true);
        }
        if (normalized.equals("minecraft.player.move") || normalized.equals("minecraft.player.look")
                || normalized.equals("minecraft.goal.navigation.safe-waypoint")) {
            return new GoalActionContract(capability, GoalActionKind.NAVIGATION, true,
                    normalized.equals("minecraft.player.move"));
        }
        if (normalized.equals("minecraft.player.interact")) {
            var action = input == null ? "use" : String.valueOf(input.getOrDefault("action", "use"));
            var kind = "attack".equalsIgnoreCase(action) ? GoalActionKind.COMBAT : GoalActionKind.INTERACTION;
            return new GoalActionContract(capability, kind, true, true);
        }
        if (normalized.equals("minecraft.entity.interact")) {
            return new GoalActionContract(capability, GoalActionKind.COMBAT, true, true);
        }
        if (normalized.startsWith("minecraft.inventory.")) {
            return new GoalActionContract(capability, GoalActionKind.INVENTORY, true, false);
        }
        if (normalized.startsWith("minecraft.ui.") || normalized.startsWith("lodestone.ui.")) {
            return new GoalActionContract(capability, GoalActionKind.UI, true, false);
        }
        if (normalized.equals("minecraft.input.release-all")) {
            return new GoalActionContract(capability, GoalActionKind.INPUT_RELEASE, true, false);
        }
        if (normalized.startsWith("minecraft.input.")) {
            return new GoalActionContract(capability, GoalActionKind.RAW_INPUT, false, false);
        }
        if (normalized.startsWith("minecraft.command.")
                || normalized.startsWith("minecraft.player.command.")
                || normalized.equals("minecraft.chat.send")) {
            return new GoalActionContract(capability, GoalActionKind.COMMAND, false, false);
        }
        if (normalized.equals("minecraft.world.block.write")
                || normalized.equals("minecraft.world.blocks.write")) {
            return new GoalActionContract(capability, GoalActionKind.WORLD_MUTATION, false, false);
        }
        return new GoalActionContract(capability, GoalActionKind.UNKNOWN, false, true);
    }

    private static boolean isObservation(String capability) {
        return capability.equals("minecraft.player.state.read")
                || capability.equals("minecraft.player.context.read")
                || capability.equals("minecraft.ui.state.read")
                || capability.equals("minecraft.server.info.read")
                || capability.equals("minecraft.chat.read")
                || capability.equals("minecraft.entity.nearby.read")
                || capability.equals("minecraft.world.region.scan")
                || capability.equals("minecraft.world.blocks.read")
                || capability.equals("minecraft.world.heightmap.read")
                || capability.equals("minecraft.world.light.analyze")
                || capability.equals("minecraft.registry.item.search")
                || capability.equals("minecraft.client.screenshot.capture");
    }
}
