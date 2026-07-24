// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.world.inventory.ClickType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure click-plan generator backing {@link NeoForgeCraftGoal}: turns a fixed recipe grid layout
 * (ingredients and the exact cells each occupies) into an ordered {@link ClickOp} list executed
 * mechanically via {@code handleInventoryMouseClick}, exactly like
 * {@code NeoForgeStoneToolsetGoal}'s own already-shipped {@code craftGeneric} - pick up an
 * ingredient's source stack, distribute one unit per required cell per craft batch via
 * right-clicks, return the unused remainder, then quick-move the result. No Minecraft registry or
 * world state is touched here, so this is directly unit-testable - see
 * {@code NeoForgeCraftPlannerTest}.
 */
final class NeoForgeCraftPlanner {
    private NeoForgeCraftPlanner() {
    }

    /** One required ingredient: {@code label} identifies it (used both as the missing-map key and
     * in click labels), {@code cellSlots} are the distinct grid cells it must occupy for one
     * craft. */
    record Ingredient(String label, int[] cellSlots) {
    }

    /** One already-located source stack for an ingredient in the currently open container:
     * {@code slot} is where it sits, {@code available} is its current stack count. */
    record SourceSlot(int slot, int available) {
    }

    record ClickOp(int slot, int button, ClickType type, String label) {
    }

    /** {@code missing} is empty exactly when {@code clicks} is the full executable plan; a
     * non-empty {@code missing} always means {@code clicks} is empty - the goal never
     * partial-clicks a recipe it cannot fully complete. Each {@code missing} value is the
     * outstanding shortfall (units still needed beyond what was found), not the full requirement. */
    record PlanResult(List<ClickOp> clicks, Map<String, Integer> missing) {
        boolean success() {
            return missing.isEmpty();
        }
    }

    /**
     * Plans {@code craftsNeeded} batches of one recipe. For each ingredient, {@code sourceLookup}
     * resolves its current source slot and available count (or {@code null} if the ingredient is
     * entirely absent from the open container); every ingredient is checked before any click is
     * planned, so a shortfall in one ingredient never leaves a partial plan for the others.
     *
     * @param resultSlot the container's crafting-result slot (always 0 for both the personal
     *                   2x2 grid and a 3x3 crafting table).
     */
    static PlanResult plan(List<Ingredient> ingredients, int resultSlot, int craftsNeeded,
                           Function<String, SourceSlot> sourceLookup) {
        if (craftsNeeded <= 0) throw new IllegalArgumentException("craftsNeeded must be positive");
        var missing = new LinkedHashMap<String, Integer>();
        var clicks = new ArrayList<ClickOp>();
        for (var ingredient : ingredients) {
            var needed = craftsNeeded * ingredient.cellSlots().length;
            var source = sourceLookup.apply(ingredient.label());
            var available = source == null ? 0 : source.available();
            if (available < needed) {
                missing.put(ingredient.label(), needed - available);
                continue;
            }
            clicks.add(new ClickOp(source.slot(), 0, ClickType.PICKUP, "pick-up-" + ingredient.label()));
            for (var slot : ingredient.cellSlots()) {
                for (var round = 0; round < craftsNeeded; round++) {
                    clicks.add(new ClickOp(slot, 1, ClickType.PICKUP, "place-" + ingredient.label() + "-" + slot));
                }
            }
            clicks.add(new ClickOp(source.slot(), 0, ClickType.PICKUP, "return-unused-" + ingredient.label()));
        }
        if (!missing.isEmpty()) {
            return new PlanResult(List.of(), Map.copyOf(missing));
        }
        clicks.add(new ClickOp(resultSlot, 0, ClickType.QUICK_MOVE, "take-crafted-output"));
        return new PlanResult(List.copyOf(clicks), Map.of());
    }

    /**
     * Derives the exact 1-indexed, row-major menu slots a recipe {@code pattern} symbol occupies
     * once left-and-top aligned inside a {@code gridWidth}-wide crafting grid (2 for the personal
     * inventory, 3 for a crafting table) - e.g. the wooden axe's {@code ["XX","X#"," #"]} pattern
     * resolves {@code 'X'} to {1,2,4} and {@code '#'} to {5,8} in a 3-wide grid, matching this
     * codebase's own already-shipped, live-verified stone-tool recipes in
     * {@code NeoForgeStoneToolsetGoal}. Package-private and pure for direct testing.
     */
    static int[] patternToSlots(String[] pattern, char symbol, int gridWidth) {
        var slots = new ArrayList<Integer>();
        for (var row = 0; row < pattern.length; row++) {
            var line = pattern[row];
            for (var col = 0; col < line.length(); col++) {
                if (line.charAt(col) == symbol) {
                    slots.add(row * gridWidth + col + 1);
                }
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The horizontally mirrored equivalent of {@code slots} within a {@code gridWidth}-wide grid -
     * every slot's column is reflected about the grid's vertical center while its row is
     * unchanged. Used only to prove the pattern-to-slot geometry above is a genuine, orientation-
     * independent derivation (real shaped-recipe matching accepts a mirrored grid placement, not
     * just the one canonical orientation this planner always writes) - package-private and pure
     * for direct testing.
     */
    static int[] mirrorSlots(int[] slots, int gridWidth) {
        var mirrored = new int[slots.length];
        for (var index = 0; index < slots.length; index++) {
            var slot = slots[index] - 1;
            var row = slot / gridWidth;
            var col = slot % gridWidth;
            mirrored[index] = row * gridWidth + (gridWidth - 1 - col) + 1;
        }
        return mirrored;
    }
}
