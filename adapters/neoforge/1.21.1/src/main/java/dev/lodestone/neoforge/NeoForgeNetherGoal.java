// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Realtime-first survival Nether workflow.
 *
 * <p>The explicit setup phase only grants the bounded portal materials and suppresses command
 * feedback. The portal frame, ignition, movement, and dimension transition are all performed and
 * read back through ordinary client input. The setup command trace is returned so callers can see
 * exactly which parts were prepared.</p>
 */
final class NeoForgeNetherGoal {
    private static final int MAX_TOTAL_TICKS = 9_600;
    private static final int MAX_SEARCH_ATTEMPTS = 18;
    private static final int LOGS_REQUIRED = 5;
    private static final int COBBLESTONE_REQUIRED = 11;
    private static final int RAW_IRON_REQUIRED = 4;

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final boolean suppressInGameMessages;
    private final NeoForgeGoalPolicy policy;
    private final List<String> setupCommands = new ArrayList<>();
    private final List<String> inputActions = new ArrayList<>();
    private final List<String> safetyDiagnostics = new ArrayList<>();
    private final NeoForgeGoalSupervisor supervisor;
    private final List<Placement> placements = new ArrayList<>();
    private final java.util.ArrayDeque<ClickOp> clicks = new java.util.ArrayDeque<>();
    private final List<BlockPos> portalScaffolds = new ArrayList<>();

    private Stage stage = Stage.WAIT_WORLD;
    private Site site;
    private BlockPos lootChest;
    private Runnable clicksComplete;
    private int clickDelay;
    private int waitTicks;
    private int stageTicks;
    private int totalTicks;
    private int placementIndex;
    private int placementAttempts;
    private int lightAttempts;
    private int portalScaffoldIndex;
    private int portalTargetIndex;
    private int portalPhase;
    private int clearIndex;
    private int inGameMessagesEmitted;
    private int frameBlocksPlaced;
    private boolean survival;
    private boolean freshWorld;
    private boolean teleportedToBuildSite;
    private boolean manualPortalBuilt;
    private boolean portalLit;
    private boolean portalBlocksObserved;
    private boolean enteredPortal;
    private boolean reachedNether;
    private boolean recipeStarted;
    private boolean smeltLoaded;
    private boolean smeltCollected;
    private boolean woodPickVerificationPending;
    private int woodPickAttempts;
    private BlockPos placementVantage;
    private BlockPos navigationDestination;
    private double navigationLastDistance = Double.POSITIVE_INFINITY;
    private int navigationStuckTicks;
    private List<BlockPos> navigationPath = List.of();
    private int navigationIndex;
    private Stage afterTableClose = Stage.EQUIP_WOOD_PICK;
    private Stage nextTableStage = Stage.CRAFT_WOOD_PICK;
    private int searchAttempts;
    private int mineIndex;
    private int stoneMineIndex;
    private int ironMineIndex;
    private int gravelMineIndex;
    private int handMinedLogs;
    private int cobblestoneMined;
    private int rawIronMined;
    private int gravelMined;
    private int flintObserved;
    private int smeltTicks;
    private int interactionAttempts;
    private int toolHotbarSlot = -1;
    private int tableHotbarSlot = -1;
    private int furnaceHotbarSlot = -1;
    private int bucketHotbarSlot = -1;
    private int flintSteelHotbarSlot = -1;
    private TreePlan resourceTree;
    private BlockPos tablePosition;
    private BlockPos furnacePosition;
    private BlockPos waterSource;
    private BlockPos lavaSource;
    private BlockPos fallbackPortalBase;
    private List<BlockPos> observedStone = List.of();
    private List<BlockPos> observedIron = List.of();
    private List<BlockPos> observedGravel = List.of();
    private String worldName = "fresh-survival-world";
    private String initialDimension = "";
    private String finalDimension = "";
    private long worldGameTimeAtStart;

    NeoForgeNetherGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.suppressInGameMessages = Boolean.TRUE.equals(
                invocation.request().input().get("suppressInGameMessages"));
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
        this.supervisor = new NeoForgeGoalSupervisor(policy, inputActions, safetyDiagnostics);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("Nether goal exceeded its bounded input budget");
            }
            // Startup validation must own the tick until the player/world contract is proven.
            // Otherwise a stale movement lease from the UI transition can let the safety
            // supervisor preempt WAIT_WORLD forever, leaving the actor visibly idle at spawn.
            if (stage != Stage.WAIT_WORLD && supervisor.tick(client)) return;
            if (!clicks.isEmpty() || clicksComplete != null) {
                tickClicks(client);
                return;
            }
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            stageTicks++;
            switch (stage) {
                case WAIT_WORLD -> waitForFreshWorld(client);
                case FIND_LOOT -> findLoot(client);
                case NAVIGATE_LOOT -> navigateLoot(client);
                case LOOT_CHEST -> lootChest(client);
                case FIND_TREE -> findTree(client);
                case NAVIGATE_TREE -> navigateTree(client);
                case MINE_TREE -> mineTree(client);
                case COLLECT_TREE -> collectTree(client);
                case OPEN_INVENTORY -> openInventory(client);
                case CRAFT_PLANKS -> craftPlanks(client);
                case CRAFT_TABLE -> craftTable(client);
                case CRAFT_STICKS -> craftSticks(client);
                case CRAFT_WOOD_PICK -> craftWoodPick(client);
                case MOVE_TABLE_TO_HOTBAR -> moveTableToHotbar(client);
                case CLOSE_INVENTORY -> closeInventory(client);
                case PLACE_TABLE -> placeTable(client);
                case OPEN_TABLE -> openTable(client);
                case CRAFT_STONE_PICK -> craftStonePick(client);
                case CRAFT_FURNACE -> craftFurnace(client);
                case MOVE_WOOD_PICK_TO_HOTBAR -> moveWoodPickToHotbar(client);
                case MOVE_TOOL_TO_HOTBAR -> moveStonePickToHotbar(client);
                case MOVE_FURNACE_TO_HOTBAR -> moveFurnaceToHotbar(client);
                case CLOSE_TABLE -> closeTable(client);
                case EQUIP_WOOD_PICK -> equipTool(client, Items.WOODEN_PICKAXE);
                case EQUIP_STONE_PICK -> equipTool(client, Items.STONE_PICKAXE);
                case FIND_STONE -> findStone(client);
                case MINE_STONE -> mineStone(client);
                case FIND_IRON -> findIron(client);
                case MINE_IRON -> mineIron(client);
                case FIND_GRAVEL -> findGravel(client);
                case MINE_GRAVEL -> mineGravel(client);
                case PLACE_FURNACE -> placeFurnace(client);
                case OPEN_FURNACE -> openFurnace(client);
                case SMELT_IRON -> smeltIron(client);
                case CLOSE_FURNACE -> closeFurnace(client);
                case CRAFT_BUCKET -> craftBucket(client);
                case CRAFT_FLINT_STEEL -> craftFlintSteel(client);
                case MOVE_BUCKET_TO_HOTBAR -> moveBucketToHotbar(client);
                case MOVE_FLINT_STEEL_TO_HOTBAR -> moveFlintSteelToHotbar(client);
                case FIND_WATER -> findWater(client);
                case FIND_LAVA -> findLava(client);
                case FILL_WATER_BUCKET -> fillWaterBucket(client);
                case FILL_LAVA_BUCKET -> fillLavaBucket(client);
                case BUILD_BUCKET_PORTAL -> buildBucketPortal(client);
                case CLEAR_PORTAL_SCAFFOLD -> clearPortalScaffold(client);
                case FIND_SITE -> findSite(client);
                case BUILD_PORTAL -> buildPortal(client);
                case LIGHT_PORTAL -> lightPortal(client);
                case ENTER_PORTAL -> enterPortal(client);
                case COMPLETE_DELAY -> complete(client);
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void waitForFreshWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (stageTicks < 40) return;

        var player = client.player;
        survival = client.gameMode.getPlayerMode() == GameType.SURVIVAL;
        initialDimension = client.level.dimension().location().toString();
        worldGameTimeAtStart = client.level.getGameTime();
        freshWorld = survival && Level.OVERWORLD.location().toString().equals(initialDimension)
                && countNonEmpty(player) == 0 && worldGameTimeAtStart <= 6_000;
        var server = client.getSingleplayerServer();
        if (server != null && server.getWorldData() != null) worldName = server.getWorldData().getLevelName();
        if (!freshWorld) {
            throw new IllegalStateException("Nether goal requires a fresh empty overworld survival world; gameTime="
                    + worldGameTimeAtStart + ", dimension=" + initialDimension
                    + ", nonEmptySlots=" + countNonEmpty(player));
        }
        if (!policy.allowBlockBreaking() || !policy.allowBlockPlacing()) {
            throw new IllegalStateException("survival Nether workflow requires block breaking and placing permissions");
        }
        announce(client, "Fresh survival world ready; observing the natural ruined-portal route");
        transition(Stage.FIND_LOOT, 20);
    }

    private void findLoot(Minecraft client) {
        lootChest = findNearestChest(client, 96);
        if (lootChest != null) {
            announce(client, "Observed a natural portal chest at " + lootChest + "; walking there normally");
            navigationDestination = null;
            transition(Stage.NAVIGATE_LOOT, 12);
            return;
        }
        if (++searchAttempts > 3) {
            announce(client, "No nearby ruined-portal chest observed; starting genuine wood-and-iron survival route");
            transition(Stage.FIND_TREE, 20);
            return;
        }
        var player = requirePlayer(client);
        player.setYRot(player.getYRot() + 45.0F);
        player.setYHeadRot(player.getYRot());
        exploreSafely(client, "natural-portal-search");
        waitTicks = 20;
    }

    private void navigateLoot(Minecraft client) {
        if (navigateTo(client, lootChest, 2.8, "ruined portal chest")) {
            stopMovement(client);
            transition(Stage.LOOT_CHEST, 15);
        }
    }

    private void lootChest(Minecraft client) {
        var player = requirePlayer(client);
        if (client.screen instanceof ContainerScreen screen) {
            if (clicks.isEmpty() && clicksComplete == null) {
                var containerSlots = screen.getMenu().slots.size() - player.getInventory().getContainerSize();
                var sequence = new ArrayList<ClickOp>();
                for (int slot = 0; slot < containerSlots; slot++) {
                    if (!screen.getMenu().getSlot(slot).getItem().isEmpty()) {
                        sequence.add(new ClickOp(slot, 0, ClickType.QUICK_MOVE, "loot-portal-chest-slot-" + slot));
                    }
                }
                if (sequence.isEmpty()) throw new IllegalStateException("observed portal chest was empty");
                startClicks(sequence, () -> {
                    if (countItem(player, Items.FLINT_AND_STEEL) < 1
                            || countItem(player, Items.OBSIDIAN) < 1) {
                        throw new IllegalStateException("natural portal chest did not provide flint-and-steel and obsidian"
                                + "; flintAndSteel=" + countItem(player, Items.FLINT_AND_STEEL)
                                + ", obsidian=" + countItem(player, Items.OBSIDIAN));
                    }
                    closeContainer(client);
                    announce(client, "Looted portal materials through normal chest clicks; locating the existing frame");
                    transition(Stage.FIND_SITE, 20);
                });
            }
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening portal chest: "
                + client.screen.getClass().getName());
        lookAt(player, Vec3.atCenterOf(lootChest));
        if (stageTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-natural-portal-chest");
        }
        if (stageTicks > 180) throw new IllegalStateException("normal use input did not open the observed portal chest");
    }

    private void findTree(Minecraft client) {
        var trees = scanTreePlans(client);
        if (!trees.isEmpty()) {
            resourceTree = trees.getFirst();
            resetNavigation();
            announce(client, "Observed a natural tree at " + resourceTree.base()
                    + "; walking there to gather wood by hand");
            transition(Stage.NAVIGATE_TREE, 15);
            return;
        }
        if (stageTicks == 1) {
            searchAttempts++;
            var player = requirePlayer(client);
            player.setYRot(player.getYRot() + 55.0F);
            player.setYHeadRot(player.getYRot());
            inputActions.add("look:visible-tree-search-sweep");
        }
        exploreSafely(client, "natural-tree-search");
        if (stageTicks >= 140) {
            stopMovement(client);
            if (searchAttempts > MAX_SEARCH_ATTEMPTS) {
                throw new IllegalStateException("no natural tree was observed after bounded survival exploration");
            }
            transition(Stage.FIND_TREE, 15);
        }
    }

    private void navigateTree(Minecraft client) {
        if (navigateTo(client, resourceTree.base(), 2.4, "natural tree")) {
            stopMovement(client);
            mineIndex = 0;
            transition(Stage.MINE_TREE, 15);
        }
    }

    private void mineTree(Minecraft client) {
        if (mineIndex >= Math.min(LOGS_REQUIRED, resourceTree.logs().size())) {
            stopAttack(client);
            transition(Stage.COLLECT_TREE, 0);
            return;
        }
        var player = requirePlayer(client);
        if (!player.getMainHandItem().isEmpty()) {
            var emptySlot = emptyHotbarSlot(player);
            if (emptySlot < 0) throw new IllegalStateException(
                    "hand-mining began with an item equipped and no empty hotbar slot was observed");
            selectHotbar(client, emptySlot);
            inputActions.add("select:empty-hotbar-slot-for-hand-mining");
            return;
        }
        var target = resourceTree.logs().get(mineIndex);
        if (!client.level.getBlockState(target).is(BlockTags.LOGS)) {
            stopAttack(client);
            handMinedLogs++;
            mineIndex++;
            stageTicks = 0;
            inputActions.add("read:block-broken-by-hand:" + target);
            waitTicks = 10;
            return;
        }
        lookAt(player, Vec3.atCenterOf(target));
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(target)) {
            if (hit instanceof BlockHitResult blocker) {
                var blockerState = client.level.getBlockState(blocker.getBlockPos());
                if (blockerState.is(BlockTags.LEAVES) || blockerState.is(BlockTags.LOGS)) {
                    lookAt(player, Vec3.atCenterOf(blocker.getBlockPos()));
                    client.options.keyAttack.setDown(true);
                    inputActions.add("attack:key.attack-held-clear-tree-foliage");
                    if (stageTicks > 420) throw new IllegalStateException(
                            "tree log remained occluded by natural foliage at " + target);
                    return;
                }
            }
            var vantage = findTreeMiningVantage(client, target);
            if (vantage != null) {
                stopAttack(client);
                navigateTo(client, vantage, 1.8, "tree-log-vantage");
                return;
            }
        }
        client.options.keyAttack.setDown(true);
        inputActions.add("attack:key.attack-held-by-hand");
        if (stageTicks > 420) throw new IllegalStateException("hand mining failed at observed log " + target);
    }

    private BlockPos findTreeMiningVantage(Minecraft client, BlockPos target) {
        for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            for (int distance = 1; distance <= 3; distance++) {
                for (int yOffset = -1; yOffset <= 2; yOffset++) {
                    var candidate = target.relative(direction, distance).offset(0, yOffset, 0);
                    if (!standable(client, candidate)) continue;
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    var clip = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, requirePlayer(client)));
                    if (clip instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    private void collectTree(Minecraft client) {
        var player = requirePlayer(client);
        if (countLogs(player) >= Math.min(LOGS_REQUIRED, resourceTree.logs().size())) {
            stopMovement(client);
            announce(client, "Collected hand-mined wood; opening the inventory crafting grid");
            transition(Stage.OPEN_INVENTORY, 20);
            return;
        }
        var drops = client.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        player.getBoundingBox().inflate(18.0), entity -> entity.isAlive()
                                && isLogStack(entity.getItem()))
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (drops != null) {
            lookAt(player, drops.position());
            setMovement(client, true, false, player.onGround() && drops.getY() > player.getY() + 0.5);
            inputActions.add("move:walk-to-observed-log-drop");
        } else {
            lookAt(player, Vec3.atCenterOf(resourceTree.base()));
            setMovement(client, true, false, player.onGround() && stageTicks % 35 < 4);
            inputActions.add("move:search-for-natural-log-drops");
        }
        if (stageTicks > 700) throw new IllegalStateException("natural log drops were not collected by movement");
    }

    private void openInventory(Minecraft client) {
        if (client.screen instanceof InventoryScreen) {
            transition(Stage.CRAFT_PLANKS, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen before survival crafting: "
                + client.screen.getClass().getName());
        KeyMapping.click(client.options.keyInventory.getKey());
        inputActions.add("ui:key.inventory");
        if (stageTicks > 100) throw new IllegalStateException("inventory did not open through normal key input");
    }

    private void craftPlanks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var logs = findPlayerItemSlot(screen, NeoForgeNetherGoal::isLogStack);
            if (logs < 0) throw new IllegalStateException("hand-mined logs missing from inventory");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(logs, 0, ClickType.PICKUP, "pick-up-hand-mined-logs"),
                    new ClickOp(1, 0, ClickType.PICKUP, "place-logs-in-inventory-grid"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-planks")), () -> {
                if (countPlanks(requirePlayer(client)) < 12) {
                    throw new IllegalStateException("visible plank recipe produced insufficient planks");
                }
                transition(Stage.CRAFT_TABLE, 12);
            });
        }
    }

    private void craftTable(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            if (planks < 0) throw new IllegalStateException("planks missing before crafting table");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-table"),
                    new ClickOp(1, 1, ClickType.PICKUP, "crafting-table-grid-1"),
                    new ClickOp(2, 1, ClickType.PICKUP, "crafting-table-grid-2"),
                    new ClickOp(3, 1, ClickType.PICKUP, "crafting-table-grid-3"),
                    new ClickOp(4, 1, ClickType.PICKUP, "crafting-table-grid-4"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-table-planks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafting-table")), () -> {
                if (countItem(requirePlayer(client), Items.CRAFTING_TABLE) < 1) {
                    throw new IllegalStateException("visible crafting-table recipe did not produce a table");
                }
                transition(Stage.CRAFT_STICKS, 12);
            });
        }
    }

    private void craftSticks(Minecraft client) {
        var screen = requireContainer(client, InventoryScreen.class);
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            if (planks < 0) throw new IllegalStateException("planks missing before crafting sticks");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-sticks"),
                    new ClickOp(1, 1, ClickType.PICKUP, "stick-grid-top"),
                    new ClickOp(3, 1, ClickType.PICKUP, "stick-grid-bottom"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-stick-planks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-crafted-sticks")), () -> {
                if (countItem(requirePlayer(client), Items.STICK) < 4) {
                    throw new IllegalStateException("visible stick recipe did not produce sticks");
                }
                transition(Stage.MOVE_TABLE_TO_HOTBAR, 12);
            });
        }
    }

    private void craftWoodPick(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (woodPickVerificationPending) {
            if (countItem(requirePlayer(client), Items.WOODEN_PICKAXE) >= 1) {
                woodPickVerificationPending = false;
                afterTableClose = Stage.EQUIP_WOOD_PICK;
                transition(Stage.MOVE_WOOD_PICK_TO_HOTBAR, 15);
                return;
            }
            if (stageTicks <= 20) return;
            woodPickVerificationPending = false;
            if (++woodPickAttempts <= 2) {
                clearCraftingGrid(client);
                recipeStarted = false;
                stageTicks = 0;
                waitTicks = 8;
                inputActions.add("craft:retry-wooden-pickaxe-after-container-sync");
                return;
            }
            throw new IllegalStateException("visible wooden-pick recipe did not produce a pickaxe");
        }
        if (!recipeStarted) {
            var planks = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.PLANKS));
            var sticks = findPlayerItemSlot(screen, stack -> stack.is(Items.STICK));
            if (planks < 0 || sticks < 0) throw new IllegalStateException("wooden pick ingredients missing");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(planks, 0, ClickType.PICKUP, "pick-up-planks-for-wood-pick"),
                    new ClickOp(1, 1, ClickType.PICKUP, "wood-pick-top-left"),
                    new ClickOp(2, 1, ClickType.PICKUP, "wood-pick-top-middle"),
                    new ClickOp(3, 1, ClickType.PICKUP, "wood-pick-top-right"),
                    new ClickOp(planks, 0, ClickType.PICKUP, "return-unused-pick-planks"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "pick-up-sticks-for-wood-pick"),
                    new ClickOp(5, 1, ClickType.PICKUP, "wood-pick-stick-middle"),
                    new ClickOp(8, 1, ClickType.PICKUP, "wood-pick-stick-bottom"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "return-unused-pick-sticks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-wooden-pickaxe")), () -> {
                woodPickVerificationPending = true;
                stageTicks = 0;
                recipeStarted = false;
            });
        }
    }

    private void clearCraftingGrid(Minecraft client) {
        if (client.gameMode == null) throw new IllegalStateException("game mode unavailable while clearing recipe grid");
        var screen = requireContainer(client, CraftingScreen.class);
        for (int slot = 1; slot <= 9; slot++) {
            if (!screen.getMenu().getSlot(slot).getItem().isEmpty()) {
                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, 0,
                        ClickType.QUICK_MOVE, requirePlayer(client));
                inputActions.add("container-click:clear-wood-pick-grid-" + slot);
            }
        }
    }

    private void moveTableToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.CRAFTING_TABLE, () -> {
            afterTableClose = Stage.PLACE_TABLE;
            transition(Stage.CLOSE_INVENTORY, 12);
        });
    }

    private void moveWoodPickToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.WOODEN_PICKAXE, () -> {
            afterTableClose = Stage.EQUIP_WOOD_PICK;
            transition(Stage.CLOSE_TABLE, 12);
        });
    }

    private void moveStonePickToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.STONE_PICKAXE, () -> {
            afterTableClose = Stage.EQUIP_STONE_PICK;
            transition(Stage.MOVE_FURNACE_TO_HOTBAR, 12);
        });
    }

    private void moveFurnaceToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.FURNACE, () -> {
            afterTableClose = Stage.EQUIP_STONE_PICK;
            transition(Stage.CLOSE_TABLE, 12);
        });
    }

    private void moveBucketToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.BUCKET, () -> transition(Stage.MOVE_FLINT_STEEL_TO_HOTBAR, 12));
    }

    private void moveFlintSteelToHotbar(Minecraft client) {
        moveCraftedToHotbar(client, Items.FLINT_AND_STEEL, () -> {
            afterTableClose = Stage.FIND_WATER;
            transition(Stage.CLOSE_TABLE, 12);
        });
    }

    private void moveCraftedToHotbar(Minecraft client, Item item, Runnable complete) {
        var container = requireAnyContainer(client);
        var player = requirePlayer(client);
        var hotbar = hotbarSlot(player, item);
        if (hotbar >= 0) {
            if (item == Items.CRAFTING_TABLE) tableHotbarSlot = hotbar;
            if (item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE) toolHotbarSlot = hotbar;
            if (item == Items.FURNACE) furnaceHotbarSlot = hotbar;
            if (item == Items.BUCKET) bucketHotbarSlot = hotbar;
            if (item == Items.FLINT_AND_STEEL) flintSteelHotbarSlot = hotbar;
            complete.run();
            return;
        }
        if (!recipeStarted) {
            var source = findPlayerItemSlot(container, stack -> stack.is(item));
            if (source < 0) throw new IllegalStateException("crafted item missing before hotbar transfer: " + item);
            recipeStarted = true;
            startClicks(List.of(new ClickOp(source, 0, ClickType.QUICK_MOVE,
                    "move-crafted-" + item.toString().replace(':', '-') + "-to-hotbar")), () -> {
                if (hotbarSlot(requirePlayer(client), item) < 0) {
                    throw new IllegalStateException("normal quick-move did not place item in hotbar: " + item);
                }
                recipeStarted = false;
                complete.run();
            });
        }
    }

    private void closeInventory(Minecraft client) {
        if (client.screen == null) {
            tablePosition = findTablePlacement(client);
            transition(Stage.PLACE_TABLE, 12);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-inventory");
        if (stageTicks > 100) throw new IllegalStateException("inventory did not close");
    }

    private void placeTable(Minecraft client) {
        var player = requirePlayer(client);
        if (client.level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
            nextTableStage = Stage.CRAFT_WOOD_PICK;
            transition(Stage.OPEN_TABLE, 15);
            return;
        }
        selectHotbar(client, tableHotbarSlot);
        var support = tablePosition.below();
        if (!standable(client, player.blockPosition())) {
            if (stageTicks > 120) throw new IllegalStateException("player lost a standable position before table placement");
            return;
        }
        lookAt(player, facePoint(support, Direction.UP));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                && blockHit.getDirection() == Direction.UP && stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-place-crafting-table");
        }
        if (stageTicks > 240) throw new IllegalStateException("normal table placement did not complete");
    }

    private void openTable(Minecraft client) {
        if (client.screen instanceof CraftingScreen) {
            transition(nextTableStage, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening crafting table");
        lookAt(requirePlayer(client), Vec3.atCenterOf(tablePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-crafting-table");
        }
        if (stageTicks > 120) throw new IllegalStateException("normal table use did not open crafting screen");
    }

    private void craftStonePick(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var cobble = findPlayerItemSlot(screen, stack -> stack.is(Items.COBBLESTONE));
            var sticks = findPlayerItemSlot(screen, stack -> stack.is(Items.STICK));
            if (cobble < 0 || sticks < 0) throw new IllegalStateException("stone-pick ingredients missing");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(cobble, 0, ClickType.PICKUP, "pick-up-cobblestone-for-stone-pick"),
                    new ClickOp(1, 1, ClickType.PICKUP, "stone-pick-top-left"),
                    new ClickOp(2, 1, ClickType.PICKUP, "stone-pick-top-middle"),
                    new ClickOp(3, 1, ClickType.PICKUP, "stone-pick-top-right"),
                    new ClickOp(cobble, 0, ClickType.PICKUP, "return-unused-stone-pick-cobble"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "pick-up-sticks-for-stone-pick"),
                    new ClickOp(5, 1, ClickType.PICKUP, "stone-pick-stick-middle"),
                    new ClickOp(8, 1, ClickType.PICKUP, "stone-pick-stick-bottom"),
                    new ClickOp(sticks, 0, ClickType.PICKUP, "return-unused-stone-pick-sticks"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-stone-pickaxe")), () -> {
                if (countItem(requirePlayer(client), Items.STONE_PICKAXE) < 1) {
                    throw new IllegalStateException("visible stone-pick recipe did not produce a pickaxe");
                }
                transition(Stage.CRAFT_FURNACE, 12);
            });
        }
    }

    private void craftFurnace(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var cobble = findPlayerItemSlot(screen, stack -> stack.is(Items.COBBLESTONE));
            if (cobble < 0 || countItem(requirePlayer(client), Items.COBBLESTONE) < 8) {
                throw new IllegalStateException("eight cobblestone required for furnace");
            }
            recipeStarted = true;
            var sequence = new ArrayList<ClickOp>();
            for (var slot : List.of(1, 2, 3, 4, 6, 7, 8, 9)) {
                sequence.add(new ClickOp(cobble, 0, ClickType.PICKUP, "furnace-cobble-slot-" + slot));
                sequence.add(new ClickOp(slot, 1, ClickType.PICKUP, "place-furnace-cobble-" + slot));
            }
            sequence.add(new ClickOp(cobble, 0, ClickType.PICKUP, "return-unused-furnace-cobble"));
            sequence.add(new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-furnace"));
            startClicks(sequence, () -> {
                if (countItem(requirePlayer(client), Items.FURNACE) < 1) {
                    throw new IllegalStateException("visible furnace recipe did not produce a furnace");
                }
                transition(Stage.MOVE_TOOL_TO_HOTBAR, 12);
            });
        }
    }

    private void closeTable(Minecraft client) {
        if (client.screen == null) {
            transition(afterTableClose, 12);
            return;
        }
        client.screen.keyPressed(256, 0, 0);
        inputActions.add("ui:key.escape-close-crafting-table");
        if (stageTicks > 100) throw new IllegalStateException("crafting table did not close");
    }

    private void equipTool(Minecraft client, Item item) {
        var slot = hotbarSlot(requirePlayer(client), item);
        if (slot < 0) throw new IllegalStateException("tool missing from hotbar: " + item);
        toolHotbarSlot = slot;
        selectHotbar(client, slot);
        if (requirePlayer(client).getMainHandItem().is(item)) {
            transition(item == Items.WOODEN_PICKAXE ? Stage.FIND_STONE : Stage.FIND_IRON, 15);
            return;
        }
        if (stageTicks > 80) throw new IllegalStateException("normal hotbar input did not equip " + item);
    }

    private void findStone(Minecraft client) {
        requirePrerequisiteTool(client, Items.WOODEN_PICKAXE, "stone mining");
        observedStone = scanVisibleBlocks(client, state -> state.is(Blocks.STONE), 28, 20);
        if (observedStone.size() >= 3) {
            stoneMineIndex = 0;
            transition(Stage.MINE_STONE, 12);
            return;
        }
        exploreForObservation(client, "stone");
    }

    private void mineStone(Minecraft client) {
        requireEquippedTool(client, Items.WOODEN_PICKAXE, "stone mining");
        if (countItem(requirePlayer(client), Items.COBBLESTONE) >= COBBLESTONE_REQUIRED) {
            stopAttack(client);
            nextTableStage = Stage.CRAFT_STONE_PICK;
            resetRecipeState();
            transition(Stage.OPEN_TABLE, 15);
            return;
        }
        if (stoneMineIndex >= observedStone.size()) {
            transition(Stage.FIND_STONE, 15);
            return;
        }
        var target = observedStone.get(stoneMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.STONE)) {
            stopAttack(client);
            cobblestoneMined++;
            stoneMineIndex++;
            stageTicks = 0;
            waitTicks = 8;
            return;
        }
        lookAt(player, Vec3.atCenterOf(target));
        client.options.keyAttack.setDown(true);
        inputActions.add("attack:key.attack-held-with-wooden-pickaxe");
        if (stageTicks > 420) throw new IllegalStateException("wooden pickaxe failed to mine observed stone");
    }

    private void findIron(Minecraft client) {
        requirePrerequisiteTool(client, Items.STONE_PICKAXE, "iron mining");
        observedIron = scanVisibleBlocks(client,
                state -> state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE), 72, 48);
        if (!observedIron.isEmpty()) {
            ironMineIndex = 0;
            transition(Stage.MINE_IRON, 12);
            return;
        }
        exploreForObservation(client, "exposed iron ore");
    }

    private void mineIron(Minecraft client) {
        requireEquippedTool(client, Items.STONE_PICKAXE, "iron mining");
        if (countItem(requirePlayer(client), Items.RAW_IRON) >= RAW_IRON_REQUIRED) {
            stopAttack(client);
            transition(Stage.FIND_GRAVEL, 15);
            return;
        }
        if (ironMineIndex >= observedIron.size()) {
            transition(Stage.FIND_IRON, 15);
            return;
        }
        var target = observedIron.get(ironMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.IRON_ORE)
                && !client.level.getBlockState(target).is(Blocks.DEEPSLATE_IRON_ORE)) {
            stopAttack(client);
            rawIronMined++;
            ironMineIndex++;
            stageTicks = 0;
            waitTicks = 10;
            return;
        }
        lookAt(player, Vec3.atCenterOf(target));
        client.options.keyAttack.setDown(true);
        inputActions.add("attack:key.attack-held-with-stone-pickaxe");
        if (stageTicks > 520) throw new IllegalStateException("stone pickaxe failed to mine observed iron ore");
    }

    private void findGravel(Minecraft client) {
        requirePrerequisiteTool(client, Items.STONE_PICKAXE, "gravel mining");
        observedGravel = scanVisibleBlocks(client, state -> state.is(Blocks.GRAVEL), 64, 32);
        if (!observedGravel.isEmpty()) {
            gravelMineIndex = 0;
            transition(Stage.MINE_GRAVEL, 12);
            return;
        }
        exploreForObservation(client, "exposed gravel");
    }

    private void mineGravel(Minecraft client) {
        requireEquippedTool(client, Items.STONE_PICKAXE, "gravel mining");
        if (countItem(requirePlayer(client), Items.FLINT) > 0) {
            stopAttack(client);
            transition(Stage.PLACE_FURNACE, 15);
            return;
        }
        if (gravelMineIndex >= observedGravel.size()) {
            if (stageTicks > 1_200) throw new IllegalStateException("no flint dropped from observed gravel");
            transition(Stage.FIND_GRAVEL, 15);
            return;
        }
        var target = observedGravel.get(gravelMineIndex);
        var player = requirePlayer(client);
        if (!client.level.getBlockState(target).is(Blocks.GRAVEL)) {
            stopAttack(client);
            gravelMined++;
            gravelMineIndex++;
            stageTicks = 0;
            waitTicks = 8;
            return;
        }
        lookAt(player, Vec3.atCenterOf(target));
        client.options.keyAttack.setDown(true);
        inputActions.add("attack:key.attack-held-for-flint-gravel");
        if (stageTicks > 320) throw new IllegalStateException("gravel mining failed");
    }

    private void placeFurnace(Minecraft client) {
        var player = requirePlayer(client);
        if (furnacePosition == null) furnacePosition = findTablePlacement(client);
        if (client.level.getBlockState(furnacePosition).is(Blocks.FURNACE)) {
            transition(Stage.OPEN_FURNACE, 15);
            return;
        }
        selectHotbar(client, furnaceHotbarSlot);
        var support = furnacePosition.below();
        lookAt(player, facePoint(support, Direction.UP));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                && blockHit.getDirection() == Direction.UP && stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-place-furnace");
        }
        if (stageTicks > 240) throw new IllegalStateException("normal furnace placement did not complete");
    }

    private void openFurnace(Minecraft client) {
        if (client.screen instanceof FurnaceScreen) {
            smeltLoaded = false;
            smeltCollected = false;
            smeltTicks = 0;
            transition(Stage.SMELT_IRON, 12);
            return;
        }
        if (client.screen != null) throw new IllegalStateException("unexpected screen while opening furnace");
        lookAt(requirePlayer(client), Vec3.atCenterOf(furnacePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-furnace");
        }
        if (stageTicks > 120) throw new IllegalStateException("normal furnace use did not open furnace screen");
    }

    private void smeltIron(Minecraft client) {
        var screen = requireContainer(client, FurnaceScreen.class);
        if (!smeltLoaded && !recipeStarted) {
            var rawIron = findPlayerItemSlot(screen, stack -> stack.is(Items.RAW_IRON));
            var fuel = findPlayerItemSlot(screen, stack -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS));
            if (rawIron < 0 || fuel < 0) throw new IllegalStateException("raw iron or natural wood fuel missing at furnace");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(rawIron, 0, ClickType.QUICK_MOVE, "load-raw-iron-into-furnace"),
                    new ClickOp(fuel, 0, ClickType.QUICK_MOVE, "load-natural-wood-fuel")), () -> {
                recipeStarted = false;
                smeltLoaded = true;
                smeltTicks = 0;
            });
            return;
        }
        if (smeltLoaded && !smeltCollected) {
            smeltTicks++;
            if (smeltTicks < 420) return;
            if (!recipeStarted) {
                recipeStarted = true;
                startClicks(List.of(new ClickOp(2, 0, ClickType.QUICK_MOVE, "take-smelted-iron-ingots")), () -> {
                    recipeStarted = false;
                    smeltCollected = true;
                    if (countItem(requirePlayer(client), Items.IRON_INGOT) < RAW_IRON_REQUIRED) {
                        throw new IllegalStateException("furnace did not visibly produce four iron ingots");
                    }
                    client.screen.keyPressed(256, 0, 0);
                    inputActions.add("ui:key.escape-close-furnace");
                    resetRecipeState();
                    nextTableStage = Stage.CRAFT_BUCKET;
                    transition(Stage.OPEN_TABLE, 20);
                });
            }
        }
    }

    private void closeFurnace(Minecraft client) {
        if (client.screen != null) {
            client.screen.keyPressed(256, 0, 0);
            inputActions.add("ui:key.escape-close-furnace");
            return;
        }
        nextTableStage = Stage.CRAFT_BUCKET;
        transition(Stage.OPEN_TABLE, 20);
    }

    private void craftBucket(Minecraft client) {
        if (!(client.screen instanceof CraftingScreen)) {
            openTableForRecipe(client);
            return;
        }
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var iron = findPlayerItemSlot(screen, stack -> stack.is(Items.IRON_INGOT));
            if (iron < 0) throw new IllegalStateException("iron ingots missing before bucket recipe");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(iron, 0, ClickType.PICKUP, "pick-up-iron-for-bucket"),
                    new ClickOp(1, 1, ClickType.PICKUP, "bucket-iron-left"),
                    new ClickOp(3, 1, ClickType.PICKUP, "bucket-iron-right"),
                    new ClickOp(5, 1, ClickType.PICKUP, "bucket-iron-bottom"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "return-unused-bucket-iron"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-bucket")), () -> {
                if (countItem(requirePlayer(client), Items.BUCKET) < 1) throw new IllegalStateException("bucket recipe failed");
                resetRecipeState();
                transition(Stage.CRAFT_FLINT_STEEL, 12);
            });
        }
    }

    private void craftFlintSteel(Minecraft client) {
        var screen = requireContainer(client, CraftingScreen.class);
        if (!recipeStarted) {
            var flint = findPlayerItemSlot(screen, stack -> stack.is(Items.FLINT));
            var iron = findPlayerItemSlot(screen, stack -> stack.is(Items.IRON_INGOT));
            if (flint < 0 || iron < 0) throw new IllegalStateException("flint or iron missing for ignition tool");
            recipeStarted = true;
            startClicks(List.of(new ClickOp(flint, 0, ClickType.PICKUP, "pick-up-natural-flint"),
                    new ClickOp(1, 1, ClickType.PICKUP, "flint-steel-flint"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "pick-up-iron-for-flint-steel"),
                    new ClickOp(2, 1, ClickType.PICKUP, "flint-steel-iron"),
                    new ClickOp(iron, 0, ClickType.PICKUP, "return-unused-flint-steel-iron"),
                    new ClickOp(0, 0, ClickType.QUICK_MOVE, "take-flint-and-steel")), () -> {
                if (countItem(requirePlayer(client), Items.FLINT_AND_STEEL) < 1) {
                    throw new IllegalStateException("visible flint-and-steel recipe failed");
                }
                resetRecipeState();
                transition(Stage.MOVE_BUCKET_TO_HOTBAR, 12);
            });
        }
    }

    private void findWater(Minecraft client) {
        waterSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.WATER, 96, 48);
        if (waterSource != null) {
            announce(client, "Observed a natural water source; searching for surface lava");
            transition(Stage.FIND_LAVA, 15);
            return;
        }
        exploreForObservation(client, "natural water source");
    }

    private void findLava(Minecraft client) {
        lavaSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.LAVA, 96, 48);
        if (lavaSource != null) {
            announce(client, "Observed natural water and lava; building a portal by bucket mechanics");
            fallbackPortalBase = findBucketPortalBase(client);
            if (fallbackPortalBase == null) throw new IllegalStateException("no clear natural ground for bucket portal");
            portalScaffolds.clear();
            for (var dx : List.of(1, 2)) {
                for (var y = 1; y <= 3; y++) {
                    portalScaffolds.add(fallbackPortalBase.offset(dx, y, 0));
                }
            }
            portalTargetIndex = 0;
            portalScaffoldIndex = 0;
            portalPhase = 0;
            transition(Stage.BUILD_BUCKET_PORTAL, 15);
            return;
        }
        exploreForObservation(client, "natural lava pool");
    }

    private void fillWaterBucket(Minecraft client) {
        var player = requirePlayer(client);
        var empty = hotbarSlot(player, Items.BUCKET);
        if (empty < 0) throw new IllegalStateException("empty bucket is not in the hotbar");
        selectHotbar(client, empty);
        waterSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.WATER, 96, 48);
        if (waterSource == null) throw new IllegalStateException("natural water source disappeared before bucket fill");
        if (navigateTo(client, waterSource, 3.2, "water source")) {
            lookAt(player, Vec3.atCenterOf(waterSource));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(waterSource)
                    && stageTicks % 12 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-fill-water-bucket");
            }
        }
        if (player.getMainHandItem().is(Items.WATER_BUCKET)) {
            portalPhase = 3;
            transition(Stage.BUILD_BUCKET_PORTAL, 10);
        }
        if (stageTicks > 300) throw new IllegalStateException("normal water-bucket fill did not complete");
    }

    private void fillLavaBucket(Minecraft client) {
        var player = requirePlayer(client);
        var empty = hotbarSlot(player, Items.BUCKET);
        if (empty < 0) throw new IllegalStateException("empty bucket is not in the hotbar before lava fill");
        selectHotbar(client, empty);
        lavaSource = findFluidSource(client, net.minecraft.world.level.material.Fluids.LAVA, 96, 48);
        if (lavaSource == null) throw new IllegalStateException("natural lava source disappeared before bucket fill");
        if (navigateTo(client, lavaSource, 3.2, "lava source")) {
            lookAt(player, Vec3.atCenterOf(lavaSource));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(lavaSource)
                    && stageTicks % 12 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-fill-lava-bucket");
            }
        }
        if (player.getMainHandItem().is(Items.LAVA_BUCKET)) {
            portalPhase = 1;
            transition(Stage.BUILD_BUCKET_PORTAL, 10);
        }
        if (stageTicks > 300) throw new IllegalStateException("normal lava-bucket fill did not complete");
    }

    private void buildBucketPortal(Minecraft client) {
        var player = requirePlayer(client);
        if (portalScaffoldIndex < portalScaffolds.size()) {
            var target = portalScaffolds.get(portalScaffoldIndex);
            if (client.level.getBlockState(target).is(Blocks.COBBLESTONE)) {
                portalScaffoldIndex++;
                stageTicks = 0;
                return;
            }
            var cobble = hotbarSlot(player, Items.COBBLESTONE);
            if (cobble < 0) throw new IllegalStateException("cobblestone scaffold material missing from hotbar");
            selectHotbar(client, cobble);
            var support = target.below();
            var vantage = findPlacementVantage(client, support, Direction.UP);
            if (vantage == null) throw new IllegalStateException("no normal-input vantage for portal scaffold " + target);
            if (!navigateTo(client, vantage, 1.0, "portal scaffold")) return;
            lookAt(player, facePoint(support, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                    && blockHit.getDirection() == Direction.UP && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("place:key.use:portal-scaffold:" + target);
            }
            if (stageTicks > 260) throw new IllegalStateException("normal portal scaffold placement failed at " + target);
            return;
        }
        var targets = framePlacements(fallbackPortalBase, Direction.Axis.Z);
        if (portalTargetIndex >= targets.size()) {
            frameBlocksPlaced = (int) targets.stream().filter(p -> client.level.getBlockState(p.target()).is(Blocks.OBSIDIAN)).count();
            manualPortalBuilt = frameBlocksPlaced == 10;
            if (!manualPortalBuilt) throw new IllegalStateException("bucket portal frame readback found " + frameBlocksPlaced + " obsidian blocks");
            clearIndex = 0;
            transition(Stage.CLEAR_PORTAL_SCAFFOLD, 12);
            return;
        }
        var target = targets.get(portalTargetIndex).target();
        if (client.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
            portalTargetIndex++;
            portalPhase = 0;
            stageTicks = 0;
            return;
        }
        if (portalPhase == 0) {
            transition(Stage.FILL_LAVA_BUCKET, 10);
            return;
        }
        if (portalPhase == 1) {
            var lava = hotbarSlot(player, Items.LAVA_BUCKET);
            if (lava < 0) throw new IllegalStateException("filled lava bucket missing after normal fill");
            selectHotbar(client, lava);
            var support = target.below();
            var vantage = findPlacementVantage(client, support, Direction.UP);
            if (vantage == null || !navigateTo(client, vantage, 1.0, "portal lava placement")) return;
            lookAt(player, facePoint(support, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(support)
                    && blockHit.getDirection() == Direction.UP && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-place-lava-for-obsidian:" + target);
            }
            if (client.level.getFluidState(target).is(net.minecraft.world.level.material.Fluids.LAVA)) {
                portalPhase = 2;
                transition(Stage.FILL_WATER_BUCKET, 10);
            }
            if (stageTicks > 260) throw new IllegalStateException("normal lava placement failed at " + target);
            return;
        }
        if (portalPhase == 3) {
            selectHotbar(client, hotbarSlot(player, Items.WATER_BUCKET));
            lookAt(player, facePoint(target, Direction.UP));
            var hit = player.pick(5.0F, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)
                    && stageTicks % 10 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
                inputActions.add("use:key.use-water-on-lava:" + target);
            }
            if (client.level.getBlockState(target).is(Blocks.OBSIDIAN)) {
                portalTargetIndex++;
                portalPhase = 0;
                stageTicks = 0;
                waitTicks = 8;
            }
            if (stageTicks > 260) throw new IllegalStateException("water did not convert lava to obsidian at " + target);
        }
    }

    private void clearPortalScaffold(Minecraft client) {
        if (clearIndex >= portalScaffolds.size()) {
            var remaining = portalScaffolds.stream().filter(pos -> client.level.getBlockState(pos).is(Blocks.COBBLESTONE)).count();
            if (remaining != 0) throw new IllegalStateException("portal scaffold remained inside frame: " + remaining);
            placements.clear();
            placements.addAll(framePlacements(fallbackPortalBase, Direction.Axis.Z));
            site = new Site(fallbackPortalBase, requirePlayer(client).blockPosition(), Direction.Axis.Z);
            transition(Stage.LIGHT_PORTAL, 15);
            return;
        }
        var target = portalScaffolds.get(clearIndex);
        if (!client.level.getBlockState(target).is(Blocks.COBBLESTONE)) {
            clearIndex++;
            stageTicks = 0;
            return;
        }
        var player = requirePlayer(client);
        selectHotbar(client, toolHotbarSlot);
        lookAt(player, Vec3.atCenterOf(target));
        client.options.keyAttack.setDown(true);
        inputActions.add("attack:key.attack-held-remove-portal-scaffold");
        if (stageTicks > 320) throw new IllegalStateException("could not remove portal scaffold " + target);
    }

    private void openTableForRecipe(Minecraft client) {
        if (client.screen instanceof CraftingScreen) return;
        if (client.screen != null) throw new IllegalStateException("unexpected screen before table recipe");
        lookAt(requirePlayer(client), Vec3.atCenterOf(tablePosition));
        if (stageTicks % 15 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("use:key.use-open-crafting-table");
        }
        if (stageTicks > 120) throw new IllegalStateException("table did not open for survival recipe");
    }

    private void exploreForObservation(Minecraft client, String label) {
        var player = requirePlayer(client);
        if (stageTicks == 1) {
            player.setYRot(player.getYRot() + 47.0F);
            player.setYHeadRot(player.getYRot());
            inputActions.add("look:visible-search-sweep:" + label.replace(' ', '-'));
        }
        if (policy.actionSegmentReplanningEnabled()) {
            exploreSafely(client, label);
        } else {
            setMovement(client, true, true, player.onGround() && stageTicks % 32 < 4);
        }
        inputActions.add("move:explore-for-" + label.replace(' ', '-'));
        if (stageTicks >= 150) {
            stopMovement(client);
            transition(stage, 15);
        }
    }

    /** Explore loaded terrain with normal movement while rejecting holes and fluids. */
    private void exploreSafely(Minecraft client, String label) {
        var player = requirePlayer(client);
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy);
        var forward = Direction.fromYRot(player.getYRot());
        var front = player.blockPosition().relative(forward);
        if (snapshot.walkable(front)) {
            setMovement(client, true, false, false);
            inputActions.add("move:safe-observation-step:" + label.replace(' ', '-'));
            return;
        }
        if (player.onGround() && snapshot.walkable(front.above())) {
            setMovement(client, true, false, true);
            inputActions.add("move:safe-observation-step-up:" + label.replace(' ', '-'));
            return;
        }
        stopMovement(client);
        player.setYRot(player.getYRot() + 43.0F);
        player.setYHeadRot(player.getYRot());
        inputActions.add("look:safe-observation-detour:" + label.replace(' ', '-'));
    }

    private List<BlockPos> scanVisibleBlocks(Minecraft client,
                                              java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> predicate,
                                              int radius, int vertical) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var output = new ArrayList<BlockPos>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - vertical);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + vertical);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    var state = client.level.getBlockState(pos);
                    if (predicate.test(state) && isVisibleBlock(client, pos)) output.add(pos.immutable());
                }
            }
        }
        output.sort(Comparator.comparingDouble(pos -> player.distanceToSqr(Vec3.atCenterOf(pos))));
        return List.copyOf(output);
    }

    private static boolean isVisibleBlock(Minecraft client, BlockPos position) {
        for (var direction : Direction.values()) {
            var neighbor = position.relative(direction);
            if (client.level.getBlockState(neighbor).isAir()
                    || !client.level.getBlockState(neighbor).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private static BlockPos findFluidSource(Minecraft client,
                                             net.minecraft.world.level.material.Fluid fluid,
                                             int radius, int vertical) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - vertical);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + vertical);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    var state = client.level.getFluidState(pos);
                    if (!state.is(fluid) || !state.isSource()) continue;
                    var distance = player.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private List<TreePlan> scanTreePlans(Minecraft client) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var logs = new HashSet<Long>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - 12);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + 32);
        for (int x = center.getX() - 64; x <= center.getX() + 64; x++) {
            for (int z = center.getZ() - 64; z <= center.getZ() + 64; z++) {
                var column = new BlockPos(x, center.getY(), z);
                if (!client.level.hasChunkAt(column)) continue;
                for (int y = minY; y <= maxY; y++) {
                    var pos = new BlockPos(x, y, z);
                    if (client.level.getBlockState(pos).is(BlockTags.LOGS)) logs.add(pos.asLong());
                }
            }
        }
        var unseen = new HashSet<>(logs);
        var plans = new ArrayList<TreePlan>();
        while (!unseen.isEmpty()) {
            var seed = BlockPos.of(unseen.iterator().next());
            var queue = new ArrayDeque<BlockPos>();
            var component = new ArrayList<BlockPos>();
            queue.add(seed);
            unseen.remove(seed.asLong());
            while (!queue.isEmpty() && component.size() <= 24) {
                var current = queue.removeFirst();
                component.add(current.immutable());
                for (var direction : Direction.values()) {
                    var neighbor = current.relative(direction);
                    if (unseen.remove(neighbor.asLong())) queue.addLast(neighbor);
                }
            }
            if (component.size() < 3 || component.size() > 16) continue;
            component.sort(Comparator.comparingInt(BlockPos::getY));
            var minX = component.stream().mapToInt(BlockPos::getX).min().orElse(0);
            var maxX = component.stream().mapToInt(BlockPos::getX).max().orElse(0);
            var minZ = component.stream().mapToInt(BlockPos::getZ).min().orElse(0);
            var maxZ = component.stream().mapToInt(BlockPos::getZ).max().orElse(0);
            if (maxX - minX > 1 || maxZ - minZ > 1 || component.getLast().getY() - component.getFirst().getY() > 12) continue;
            var base = component.getFirst();
            if (client.level.getBlockState(base.below()).is(BlockTags.LOGS)) continue;
            plans.add(new TreePlan(base, List.copyOf(component), player.distanceToSqr(Vec3.atCenterOf(base))));
        }
        plans.sort(Comparator.comparingDouble(TreePlan::distanceSquared));
        return List.copyOf(plans);
    }

    private BlockPos findBucketPortalBase(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        for (int radius = 2; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    var base = origin.offset(dx, 0, dz);
                    if (!standable(client, base)) continue;
                    var valid = true;
                    for (var placement : framePlacements(base, Direction.Axis.Z)) {
                        var target = placement.target();
                        if (!client.level.getBlockState(target).canBeReplaced()
                                || !client.level.getBlockState(target).getFluidState().isEmpty()) valid = false;
                    }
                    for (int along = 1; along <= 2; along++) {
                        for (int y = 2; y <= 3; y++) {
                            if (!client.level.getBlockState(frameOffset(base, Direction.Axis.Z, along, y, 0)).canBeReplaced()) valid = false;
                        }
                    }
                    if (valid) return base.immutable();
                }
            }
        }
        return null;
    }

    private static BlockPos findNearestChest(Minecraft client, int radius) {
        var player = requirePlayer(client);
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        var center = player.blockPosition();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - 32);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + 32);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!client.level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    var candidate = new BlockPos(x, y, z);
                    if (!client.level.getBlockState(candidate).is(Blocks.CHEST)) continue;
                    var distance = player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private void findSite(Minecraft client) {
        site = findPortalSite(client);
        if (site == null) {
            if (stageTicks > 360) throw new IllegalStateException(
                    "no natural ruined-portal frame was observed after looting the portal chest");
            return;
        }
        placements.clear();
        placements.addAll(framePlacements(site.base(), site.axis()));
        announce(client, "Natural ruined-portal frame observed; completing missing blocks with normal placement input");
        transition(Stage.BUILD_PORTAL, 12);
    }

    private void buildPortal(Minecraft client) {
        var player = requirePlayer(client);
        if (placementIndex >= placements.size()) {
            frameBlocksPlaced = (int) placements.stream()
                    .filter(placement -> client.level.getBlockState(placement.target()).is(Blocks.OBSIDIAN)).count();
            manualPortalBuilt = frameBlocksPlaced == 10;
            if (!manualPortalBuilt) throw new IllegalStateException("portal frame readback did not find ten obsidian blocks");
            announce(client, "Portal frame complete; lighting it with normal flint-and-steel input");
            transition(Stage.LIGHT_PORTAL, 12);
            return;
        }

        var placement = placements.get(placementIndex);
        if (client.level.getBlockState(placement.target()).is(Blocks.OBSIDIAN)) {
            placementIndex++;
            placementAttempts = 0;
            placementVantage = null;
            navigationDestination = null;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            waitTicks = 3;
            return;
        }
        if (!client.level.getBlockState(placement.target()).isAir()) {
            throw new IllegalStateException("portal target became obstructed: " + placement.target());
        }
        var slot = hotbarSlot(player, Items.OBSIDIAN);
        if (slot < 0) throw new IllegalStateException("naturally obtained obsidian is not in the hotbar");
        selectHotbar(client, slot);
        if (placementVantage == null) {
            placementVantage = findPlacementVantage(client, placement.support(), Direction.UP);
            if (placementVantage == null) throw new IllegalStateException(
                    "no standable normal-input vantage can see portal support " + placement.support());
        }
        if (!navigateTo(client, placementVantage, 0.85, "portal placement")) return;
        lookAt(player, facePoint(placement.support(), Direction.UP));
        var hit = player.pick(5.0, 1.0F, false);
        var aimed = hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(placement.support());
        var correctlyFaced = hit instanceof BlockHitResult blockHit
                && aimed && blockHit.getDirection() == Direction.UP;
        if (correctlyFaced && stageTicks % 8 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("place:key.use:obsidian:" + placement.target());
            placementAttempts++;
        }
        if (placementAttempts > 20 || stageTicks > 1_200) {
            var hitDescription = hit instanceof BlockHitResult blockHit
                    ? blockHit.getBlockPos() + "/" + blockHit.getDirection() : hit.getType().toString();
            throw new IllegalStateException("normal use input could not place portal obsidian at "
                    + placement.target() + "; aimed=" + correctlyFaced + ", hit=" + hitDescription
                    + ", player=" + player.blockPosition() + ", vantage=" + placementVantage);
        }
    }

    private void lightPortal(Minecraft client) {
        var player = requirePlayer(client);
        var flintSlot = hotbarSlot(player, Items.FLINT_AND_STEEL);
        if (flintSlot < 0) throw new IllegalStateException("naturally obtained flint and steel is not in the hotbar");
        selectHotbar(client, flintSlot);
        var ignitionSupport = frameOffset(site.base(), site.axis(), 1, 1, 0);
        if (placementVantage == null) placementVantage = findPlacementVantage(client, ignitionSupport, Direction.UP);
        if (placementVantage == null || !navigateTo(client, placementVantage, 0.85, "portal ignition")) return;
        lookAt(player, facePoint(ignitionSupport, Direction.UP));
        if (stageTicks % 12 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("portal:key.use:flint-and-steel");
            lightAttempts++;
        }
        portalBlocksObserved = hasPortalBlocks(client);
        if (portalBlocksObserved) {
            portalLit = true;
            announce(client, "Portal lit; entering through normal forward movement");
            transition(Stage.ENTER_PORTAL, 12);
            return;
        }
        if (lightAttempts > 20 || stageTicks > 300) {
            throw new IllegalStateException("flint-and-steel input did not produce Nether portal blocks");
        }
    }

    private void enterPortal(Minecraft client) {
        if (client.level != null && Level.NETHER.equals(client.level.dimension())) {
            reachedNether = true;
            enteredPortal = true;
            finalDimension = client.level.dimension().location().toString();
            releaseInput(client);
            transition(Stage.COMPLETE_DELAY, 50);
            return;
        }
        if (!portalBlocksObserved) portalBlocksObserved = hasPortalBlocks(client);
        var center = frameOffset(site.base(), site.axis(), 1, 2, 0);
        lookAt(requirePlayer(client), Vec3.atCenterOf(center));
        client.options.keyUp.setDown(true);
        if (stageTicks % 30 == 1) inputActions.add("move:key.forward-into-portal");
        if (stageTicks > 1_000) {
            throw new IllegalStateException("player did not transition through the lit portal");
        }
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        if (!reachedNether) throw new IllegalStateException("Nether terminal predicate was not observed");
        finalDimension = client.level == null ? finalDimension : client.level.dimension().location().toString();
        result.complete(Map.ofEntries(
                Map.entry("freshWorld", freshWorld),
                Map.entry("survival", survival),
                Map.entry("worldName", worldName),
                Map.entry("worldGameTimeAtStart", worldGameTimeAtStart),
                Map.entry("initialDimension", initialDimension),
                Map.entry("finalDimension", finalDimension),
                Map.entry("portalBase", position(site.base())),
                Map.entry("teleportedToBuildSite", teleportedToBuildSite),
                Map.entry("setupCommandsUsed", !setupCommands.isEmpty()),
                Map.entry("setupCommandCount", setupCommands.size()),
                Map.entry("setupCommands", List.copyOf(setupCommands)),
                Map.entry("commandFeedbackSuppressed", true),
                Map.entry("manualPortalBuilt", manualPortalBuilt),
                Map.entry("portalFrameBlocksPlaced", frameBlocksPlaced),
                Map.entry("portalLit", portalLit),
                Map.entry("portalBlocksObserved", portalBlocksObserved),
                Map.entry("enteredPortal", enteredPortal),
                Map.entry("reachedNether", reachedNether),
                Map.entry("playerAlive", requirePlayer(client).isAlive() && requirePlayer(client).getHealth() > 0.0F),
                Map.entry("healthAtEnd", requirePlayer(client).getHealth()),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("prerequisitePlanning", policy.prerequisitePlanningEnabled()),
                Map.entry("actionSegmentReplanning", policy.actionSegmentReplanningEnabled()),
                Map.entry("safetyInterventions", List.copyOf(safetyDiagnostics)),
                Map.entry("observation", policy.observation()), Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowBlockBreaking", policy.allowBlockBreaking()),
                Map.entry("allowBlockPlacing", policy.allowBlockPlacing()),
                Map.entry("suppressInGameMessages", suppressInGameMessages),
                Map.entry("inGameMessagesEmitted", inGameMessagesEmitted),
                Map.entry("directMutationUsed", false),
                Map.entry("inputActions", List.copyOf(inputActions))));
    }

    private Site findPortalSite(Minecraft client) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        var observedObsidian = new ArrayList<BlockPos>();
        var minY = Math.max(client.level.getMinBuildHeight(), center.getY() - 40);
        var maxY = Math.min(client.level.getMaxBuildHeight() - 1, center.getY() + 40);
        for (int x = center.getX() - 96; x <= center.getX() + 96; x++) {
            for (int z = center.getZ() - 96; z <= center.getZ() + 96; z++) {
                if (!client.level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    var position = new BlockPos(x, y, z);
                    if (client.level.getBlockState(position).is(Blocks.OBSIDIAN)) {
                        observedObsidian.add(position.immutable());
                    }
                }
            }
        }
        Site best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (var anchor : observedObsidian) {
            for (var axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
                for (int along = -3; along <= 0; along++) {
                    for (int yOffset = -4; yOffset <= -1; yOffset++) {
                        var base = axis == Direction.Axis.Z
                                ? anchor.offset(along, yOffset, 0)
                                : anchor.offset(0, yOffset, along);
                        var candidatePlacements = framePlacements(base, axis);
                        var existing = 0;
                        var valid = true;
                        for (var placement : candidatePlacements) {
                            var state = client.level.getBlockState(placement.target());
                            if (state.is(Blocks.OBSIDIAN)) existing++;
                            else if (!state.isAir() && !state.canBeReplaced()) valid = false;
                        }
                        if (!valid || existing < 5) continue;
                        var missing = candidatePlacements.size() - existing;
                        if (missing > countItem(player, Items.OBSIDIAN)) continue;
                        for (int alongInterior = 1; alongInterior <= 2 && valid; alongInterior++) {
                            for (int y = 2; y <= 3; y++) {
                                var interior = frameOffset(base, axis, alongInterior, y, 0);
                                if (!client.level.getBlockState(interior).isAir()) valid = false;
                            }
                        }
                        if (!valid) continue;
                        var stand = findNearestStandable(client, base, axis);
                        if (stand == null) continue;
                        var distance = player.distanceToSqr(Vec3.atCenterOf(stand));
                        if (distance < bestDistance) {
                            best = new Site(base.immutable(), stand.immutable(), axis);
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findNearestStandable(Minecraft client, BlockPos base, Direction.Axis axis) {
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    var candidate = base.offset(dx, dy, dz);
                    if (!standable(client, candidate)) continue;
                    var distance = candidate.distSqr(base);
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static List<Placement> framePlacements(BlockPos base, Direction.Axis axis) {
        var output = new ArrayList<Placement>(10);
        for (int along = 0; along < 4; along++) {
            var target = frameOffset(base, axis, along, 1, 0);
            output.add(new Placement(target, target.below()));
        }
        for (int y = 2; y <= 3; y++) {
            var left = frameOffset(base, axis, 0, y, 0);
            var right = frameOffset(base, axis, 3, y, 0);
            output.add(new Placement(left, left.below()));
            output.add(new Placement(right, right.below()));
        }
        for (int along = 0; along < 4; along++) {
            var target = frameOffset(base, axis, along, 4, 0);
            output.add(new Placement(target, target.below()));
        }
        return output;
    }

    private static BlockPos frameOffset(BlockPos base, Direction.Axis axis, int along, int y, int normal) {
        return axis == Direction.Axis.Z ? base.offset(along, y, normal) : base.offset(normal, y, along);
    }

    private boolean hasPortalBlocks(Minecraft client) {
        if (client.level == null || site == null) return false;
        for (int along = 1; along <= 2; along++) {
            for (int y = 2; y <= 3; y++) {
                if (client.level.getBlockState(frameOffset(site.base(), site.axis(), along, y, 0))
                        .is(Blocks.NETHER_PORTAL)) return true;
            }
        }
        return false;
    }

    private static boolean solid(Minecraft client, BlockPos position) {
        return !client.level.getBlockState(position).getCollisionShape(client.level, position).isEmpty();
    }

    private BlockPos findPlacementVantage(Minecraft client, BlockPos support, Direction face) {
        var aim = facePoint(support, face);
        for (var direction : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            for (var distance : List.of(1, 2, 3)) {
                var horizontal = support.relative(direction, distance);
                for (var yOffset : List.of(1, 0, 2, -1, 3)) {
                    var candidate = new BlockPos(horizontal.getX(), support.getY() + yOffset,
                            horizontal.getZ());
                    if (!standable(client, candidate)) continue;
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    if (eye.distanceTo(aim) > 4.8) continue;
                    var clip = client.level.clip(new ClipContext(eye, aim,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, requirePlayer(client)));
                    if (clip instanceof BlockHitResult blockHit
                            && blockHit.getBlockPos().equals(support)
                            && blockHit.getDirection() == face) return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean navigateTo(Minecraft client, BlockPos target, double tolerance, String label) {
        if (policy.smartNavigation()) return navigateWithSafePath(client, target, tolerance, label);
        var player = requirePlayer(client);
        if (!target.equals(navigationDestination)) {
            navigationDestination = target.immutable();
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
        }
        var dx = target.getX() + 0.5 - player.getX();
        var dy = target.getY() + 0.1 - player.getY();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (horizontalDistance <= tolerance && Math.abs(dy) <= 0.4) {
            stopMovement(client);
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            return true;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(8.0F);
        var detouring = navigationStuckTicks > 18;
        var detourLeft = detouring && (navigationStuckTicks / 30) % 2 == 0;
        client.options.keyUp.setDown(horizontalDistance > tolerance);
        client.options.keySprint.setDown(horizontalDistance > 4.0);
        client.options.keyLeft.setDown(detouring && detourLeft);
        client.options.keyRight.setDown(detouring && !detourLeft);
        client.options.keyJump.setDown(player.onGround() && (detouring || stageTicks % 30 < 3));
        inputActions.add("move:key.forward:" + label);
        if (detouring) inputActions.add("move:key.strafe-detour:" + label);
        if (distance < navigationLastDistance - 0.04) navigationStuckTicks = 0;
        else navigationStuckTicks++;
        navigationLastDistance = distance;
        if (navigationStuckTicks > 240) throw new IllegalStateException(
                "normal-input navigation stuck during " + label + " at " + player.blockPosition()
                        + "; target=" + target + ", distance=" + rounded(distance));
        return false;
    }

    private boolean navigateWithSafePath(Minecraft client, BlockPos target, double tolerance, String label) {
        var player = requirePlayer(client);
        if (!target.equals(navigationDestination)) {
            navigationDestination = target.immutable();
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), target, policy);
            navigationIndex = navigationPath.size() > 1 ? 1 : 0;
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            inputActions.add("observe:safety-weighted-route:" + label);
        }
        var dx = target.getX() + 0.5 - player.getX();
        var dy = target.getY() + 0.1 - player.getY();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= tolerance && Math.abs(dy) <= 0.8) {
            stopMovement(client);
            return true;
        }
        if (navigationPath.isEmpty()) {
            if (policy.highSafety()) throw new IllegalStateException(
                    "high-safety path unavailable before reaching " + label);
            return false;
        }
        while (navigationIndex < navigationPath.size()) {
            var waypoint = navigationPath.get(navigationIndex);
            if (player.distanceToSqr(Vec3.atCenterOf(waypoint)) <= 1.0) navigationIndex++;
            else break;
        }
        if (navigationIndex >= navigationPath.size()) return false;
        var waypoint = navigationPath.get(navigationIndex);
        var waypointDx = waypoint.getX() + 0.5 - player.getX();
        var waypointDz = waypoint.getZ() + 0.5 - player.getZ();
        var waypointDistance = Math.sqrt(waypointDx * waypointDx + waypointDz * waypointDz);
        if (waypointDistance < navigationLastDistance - 0.02) navigationStuckTicks = 0;
        else navigationStuckTicks++;
        navigationLastDistance = waypointDistance;
        if (navigationStuckTicks > 90) {
            navigationPath = NeoForgeSafePathPlanner.find(client.level, player.blockPosition(), target, policy);
            navigationIndex = navigationPath.size() > 1 ? 1 : 0;
            navigationStuckTicks = 0;
            navigationLastDistance = Double.POSITIVE_INFINITY;
            safetyDiagnostics.add("navigation-replan:" + label);
            if (navigationPath.isEmpty() && policy.highSafety()) {
                throw new IllegalStateException("high-safety navigation remained blocked during " + label);
            }
            return false;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(waypointDz, waypointDx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(!policy.highSafety() && horizontal > 7.0);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(player.onGround() && waypoint.getY() > player.getY() + 0.35);
        inputActions.add("move:safety-weighted-route:" + label);
        return false;
    }

    private void resetNavigation() {
        navigationDestination = null;
        navigationLastDistance = Double.POSITIVE_INFINITY;
        navigationStuckTicks = 0;
        navigationPath = List.of();
        navigationIndex = 0;
    }

    private BlockPos findTablePlacement(Minecraft client) {
        var player = requirePlayer(client);
        var origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        var candidate = origin.offset(dx, dy, dz);
                        var support = candidate.below();
                        if (!client.level.getBlockState(candidate).canBeReplaced()
                                || !client.level.getBlockState(candidate).getFluidState().isEmpty()
                                || client.level.getBlockState(support).getCollisionShape(client.level, support).isEmpty()) continue;
                        if (player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(candidate))) continue;
                        return candidate.immutable();
                    }
                }
            }
        }
        throw new IllegalStateException("no clear normal-input placement position observed");
    }

    private static boolean standable(Minecraft client, BlockPos feet) {
        return !client.level.getBlockState(feet.below()).getCollisionShape(client.level, feet.below()).isEmpty()
                && client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty()
                && client.level.getBlockState(feet.above()).getCollisionShape(client.level, feet.above()).isEmpty();
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    private void tickClicks(Minecraft client) {
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        if (!clicks.isEmpty()) {
            var click = clicks.removeFirst();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
                throw new IllegalStateException("container closed during natural portal chest loot");
            }
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, click.slot(), click.button(),
                    click.type(), requirePlayer(client));
            inputActions.add("container-click:" + click.label());
            clickDelay = 7;
            return;
        }
        var complete = clicksComplete;
        clicksComplete = null;
        if (complete != null) complete.run();
    }

    private void startClicks(List<ClickOp> sequence, Runnable complete) {
        if (!clicks.isEmpty() || clicksComplete != null) {
            throw new IllegalStateException("natural portal chest click sequence already active");
        }
        clicks.addAll(sequence);
        clicksComplete = complete;
        clickDelay = 2;
    }

    private void closeContainer(Minecraft client) {
        if (client.screen != null) {
            client.screen.keyPressed(256, 0, 0);
            inputActions.add("ui:key.escape-close-portal-chest");
        }
    }

    private static int countItem(LocalPlayer player, Item item) {
        var count = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countLogs(LocalPlayer player) {
        var count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isLogStack(player.getInventory().getItem(slot))) count += player.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private static int countPlanks(LocalPlayer player) {
        var count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(ItemTags.PLANKS)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isLogStack(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS);
    }

    private static int findPlayerItemSlot(AbstractContainerScreen<?> screen,
                                          java.util.function.Predicate<ItemStack> predicate) {
        var slots = screen.getMenu().slots;
        var start = screen instanceof InventoryScreen ? 9 : 10;
        for (int index = start; index < slots.size(); index++) {
            var stack = slots.get(index).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) return index;
        }
        return -1;
    }

    private static AbstractContainerScreen<?> requireAnyContainer(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            throw new IllegalStateException("expected a visible container screen");
        }
        return screen;
    }

    private static <T extends AbstractContainerScreen<?>> T requireContainer(Minecraft client, Class<T> type) {
        if (!type.isInstance(client.screen)) {
            throw new IllegalStateException("expected " + type.getSimpleName() + " but saw "
                    + (client.screen == null ? "no screen" : client.screen.getClass().getSimpleName()));
        }
        return type.cast(client.screen);
    }

    private void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint && forward);
        client.options.keyJump.setDown(jump);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int countNonEmpty(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) count++;
        }
        return count;
    }

    private void requirePrerequisiteTool(Minecraft client, Item item, String action) {
        if (!policy.prerequisitePlanningEnabled()) return;
        var player = requirePlayer(client);
        if (countItem(player, item) < 1) {
            throw new IllegalStateException("intelligent prerequisite missing before " + action + ": " + item);
        }
        if (hotbarSlot(player, item) < 0) {
            throw new IllegalStateException("intelligent prerequisite was not promoted to the hotbar before "
                    + action + ": " + item);
        }
    }

    private void requireEquippedTool(Minecraft client, Item item, String action) {
        if (!policy.prerequisitePlanningEnabled()) return;
        requirePrerequisiteTool(client, item, action);
        if (!requirePlayer(client).getMainHandItem().is(item)) {
            throw new IllegalStateException("intelligent prerequisite tool was not equipped before "
                    + action + ": " + item);
        }
    }

    private static int hotbarSlot(LocalPlayer player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static int emptyHotbarSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private void selectHotbar(Minecraft client, int slot) {
        if (client.player.getInventory().selected == slot) return;
        KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
        inputActions.add("select:hotbar-key-" + (slot + 1));
    }

    private static Vec3 facePoint(BlockPos support, Direction face) {
        return Vec3.atCenterOf(support).add(face.getStepX() * 0.49,
                face.getStepY() * 0.49, face.getStepZ() * 0.49);
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        var eye = new Vec3(player.getX(), player.getEyeY(), player.getZ());
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private void transition(Stage next, int delay) {
        stage = next;
        stageTicks = 0;
        waitTicks = delay;
        recipeStarted = false;
    }

    private void resetRecipeState() {
        recipeStarted = false;
    }

    private void announce(Minecraft client, String message) {
        if (suppressInGameMessages) return;
        var text = Component.literal("[Lodestone Goal] " + message);
        if (client.player != null) client.player.displayClientMessage(text, true);
        client.gui.getChat().addMessage(text);
        inGameMessagesEmitted += 2;
    }

    private static void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }

    private static LocalPlayer requirePlayer(Minecraft client) {
        if (client.player == null || client.level == null) {
            throw new IllegalStateException("client player/world unavailable");
        }
        return client.player;
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private record Placement(BlockPos target, BlockPos support) {
    }

    private record ClickOp(int slot, int button, ClickType type, String label) {
    }

    private record Site(BlockPos base, BlockPos standPosition, Direction.Axis axis) {
    }

    private record TreePlan(BlockPos base, List<BlockPos> logs, double distanceSquared) {
    }

    private enum Stage {
        WAIT_WORLD,
        FIND_LOOT,
        NAVIGATE_LOOT,
        LOOT_CHEST,
        FIND_TREE,
        NAVIGATE_TREE,
        MINE_TREE,
        COLLECT_TREE,
        OPEN_INVENTORY,
        CRAFT_PLANKS,
        CRAFT_TABLE,
        CRAFT_STICKS,
        CRAFT_WOOD_PICK,
        MOVE_TABLE_TO_HOTBAR,
        MOVE_WOOD_PICK_TO_HOTBAR,
        CLOSE_INVENTORY,
        PLACE_TABLE,
        OPEN_TABLE,
        CRAFT_STONE_PICK,
        CRAFT_FURNACE,
        MOVE_TOOL_TO_HOTBAR,
        MOVE_FURNACE_TO_HOTBAR,
        CLOSE_TABLE,
        EQUIP_WOOD_PICK,
        EQUIP_STONE_PICK,
        FIND_STONE,
        MINE_STONE,
        FIND_IRON,
        MINE_IRON,
        FIND_GRAVEL,
        MINE_GRAVEL,
        PLACE_FURNACE,
        OPEN_FURNACE,
        SMELT_IRON,
        CLOSE_FURNACE,
        CRAFT_BUCKET,
        CRAFT_FLINT_STEEL,
        MOVE_BUCKET_TO_HOTBAR,
        MOVE_FLINT_STEEL_TO_HOTBAR,
        FIND_WATER,
        FIND_LAVA,
        FILL_WATER_BUCKET,
        FILL_LAVA_BUCKET,
        BUILD_BUCKET_PORTAL,
        CLEAR_PORTAL_SCAFFOLD,
        FIND_SITE,
        BUILD_PORTAL,
        LIGHT_PORTAL,
        ENTER_PORTAL,
        COMPLETE_DELAY
    }
}
