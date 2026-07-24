// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeCraftPlannerTest {
    @Test
    void oakPlanksShapelessFromLogPlansASingleCellPickup() {
        var ingredients = List.of(new NeoForgeCraftPlanner.Ingredient("minecraft:oak_log", new int[]{1}));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 1,
                label -> new NeoForgeCraftPlanner.SourceSlot(9, 4));

        assertTrue(result.success());
        assertEquals(List.of(
                new NeoForgeCraftPlanner.ClickOp(9, 0, ClickType.PICKUP, "pick-up-minecraft:oak_log"),
                new NeoForgeCraftPlanner.ClickOp(1, 1, ClickType.PICKUP, "place-minecraft:oak_log-1"),
                new NeoForgeCraftPlanner.ClickOp(9, 0, ClickType.PICKUP, "return-unused-minecraft:oak_log"),
                new NeoForgeCraftPlanner.ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-output")
        ), result.clicks());
    }

    @Test
    void stickPlansBothCellsOfItsSingleColumnFromTheSamePlankStack() {
        // Vanilla's real stick.json is crafting_shaped with pattern ["#","#"] (one column, two
        // rows) - in the 2x2 personal grid that is the left column, slots {1,3}.
        var ingredients = List.of(new NeoForgeCraftPlanner.Ingredient("minecraft:planks", new int[]{1, 3}));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 1,
                label -> new NeoForgeCraftPlanner.SourceSlot(10, 8));

        assertTrue(result.success());
        assertEquals(List.of(
                new NeoForgeCraftPlanner.ClickOp(10, 0, ClickType.PICKUP, "pick-up-minecraft:planks"),
                new NeoForgeCraftPlanner.ClickOp(1, 1, ClickType.PICKUP, "place-minecraft:planks-1"),
                new NeoForgeCraftPlanner.ClickOp(3, 1, ClickType.PICKUP, "place-minecraft:planks-3"),
                new NeoForgeCraftPlanner.ClickOp(10, 0, ClickType.PICKUP, "return-unused-minecraft:planks"),
                new NeoForgeCraftPlanner.ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-output")
        ), result.clicks());
    }

    @Test
    void craftingTableFillsAllFourPersonalGridCellsInOrder() {
        // Vanilla's real crafting_table.json is crafting_shaped with pattern ["##","##"] - the
        // full 2x2 personal grid, slots {1,2,3,4}.
        var ingredients = List.of(new NeoForgeCraftPlanner.Ingredient("minecraft:planks", new int[]{1, 2, 3, 4}));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 1,
                label -> new NeoForgeCraftPlanner.SourceSlot(9, 4));

        assertTrue(result.success());
        var placedSlots = result.clicks().stream()
                .filter(click -> click.button() == 1)
                .map(NeoForgeCraftPlanner.ClickOp::slot).toList();
        assertEquals(List.of(1, 2, 3, 4), placedSlots);
    }

    @Test
    void woodenAxeMatchesTheAsymmetricShapeAlreadyVerifiedInStoneToolsetGoal() {
        // Vanilla's real wooden_axe.json is crafting_shaped with pattern ["XX","X#"," #"] against
        // the 3x3 table - this must land on exactly the same slots as
        // NeoForgeStoneToolsetGoal.craftWoodenAxe() (planks 1,2,4 / sticks 5,8), independently
        // re-derived here from the recipe pattern itself rather than copied from that class.
        var pattern = new String[]{"XX", "X#", " #"};
        var planks = NeoForgeCraftPlanner.patternToSlots(pattern, 'X', 3);
        var sticks = NeoForgeCraftPlanner.patternToSlots(pattern, '#', 3);

        assertArrayEquals(new int[]{1, 2, 4}, planks);
        assertArrayEquals(new int[]{5, 8}, sticks);

        var ingredients = List.of(
                new NeoForgeCraftPlanner.Ingredient("minecraft:planks", planks),
                new NeoForgeCraftPlanner.Ingredient("minecraft:stick", sticks));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 1, label -> switch (label) {
            case "minecraft:planks" -> new NeoForgeCraftPlanner.SourceSlot(11, 3);
            case "minecraft:stick" -> new NeoForgeCraftPlanner.SourceSlot(12, 2);
            default -> null;
        });
        assertTrue(result.success());
        var placedByLabel = result.clicks().stream()
                .filter(click -> click.button() == 1)
                .map(NeoForgeCraftPlanner.ClickOp::slot).toList();
        assertEquals(List.of(1, 2, 4, 5, 8), placedByLabel);
    }

    @Test
    void mirroringTheAxeSlotsReflectsEachCellAboutTheGridsOwnCenterColumn() {
        // The asymmetric axe shape is real proof this geometry isn't hardcoded for one fixed
        // orientation: mirroring the already-verified slots must move the off-center planks
        // (columns 0) to the opposite column (2) while leaving the sticks - already in the
        // grid's center column - untouched.
        assertArrayEquals(new int[]{3, 2, 6}, NeoForgeCraftPlanner.mirrorSlots(new int[]{1, 2, 4}, 3));
        assertArrayEquals(new int[]{5, 8}, NeoForgeCraftPlanner.mirrorSlots(new int[]{5, 8}, 3));
    }

    @Test
    void missingIngredientProducesAShortfallMapAndNeverPartialClicks() {
        // Vanilla's real wooden_pickaxe.json needs planks at {1,2,3} and sticks at {5,8}; here
        // only 1 of the 3 required planks and none of the 2 required sticks are available.
        var ingredients = List.of(
                new NeoForgeCraftPlanner.Ingredient("minecraft:planks", new int[]{1, 2, 3}),
                new NeoForgeCraftPlanner.Ingredient("minecraft:stick", new int[]{5, 8}));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 1, label -> switch (label) {
            case "minecraft:planks" -> new NeoForgeCraftPlanner.SourceSlot(11, 1);
            default -> null;
        });

        assertTrue(result.clicks().isEmpty(), "a short recipe must never partial-click");
        assertEquals(Map.of("minecraft:planks", 2, "minecraft:stick", 2), result.missing());
    }

    @Test
    void multiBatchCraftingRepeatsEachCellOnceForEveryRequestedBatch() {
        var ingredients = List.of(new NeoForgeCraftPlanner.Ingredient("minecraft:planks", new int[]{1, 3}));
        var result = NeoForgeCraftPlanner.plan(ingredients, 0, 3,
                label -> new NeoForgeCraftPlanner.SourceSlot(9, 6));

        assertTrue(result.success());
        var placedSlots = result.clicks().stream()
                .filter(click -> click.button() == 1)
                .map(NeoForgeCraftPlanner.ClickOp::slot).toList();
        assertEquals(List.of(1, 1, 1, 3, 3, 3), placedSlots);
    }
}
