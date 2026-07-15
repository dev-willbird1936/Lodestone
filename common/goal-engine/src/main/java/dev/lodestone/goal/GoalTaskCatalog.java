// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GoalTaskCatalog {
    private static final List<TaskDefinition> TASKS = List.of(
            task("creative.wool-tree-zombie-defense", "creative",
                    "Load a fresh world, manually build a wool-leaf tree in creative, set up a nearby zombie, teleport away, equip a diamond sword, switch to survival, and mine the tree while reacting defensively to observed threats.",
                    "creative-then-survival",
                    List.of("lodestone.ui.navigate", "minecraft.goal.creative.wool-tree-zombie-defense"),
                    "Creative setup commands are separated and silent. Every tree block is placed and mined with normal client input; zombie defense is triggered only by fresh visibility and distance observations; terminal readback proves survival, sword, reactive defense, full tree removal, and no direct mutation or unconditional kill routine."),
            task("survival.wooden-axe-mine-tree", "survival", "Load a fresh survival world, craft a wooden axe, and mine one whole tree.",
                    "survival", List.of("lodestone.ui.navigate", "minecraft.goal.survival.wooden-axe-tree"),
                    "A fresh empty survival world is loaded through Minecraft UI. Normal player input must hand-mine at least three logs, craft planks/sticks/table/wooden axe through visible inventory menus, equip the axe, and remove every initially observed log in a second tree."),
            task("survival.collect-wood", "survival", "Find a nearby tree and collect logs with player input.", "survival",
                    List.of("minecraft.player.state.read", "minecraft.world.region.scan", "minecraft.player.look", "minecraft.player.move", "minecraft.player.interact"),
                    "Fresh player state and a loaded tree in scan radius."),
            task("creative.place-pillar", "creative", "Place a bounded three-block stone pillar and verify every block.", "creative",
                    List.of("minecraft.world.blocks.write", "minecraft.world.blocks.read"), "Pillar readback must contain stone."),
            task("creative.clear-pillar", "creative", "Remove a bounded three-block pillar and verify air readback.", "creative",
                    List.of("minecraft.world.blocks.write", "minecraft.world.blocks.read"), "Clear readback must contain air."),
            task("navigation.reach-waypoint", "navigation", "Move under a finite lease and verify fresh player position after movement.", "survival",
                    List.of("minecraft.player.state.read", "minecraft.player.move", "minecraft.input.release-all"), "Position observation must exist after movement."),
            task("combat.attack-nearest", "combat", "Inspect nearby entities, queue an attack, then observe fresh player state.", "survival",
                    List.of("minecraft.entity.nearby.read", "minecraft.player.interact", "minecraft.player.state.read"), "Attack is queued only; a native kill confirmation is required for success."),
            task("commands.set-day", "commands", "Execute time set day and read back structured server time.", "creative",
                    List.of("minecraft.command.execute", "minecraft.server.info.read"), "Server info dayTime must be observed after command."),
            task("tools.inspect-nearby", "tools", "Scan a bounded region and nearby entities without mutation.", "survival",
                    List.of("minecraft.world.region.scan", "minecraft.entity.nearby.read"), "Both bounded observations must succeed."),
            task("failure.stale-ui-snapshot", "failure", "Prove stale guarded UI state fails closed.", "any",
                    List.of("minecraft.ui.state.read", "minecraft.ui.click"), "Expected error is stale snapshot, never success."));

    private GoalTaskCatalog() {
    }

    public static List<TaskDefinition> tasks() {
        return TASKS;
    }

    public static Optional<TaskDefinition> find(String id) {
        return TASKS.stream().filter(task -> task.id().equalsIgnoreCase(id)).findFirst();
    }

    public record TaskDefinition(String id, String category, String description, String gameMode,
                                 List<String> requiredCapabilities, String successContract) {
        public Map<String, Object> toMap() {
            return Map.of("id", id, "category", category, "description", description,
                    "gameMode", gameMode, "requiredCapabilities", requiredCapabilities,
                    "successContract", successContract);
        }
    }

    private static TaskDefinition task(String id, String category, String description, String gameMode,
                                       List<String> capabilities, String success) {
        return new TaskDefinition(id, category, description, gameMode, List.copyOf(capabilities), success);
    }
}
