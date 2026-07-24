// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Backs {@code minecraft.inventory.craft}: resolves the requested item against a small built-in
 * recipe table covering the vanilla wood-tier progression (planks from any log, sticks, a
 * crafting table, wooden tools, a torch, a chest, and a furnace) rather than driving the live
 * {@code RecipeManager} generically - see the class documentation on {@link NeoForgeCraftPlanner}
 * for the click-plan mechanics this reuses from {@code NeoForgeStoneToolsetGoal}'s proven
 * substrate. A 2x2 recipe crafts directly in the personal inventory grid; a 3x3 recipe needs a
 * crafting table - one is found within 8 blocks, else placed from inventory, else (one level of
 * recursion) crafted from planks first.
 */
final class NeoForgeCraftGoal {
    private static final int MAX_SCAN_RADIUS = 8;
    private static final int SCREEN_STALL_TICKS = 200;
    private static final int PLACE_STALL_TICKS = 200;
    private static final float REACH = 4.5F;

    private enum Stage {
        RESOLVE,
        LOCATE_TABLE, APPROACH_TABLE, OPEN_TABLE,
        BOOTSTRAP_OPEN_INVENTORY, BOOTSTRAP_CRAFT, BOOTSTRAP_CLOSE_INVENTORY,
        FIND_PLACEMENT, EQUIP_TABLE_ITEM, PLACE_TABLE,
        OPEN_INVENTORY,
        CRAFTING,
        CLOSE_SCREEN,
        DONE
    }

    private record RecipeIngredient(String label, int[] cellSlots, Predicate<ItemStack> matcher) {
    }

    private record RecipeDef(int gridSize, int outputPerCraft, List<RecipeIngredient> ingredients) {
        boolean requiresTable() {
            return gridSize == 9;
        }
    }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final String requestedItemId;
    private final int requestedCount;
    private final boolean useNearbyTable;
    private final boolean placeTableIfNeeded;
    private final int timeoutTicks;

    private Stage stage = Stage.RESOLVE;
    private int ticks;
    private Item resultItem;
    private RecipeDef recipe;
    private int craftsNeeded;
    private boolean initialResultCountCaptured;
    private int initialResultCount;
    private int craftedCount;

    private BlockPos tablePosition;
    private NeoForgeGotoMovement movement;
    private int screenWaitTicks;
    private int placeTicks;
    private String pendingReason;
    private Map<String, Integer> pendingMissing = Map.of();

    private final ArrayDeque<NeoForgeCraftPlanner.ClickOp> clicks = new ArrayDeque<>();
    private Runnable clicksComplete;
    private int clickDelay;

    NeoForgeCraftGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.requestedItemId = normalizeItemId(input.get("item"));
        this.requestedCount = (int) clamp(numberOrDefault(input, "count", 1), 1, 64);
        this.useNearbyTable = !Boolean.FALSE.equals(input.get("useNearbyTable"));
        this.placeTableIfNeeded = !Boolean.FALSE.equals(input.get("placeTableIfNeeded"));
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 2400), 100, 6000);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++ticks > timeoutTicks) {
                complete(client, "timeout", Map.of());
                return;
            }
            if (client.level == null || client.player == null || client.gameMode == null) {
                return;
            }
            var screenAllowed = stage == Stage.OPEN_INVENTORY || stage == Stage.BOOTSTRAP_OPEN_INVENTORY
                    || stage == Stage.BOOTSTRAP_CLOSE_INVENTORY || stage == Stage.OPEN_TABLE
                    || stage == Stage.CRAFTING || stage == Stage.CLOSE_SCREEN || stage == Stage.BOOTSTRAP_CRAFT;
            if (!screenAllowed && client.screen != null) return;
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.inventory.craft requires survival or adventure mode");
            }
            if (!clicks.isEmpty() || clicksComplete != null) {
                tickClicks(client);
                return;
            }
            switch (stage) {
                case RESOLVE -> tickResolve(client);
                case LOCATE_TABLE -> tickLocateTable(client);
                case APPROACH_TABLE -> tickApproachTable(client);
                case OPEN_TABLE -> tickOpenTable(client);
                case BOOTSTRAP_OPEN_INVENTORY -> tickOpenInventory(client, Stage.BOOTSTRAP_CRAFT);
                case BOOTSTRAP_CRAFT -> tickBootstrapCraft(client);
                case BOOTSTRAP_CLOSE_INVENTORY -> tickCloseScreen(client, Stage.FIND_PLACEMENT);
                case FIND_PLACEMENT -> tickFindPlacement(client);
                case EQUIP_TABLE_ITEM -> tickEquipTableItem(client);
                case PLACE_TABLE -> tickPlaceTable(client);
                case OPEN_INVENTORY -> tickOpenInventory(client, Stage.CRAFTING);
                case CRAFTING -> tickCrafting(client);
                case CLOSE_SCREEN -> tickFinalCloseScreen(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Recipe resolution
    // ---------------------------------------------------------------------------------------

    private void tickResolve(Minecraft client) {
        var location = ResourceLocation.tryParse(requestedItemId);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            complete(client, "unsupported-item", Map.of());
            return;
        }
        resultItem = BuiltInRegistries.ITEM.get(location);
        recipe = resolveRecipe(requestedItemId);
        if (recipe == null) {
            complete(client, "unsupported-item", Map.of());
            return;
        }
        craftsNeeded = Math.max(1, ceilDiv(requestedCount, recipe.outputPerCraft()));
        stage = recipe.requiresTable() ? Stage.LOCATE_TABLE : Stage.OPEN_INVENTORY;
    }

    // ---------------------------------------------------------------------------------------
    // Crafting-table acquisition (3x3 recipes only)
    // ---------------------------------------------------------------------------------------

    private void tickLocateTable(Minecraft client) {
        var found = useNearbyTable ? findNearbyCraftingTable(client) : null;
        if (found != null) {
            tablePosition = found;
            movement = null;
            stage = Stage.APPROACH_TABLE;
            return;
        }
        if (hasItem(client.player, Items.CRAFTING_TABLE)) {
            stage = Stage.FIND_PLACEMENT;
            return;
        }
        if (!placeTableIfNeeded) {
            complete(client, "no-crafting-table", Map.of());
            return;
        }
        if (countTag(client.player, ItemTags.PLANKS) >= 4) {
            stage = Stage.BOOTSTRAP_OPEN_INVENTORY;
            return;
        }
        complete(client, "no-crafting-table", Map.of());
    }

    private BlockPos findNearbyCraftingTable(Minecraft client) {
        var level = client.level;
        var center = client.player.blockPosition();
        BlockPos nearest = null;
        var nearestDistSqr = Double.MAX_VALUE;
        for (var x = -MAX_SCAN_RADIUS; x <= MAX_SCAN_RADIUS; x++) {
            for (var y = -MAX_SCAN_RADIUS; y <= MAX_SCAN_RADIUS; y++) {
                for (var z = -MAX_SCAN_RADIUS; z <= MAX_SCAN_RADIUS; z++) {
                    var pos = center.offset(x, y, z);
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
                    var distSqr = pos.distSqr(center);
                    if (distSqr < nearestDistSqr) {
                        nearestDistSqr = distSqr;
                        nearest = pos.immutable();
                    }
                }
            }
        }
        return nearest;
    }

    private void tickApproachTable(Minecraft client) {
        if (movement == null) movement = new NeoForgeGotoMovement(false, true);
        var outcome = movement.tick(client, client.player, tablePosition, 2.5);
        switch (outcome) {
            case ARRIVED -> {
                movement.releaseInput(client);
                movement = null;
                screenWaitTicks = 0;
                stage = Stage.OPEN_TABLE;
            }
            case NO_ROUTE, MUTATION_FAILURE -> complete(client, "no-crafting-table", Map.of());
            case MOVING -> { }
        }
    }

    private void tickOpenTable(Minecraft client) {
        if (client.screen instanceof CraftingScreen) {
            stage = Stage.CRAFTING;
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen before opening crafting table");
        var player = client.player;
        lookAt(player, tablePosition);
        var hit = player.pick(REACH, 0.0F, false);
        var aimed = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(tablePosition);
        if (aimed && screenWaitTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
        }
        screenWaitTicks++;
        if (screenWaitTicks > SCREEN_STALL_TICKS) {
            throw new IllegalStateException("normal use input did not open the crafting table");
        }
    }

    // ---------------------------------------------------------------------------------------
    // One-level-recursion bootstrap: craft a crafting table from planks, then place it
    // ---------------------------------------------------------------------------------------

    private void tickBootstrapCraft(Minecraft client) {
        if (!(client.screen instanceof InventoryScreen)) throw new IllegalStateException("inventory screen closed unexpectedly");
        if (hasItem(client.player, Items.CRAFTING_TABLE)) {
            stage = Stage.BOOTSTRAP_CLOSE_INVENTORY;
            return;
        }
        var tableRecipe = resolveRecipe("minecraft:crafting_table");
        var plan = buildPlan(client, tableRecipe, 1);
        if (!plan.success()) {
            pendingReason = "missing-ingredients";
            pendingMissing = plan.missing();
            stage = Stage.CLOSE_SCREEN;
            return;
        }
        startClicks(plan.clicks(), () -> {
            if (!hasItem(client.player, Items.CRAFTING_TABLE)) {
                throw new IllegalStateException("bootstrap crafting-table recipe did not produce a crafting table");
            }
            stage = Stage.BOOTSTRAP_CLOSE_INVENTORY;
        });
    }

    private void tickFindPlacement(Minecraft client) {
        var player = client.player;
        var origin = player.blockPosition();
        for (var direction : Direction.values()) {
            if (direction.getAxis().isVertical()) continue;
            var candidate = origin.relative(direction);
            if (isClearPlacement(client, candidate)) {
                tablePosition = candidate.immutable();
                stage = Stage.EQUIP_TABLE_ITEM;
                return;
            }
        }
        complete(client, "no-crafting-table", Map.of());
    }

    private boolean isClearPlacement(Minecraft client, BlockPos candidate) {
        var player = client.player;
        var state = client.level.getBlockState(candidate);
        var supportPos = candidate.below();
        var support = client.level.getBlockState(supportPos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()
                || !Block.isShapeFullBlock(support.getCollisionShape(client.level, supportPos))
                || player.getBoundingBox().intersects(new AABB(candidate))) {
            return false;
        }
        var supportPoint = new Vec3(supportPos.getX() + 0.5, supportPos.getY() + 1.0 - 0.001, supportPos.getZ() + 0.5);
        var hit = client.level.clip(new ClipContext(player.getEyePosition(), supportPoint,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(supportPos)
                && player.getEyePosition().distanceTo(supportPoint) <= 4.25;
    }

    private void tickEquipTableItem(Minecraft client) {
        var player = client.player;
        var slot = hotbarSlot(player, Items.CRAFTING_TABLE);
        if (slot < 0) throw new IllegalStateException("crafting table is no longer present to equip");
        if (player.getInventory().selected != slot) {
            KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
            return;
        }
        placeTicks = 0;
        stage = Stage.PLACE_TABLE;
    }

    private void tickPlaceTable(Minecraft client) {
        if (client.level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
            screenWaitTicks = 0;
            stage = Stage.OPEN_TABLE;
            return;
        }
        var player = client.player;
        var support = tablePosition.below();
        var supportPoint = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
        lookAt(player, support);
        var hit = player.pick(REACH, 0.0F, false);
        var aimedAtSupport = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support);
        if (aimedAtSupport && placeTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
        }
        placeTicks++;
        if (placeTicks > PLACE_STALL_TICKS) {
            throw new IllegalStateException("normal use input did not place the crafting table; eyeDistance="
                    + rounded(player.getEyePosition().distanceTo(supportPoint)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Screen open/close (2x2 personal inventory) and generic crafting execution
    // ---------------------------------------------------------------------------------------

    private void tickOpenInventory(Minecraft client, Stage next) {
        if (client.screen instanceof InventoryScreen) {
            screenWaitTicks = 0;
            stage = next;
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen before opening inventory");
        KeyMapping.click(client.options.keyInventory.getKey());
        if (++screenWaitTicks > SCREEN_STALL_TICKS) throw new IllegalStateException("inventory screen did not open");
    }

    private void tickCloseScreen(Minecraft client, Stage next) {
        if (client.screen == null) {
            screenWaitTicks = 0;
            stage = next;
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        if (++screenWaitTicks > SCREEN_STALL_TICKS) throw new IllegalStateException("screen did not close");
    }

    /** Terminal variant of {@link #tickCloseScreen}: once the screen is closed, completes the
     * goal instead of advancing to another stage - either with the pending failure reason set by
     * a missing-ingredient check, or with the normal successful-craft result. */
    private void tickFinalCloseScreen(Minecraft client) {
        if (client.screen != null) {
            client.screen.keyPressed(256, 0, 0);
            if (++screenWaitTicks > SCREEN_STALL_TICKS) throw new IllegalStateException("screen did not close");
            return;
        }
        if (pendingReason != null) {
            complete(client, pendingReason, pendingMissing);
        } else {
            complete(client, "complete", Map.of());
        }
    }

    private void tickCrafting(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?>)) {
            throw new IllegalStateException("required visible container is not open for crafting");
        }
        if (resultItem == null) throw new IllegalStateException("craft goal reached crafting stage without a resolved item");
        if (!initialResultCountCaptured) {
            initialResultCount = countItem(client.player, resultItem);
            initialResultCountCaptured = true;
        }
        var plan = buildPlan(client, recipe, craftsNeeded);
        if (!plan.success()) {
            pendingReason = "missing-ingredients";
            pendingMissing = plan.missing();
            stage = Stage.CLOSE_SCREEN;
            return;
        }
        startClicks(plan.clicks(), () -> {
            craftedCount = countItem(client.player, resultItem) - initialResultCount;
            stage = Stage.CLOSE_SCREEN;
        });
    }

    private NeoForgeCraftPlanner.PlanResult buildPlan(Minecraft client, RecipeDef recipeDef, int batches) {
        var screen = (AbstractContainerScreen<?>) client.screen;
        var ingredients = recipeDef.ingredients().stream()
                .map(ingredient -> new NeoForgeCraftPlanner.Ingredient(ingredient.label(), ingredient.cellSlots()))
                .toList();
        return NeoForgeCraftPlanner.plan(ingredients, 0, batches,
                label -> findSource(screen, recipeDef.ingredients(), label));
    }

    private static NeoForgeCraftPlanner.SourceSlot findSource(AbstractContainerScreen<?> screen,
                                                              List<RecipeIngredient> ingredients, String label) {
        var matcher = ingredients.stream().filter(ingredient -> ingredient.label().equals(label))
                .findFirst().map(RecipeIngredient::matcher).orElse(null);
        if (matcher == null) return null;
        var slots = screen.getMenu().slots;
        var start = screen instanceof InventoryScreen ? 9 : 10;
        for (var index = start; index < slots.size(); index++) {
            var stack = slots.get(index).getItem();
            if (!stack.isEmpty() && matcher.test(stack)) {
                return new NeoForgeCraftPlanner.SourceSlot(index, stack.getCount());
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Click execution (duplicated from NeoForgeStoneToolsetGoal by design)
    // ---------------------------------------------------------------------------------------

    private void startClicks(List<NeoForgeCraftPlanner.ClickOp> sequence, Runnable complete) {
        if (!clicks.isEmpty() || clicksComplete != null) throw new IllegalStateException("click sequence already active");
        clicks.addAll(sequence);
        clicksComplete = complete;
        clickDelay = 2;
    }

    private void tickClicks(Minecraft client) {
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        if (!clicks.isEmpty()) {
            var click = clicks.removeFirst();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new IllegalStateException("container screen closed mid-click-sequence");
            }
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, click.slot(), click.button(),
                    click.type(), client.player);
            clickDelay = 7;
            return;
        }
        var complete = clicksComplete;
        clicksComplete = null;
        if (complete != null) complete.run();
    }

    // ---------------------------------------------------------------------------------------
    // Recipe table
    // ---------------------------------------------------------------------------------------

    private static RecipeDef resolveRecipe(String itemId) {
        var builtin = BUILTIN_RECIPES.get(itemId);
        if (builtin != null) return builtin;
        if (itemId.startsWith("minecraft:") && itemId.endsWith("_planks")) {
            var species = itemId.substring("minecraft:".length(), itemId.length() - "_planks".length());
            var logsTag = speciesLogsTag(species);
            if (logsTag == null) return null;
            return new RecipeDef(4, 4, List.of(
                    new RecipeIngredient(itemId + "-logs", new int[]{1}, stack -> stack.is(logsTag))));
        }
        return null;
    }

    private static TagKey<Item> speciesLogsTag(String species) {
        return switch (species) {
            case "oak" -> ItemTags.OAK_LOGS;
            case "spruce" -> ItemTags.SPRUCE_LOGS;
            case "birch" -> ItemTags.BIRCH_LOGS;
            case "jungle" -> ItemTags.JUNGLE_LOGS;
            case "acacia" -> ItemTags.ACACIA_LOGS;
            case "dark_oak" -> ItemTags.DARK_OAK_LOGS;
            case "mangrove" -> ItemTags.MANGROVE_LOGS;
            case "cherry" -> ItemTags.CHERRY_LOGS;
            default -> null;
        };
    }

    private static final Map<String, RecipeDef> BUILTIN_RECIPES = buildRecipeTable();

    private static Map<String, RecipeDef> buildRecipeTable() {
        var table = new LinkedHashMap<String, RecipeDef>();
        Predicate<ItemStack> planks = stack -> stack.is(ItemTags.PLANKS);
        Predicate<ItemStack> stick = stack -> stack.is(Items.STICK);
        Predicate<ItemStack> coalOrCharcoal = stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
        Predicate<ItemStack> stoneCraftingMaterial = stack -> stack.is(ItemTags.STONE_CRAFTING_MATERIALS);

        // Personal 2x2 grid (slots 1,2 top row; 3,4 bottom row - InventoryMenu.CRAFT_SLOT_START).
        table.put("minecraft:stick", new RecipeDef(4, 4, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 3}, planks))));
        table.put("minecraft:crafting_table", new RecipeDef(4, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 2, 3, 4}, planks))));
        table.put("minecraft:torch", new RecipeDef(4, 4, List.of(
                new RecipeIngredient("minecraft:coal_or_charcoal", new int[]{1}, coalOrCharcoal),
                new RecipeIngredient("minecraft:stick", new int[]{3}, stick))));

        // 3x3 crafting-table grid (row-major slots 1-9), verified directly against the real
        // vanilla recipe JSON patterns - see NeoForgeCraftPlannerTest for the derivation, which
        // matches NeoForgeStoneToolsetGoal's already-shipped pickaxe/axe/sword/shovel/furnace
        // recipes exactly.
        table.put("minecraft:wooden_pickaxe", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 2, 3}, planks),
                new RecipeIngredient("minecraft:stick", new int[]{5, 8}, stick))));
        table.put("minecraft:wooden_axe", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 2, 4}, planks),
                new RecipeIngredient("minecraft:stick", new int[]{5, 8}, stick))));
        table.put("minecraft:wooden_sword", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 4}, planks),
                new RecipeIngredient("minecraft:stick", new int[]{7}, stick))));
        table.put("minecraft:wooden_shovel", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1}, planks),
                new RecipeIngredient("minecraft:stick", new int[]{4, 7}, stick))));
        table.put("minecraft:wooden_hoe", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 2}, planks),
                new RecipeIngredient("minecraft:stick", new int[]{5, 8}, stick))));
        table.put("minecraft:chest", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:planks", new int[]{1, 2, 3, 4, 6, 7, 8, 9}, planks))));
        table.put("minecraft:furnace", new RecipeDef(9, 1, List.of(
                new RecipeIngredient("minecraft:stone_crafting_materials", new int[]{1, 2, 3, 4, 6, 7, 8, 9},
                        stoneCraftingMaterial))));
        return Map.copyOf(table);
    }

    // ---------------------------------------------------------------------------------------
    // Completion and shared helpers
    // ---------------------------------------------------------------------------------------

    private void complete(Minecraft client, String reason, Map<String, Integer> missing) {
        releaseInput(client);
        var output = new LinkedHashMap<String, Object>();
        output.put("item", requestedItemId);
        output.put("requestedCount", requestedCount);
        output.put("craftedCount", Math.max(0, craftedCount));
        output.put("complete", craftedCount >= requestedCount);
        output.put("reason", reason);
        if (!missing.isEmpty()) output.put("missing", Map.copyOf(missing));
        result.complete(Map.copyOf(output));
        stage = Stage.DONE;
    }

    private void releaseInput(Minecraft client) {
        if (movement != null) movement.releaseInput(client);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        var eye = player.getEyePosition();
        var dx = target.getX() + 0.5 - eye.x;
        var dy = target.getY() + 0.5 - eye.y;
        var dz = target.getZ() + 0.5 - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private static boolean hasItem(Player player, Item item) {
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item)) return true;
        }
        return false;
    }

    private static int countItem(Player player, Item item) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countTag(Player player, TagKey<Item> tag) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(tag)) count += stack.getCount();
        }
        return count;
    }

    private static int hotbarSlot(Player player, Item item) {
        for (var slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(item)) return slot;
        return -1;
    }

    private static String normalizeItemId(Object value) {
        if (value == null) throw new IllegalArgumentException("item is required");
        var text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) throw new IllegalArgumentException("item must not be blank");
        return text.contains(":") ? text : "minecraft:" + text;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
