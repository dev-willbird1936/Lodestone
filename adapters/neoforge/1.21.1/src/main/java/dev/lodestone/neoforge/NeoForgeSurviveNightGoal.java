// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Backs {@code minecraft.goal.survival.survive-night}: outside the night window this completes
 * immediately with {@code reason="not-night"}. During the night it hand-digs a small vertical
 * shaft (melee-mined, matching the proven substrate in {@link NeoForgeStoneToolsetGoal}), seals
 * itself in with one of the dug blocks, idles until dawn polling the live day time, then unseals
 * and performs one bounded jump-and-place-underfoot maneuver to climb back out - a deliberately
 * simple, best-effort exit rather than a fully reconstructed staircase, since surviving the night
 * (not the cosmetics of the exit) is this competency's actual contract.
 */
final class NeoForgeSurviveNightGoal {
    private static final int SHAFT_DEPTH = 2;
    private static final int MINE_STALL_TICKS = 200;
    private static final int PLACE_STALL_TICKS = 200;
    private static final int EXIT_STALL_TICKS = 60;
    // Live-caught bug: beginSeal() used to check the inventory for a sealable block on the exact
    // same tick digging finished, racing vanilla's own default ItemEntity pickup delay (10 ticks) -
    // the drop from the very last dug cell could not possibly have been collected yet, so this
    // reliably reported no-shelter-material even though a genuine dirt/grass drop was one or two
    // ticks away from landing in the inventory. This bound is generously past that delay.
    private static final int AWAIT_DROPS_STALL_TICKS = 40;
    private static final float REACH = 4.5F;

    /** Vanilla block ids that reliably yield a usable, hand-minable drop - the exact set this shaft
     * dig is meant to work with (topsoil/sand/etc.), never stone, ore, or anything else that
     * requires a correct tool to drop at all. Checked before spending any mining ticks on a cell so
     * an early "unshelterable-ground" is honest instead of grinding a no-drop block to a deadline.
     * Package-private and pure for direct testing via {@link #yieldsHandDiggableDrop}. */
    private static final Set<String> HAND_DIGGABLE_SOIL_IDS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol",
            "minecraft:mycelium", "minecraft:rooted_dirt", "minecraft:sand", "minecraft:red_sand",
            "minecraft:gravel", "minecraft:clay", "minecraft:mud", "minecraft:muddy_mangrove_roots",
            "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:farmland", "minecraft:dirt_path",
            "minecraft:snow_block", "minecraft:powder_snow");

    private enum Stage { CHECK, DIG, AWAIT_DROPS, ENSURE_HOTBAR, SEAL, WAIT_DAWN, UNSEAL, EXIT, DONE }
    private enum HotbarTransfer { OPENING, MOVING, CLOSING }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final int timeoutTicks;

    private Stage stage = Stage.CHECK;
    private int ticks;
    private int ticksWaited;
    private BlockPos anchor;
    private List<BlockPos> shaftCells = List.of();
    private int digIndex;
    private BlockPos activeMiningTarget;
    private int miningTicks;
    private int preDigBlockItemTotal;
    private int awaitDropsTicks;
    private Item sealItem;
    private HotbarTransfer hotbarTransfer;
    private int hotbarTicks;
    private BlockPos sealTarget;
    private int placeTicks;
    private Direction placeAttemptDirection = Direction.NORTH;
    private BlockPos exitCell;
    private int exitTicks;
    private boolean jumpedForExit;

    NeoForgeSurviveNightGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 15000), 1000, 16000);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++ticks > timeoutTicks) {
                complete(client, "timeout");
                return;
            }
            if (client.level == null || client.player == null || client.gameMode == null) {
                return;
            }
            if (client.screen != null && stage != Stage.ENSURE_HOTBAR) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.goal.survival.survive-night requires survival or adventure mode");
            }
            switch (stage) {
                case CHECK -> tickCheck(client);
                case DIG -> tickDig(client);
                case AWAIT_DROPS -> tickAwaitDrops(client);
                case ENSURE_HOTBAR -> tickEnsureHotbar(client);
                case SEAL -> tickSeal(client);
                case WAIT_DAWN -> tickWaitDawn(client);
                case UNSEAL -> tickUnseal(client);
                case EXIT -> tickExit(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void tickCheck(Minecraft client) {
        if (!isNightNow(client.level.getDayTime())) {
            complete(client, "not-night");
            return;
        }
        var player = client.player;
        anchor = player.blockPosition();
        var level = client.level;
        shaftCells = planShaftCells(anchor, SHAFT_DEPTH,
                pos -> !level.isLoaded(pos) || isHazardBlock(level.getBlockState(pos)));
        if (shaftCells.isEmpty()) {
            complete(client, "no-shelter-material");
            return;
        }
        // The dig always attempts unconditionally from here - never gated on whether the player
        // already happens to be carrying a sealable block - since the dug dirt/grass IS the seal
        // material this competency is meant to obtain. Only the delta against this snapshot (see
        // tickAwaitDrops) decides no-shelter-material, and only after digging actually finished.
        preDigBlockItemTotal = countBlockItems(player);
        digIndex = 0;
        stage = Stage.DIG;
    }

    private void tickDig(Minecraft client) {
        if (digIndex >= shaftCells.size()) {
            awaitDropsTicks = 0;
            stage = Stage.AWAIT_DROPS;
            return;
        }
        var target = shaftCells.get(digIndex);
        var state = client.level.getBlockState(target);
        if (state.isAir()) {
            digIndex++;
            activeMiningTarget = null;
            miningTicks = 0;
            return;
        }
        // Live-caught bug: mining a block that requires a correct tool to drop anything (e.g. stone
        // under a thin topsoil layer, bare-handed) could still fully break within the stall budget
        // while yielding zero usable material, wasting the whole attempt before finding out. Checked
        // before spending any ticks on this cell, so the honest give-up is immediate, not deferred.
        if (!yieldsHandDiggableDrop(NeoForgeHardScript.blockId(state))) {
            complete(client, "unshelterable-ground");
            return;
        }
        if (mineVerified(client, target)) {
            complete(client, "unshelterable-ground");
        }
    }

    /**
     * Bounded post-dig wait for the shaft's own drops to actually register in the inventory - see
     * {@link #AWAIT_DROPS_STALL_TICKS}'s own doc for the pickup-delay race this replaces. Only once
     * the inventory shows a genuine increase in block-item count since before digging started (not
     * merely "any block item present", which could be a false positive from unrelated pre-existing
     * inventory contents) does this proceed to seal; a bounded timeout with no such increase is now
     * a truly earned no-shelter-material, not a premature one.
     */
    private void tickAwaitDrops(Minecraft client) {
        stopMovement(client);
        var player = client.player;
        if (materialCollected(countBlockItems(player), preDigBlockItemTotal)) {
            beginSeal(client);
            return;
        }
        if (++awaitDropsTicks > AWAIT_DROPS_STALL_TICKS) {
            complete(client, "no-shelter-material");
        }
    }

    private void beginSeal(Minecraft client) {
        var player = client.player;
        sealItem = firstBlockItem(player);
        if (sealItem == null) {
            complete(client, "no-shelter-material");
            return;
        }
        sealTarget = player.blockPosition().above();
        placeTicks = 0;
        placeAttemptDirection = Direction.NORTH;
        hotbarTransfer = null;
        hotbarTicks = 0;
        stage = Stage.ENSURE_HOTBAR;
    }

    /** Makes sure {@link #sealItem} is selected in the hotbar before sealing, moving it there
     * through the normal guarded inventory-screen quick-move path (matching
     * {@code NeoForgeStoneToolsetGoal.moveItemToHotbar}) when a freshly mined drop landed in main
     * inventory instead. */
    private void tickEnsureHotbar(Minecraft client) {
        var player = client.player;
        var slot = hotbarSlot(player, sealItem);
        if (slot >= 0 && client.screen == null) {
            if (player.getInventory().selected != slot) {
                KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
                return;
            }
            stage = Stage.SEAL;
            return;
        }
        hotbarTicks++;
        if (hotbarTicks > PLACE_STALL_TICKS) {
            // A structured, honest failure - not an exception - so a genuinely stuck inventory
            // transfer after the dig's mutation was already committed reports cleanly instead of
            // leaving the session quarantined as indeterminate.
            complete(client, "no-shelter-material");
            return;
        }
        if (slot >= 0) {
            // Already in the hotbar; only a lingering screen from the transfer below is left to close.
            client.screen.keyPressed(256, 0, 0);
            return;
        }
        if (hotbarTransfer == null) {
            if (client.screen instanceof InventoryScreen) {
                hotbarTransfer = HotbarTransfer.MOVING;
                return;
            }
            if (client.screen != null) return;
            KeyMapping.click(client.options.keyInventory.getKey());
            hotbarTransfer = HotbarTransfer.OPENING;
            return;
        }
        switch (hotbarTransfer) {
            case OPENING -> {
                if (client.screen instanceof InventoryScreen) hotbarTransfer = HotbarTransfer.MOVING;
            }
            case MOVING -> {
                if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
                var menu = screen.getMenu();
                for (var index = 9; index < menu.slots.size(); index++) {
                    if (menu.slots.get(index).getItem().is(sealItem)) {
                        client.gameMode.handleInventoryMouseClick(menu.containerId, index, 0, ClickType.QUICK_MOVE, player);
                        break;
                    }
                }
                hotbarTransfer = HotbarTransfer.CLOSING;
            }
            case CLOSING -> {
                if (client.screen == null) {
                    hotbarTransfer = null;
                    return;
                }
                client.screen.keyPressed(256, 0, 0);
            }
        }
    }

    private void tickSeal(Minecraft client) {
        if (client.level.getBlockState(sealTarget).isAir()) {
            attemptFacePlacement(client, sealTarget);
            return;
        }
        stopUse(client);
        stopMovement(client);
        ticksWaited = 0;
        stage = Stage.WAIT_DAWN;
    }

    private void tickWaitDawn(Minecraft client) {
        ticksWaited++;
        stopMovement(client);
        stopUse(client);
        if (dawnReached(client.level.getDayTime())) {
            activeMiningTarget = null;
            miningTicks = 0;
            stage = Stage.UNSEAL;
        }
    }

    private void tickUnseal(Minecraft client) {
        if (client.level.getBlockState(sealTarget).isAir()) {
            exitCell = client.player.blockPosition();
            exitTicks = 0;
            jumpedForExit = false;
            stage = Stage.EXIT;
            return;
        }
        if (mineVerified(client, sealTarget)) {
            // Already survived to dawn - a stuck exit is a best-effort cosmetic gap, not a shelter
            // failure (see tickExit's own doc): report the success already earned instead of an
            // indeterminate failure after the seal-mutation was committed.
            complete(client, "dawn");
        }
    }

    private void tickExit(Minecraft client) {
        exitTicks++;
        var player = client.player;
        // Best-effort: a placed block under the player's feet is a bonus, not a requirement -
        // surviving the night is this competency's actual contract, so a bounded number of failed
        // jump-and-place attempts still ends in success rather than throwing.
        if (!client.level.getBlockState(exitCell).isAir() || exitTicks > EXIT_STALL_TICKS) {
            complete(client, "dawn");
            return;
        }
        client.options.keyJump.setDown(true);
        jumpedForExit = jumpedForExit || !player.onGround();
        if (jumpedForExit) {
            player.setXRot(90.0F);
            if (placeTicks == 0 || placeTicks % 5 == 1) {
                KeyMapping.click(client.options.keyUse.getKey());
            }
            placeTicks++;
        }
    }

    /**
     * Live-caught bug: this never stopped player movement, unlike every other mining loop in the
     * codebase ({@code NeoForgeGotoMovement.continueHardMine}/{@code continueSoftMine}) - any
     * movement key left down from whatever preceded this invocation kept walking the player away
     * from directly above the shaft cell mid-dig, so the downward raycast increasingly missed the
     * target and the "completion detection" (the cell ever actually turning to air) never fired.
     * Also no longer throws on a stall: the caller decides what an honest give-up looks like in its
     * own context (digging the shelter vs. mining back out at dawn) instead of every caller being
     * forced into the same "irreversible mutation dispatched, now indeterminate" exceptional path -
     * see {@link #tickDig}/{@link #tickUnseal}.
     *
     * @return true once the mining attempt has exceeded its bounded stall budget without breaking
     * the target.
     */
    private boolean mineVerified(Minecraft client, BlockPos target) {
        var player = client.player;
        if (!target.equals(activeMiningTarget)) {
            activeMiningTarget = target;
            miningTicks = 0;
        }
        miningTicks++;
        stopMovement(client);
        lookAt(player, target);
        var hit = player.pick(REACH, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
            if (miningTicks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
        } else {
            client.options.keyAttack.setDown(false);
        }
        return miningTicks > MINE_STALL_TICKS;
    }

    /**
     * Cycles through the four cardinal directions looking for a solid neighbor of {@code target}
     * at eye height (the shared face of any such neighbor is exactly {@code target}, since the
     * player's eye sits within {@code target}'s own vertical band while standing directly below
     * it) then holds the use key at it. The caller re-checks {@code target}'s block state itself
     * on the next tick to notice once placement actually lands.
     */
    private void attemptFacePlacement(Minecraft client, BlockPos target) {
        var player = client.player;
        var slot = hotbarSlot(player, sealItem);
        if (slot < 0) {
            // A structured, honest failure - not an exception - so this reports cleanly instead of
            // leaving the session quarantined as indeterminate after the dig's mutation committed.
            complete(client, "no-shelter-material");
            return;
        }
        if (player.getInventory().selected != slot) {
            KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
        }
        var neighbor = target.relative(placeAttemptDirection);
        var eye = new Vec3(target.getX() + 0.5, player.getEyeY(), target.getZ() + 0.5);
        var neighborState = client.level.getBlockState(neighbor);
        var solid = !neighborState.getCollisionShape(client.level, neighbor).isEmpty();
        player.setYRot(placeAttemptDirection.toYRot());
        player.setYHeadRot(player.getYRot());
        player.setXRot(0.0F);
        placeTicks++;
        if (placeTicks > PLACE_STALL_TICKS) {
            complete(client, "unshelterable-ground");
            return;
        }
        if (solid) {
            var hit = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(neighbor),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(neighbor)
                    && blockHit.getBlockPos().relative(blockHit.getDirection()).equals(target)) {
                if (placeTicks % 5 == 1) KeyMapping.click(client.options.keyUse.getKey());
                return;
            }
        }
        placeAttemptDirection = placeAttemptDirection.getClockWise();
    }

    private void complete(Minecraft client, String reason) {
        releaseInput(client);
        var output = new LinkedHashMap<String, Object>();
        var sheltered = "dawn".equals(reason);
        output.put("sheltered", sheltered);
        output.put("ticksWaited", ticksWaited);
        var position = anchor != null ? anchor : client.player == null ? null : client.player.blockPosition();
        output.put("position", position == null ? Map.of() : positionMap(position));
        output.put("reason", reason);
        result.complete(Map.copyOf(output));
        stage = Stage.DONE;
    }

    private void releaseInput(Minecraft client) {
        stopMovement(client);
        stopUse(client);
        client.options.keyAttack.setDown(false);
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void stopUse(Minecraft client) {
        client.options.keyUse.setDown(false);
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

    private static Item firstBlockItem(LocalPlayer player) {
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (isBlockStack(stack)) return stack.getItem();
        }
        return null;
    }

    private static boolean isBlockStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    /** Total count of block items across the whole inventory - used as the before/after snapshot
     * for {@link #materialCollected}, immune to which specific stack/slot a drop happened to land
     * in. */
    private static int countBlockItems(LocalPlayer player) {
        var total = 0;
        for (var slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (isBlockStack(stack)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Whether digging has actually yielded new sealable material, compared against the inventory
     * snapshot taken before the dig started - a genuine increase, not merely "some block item is
     * present now", which could be a false positive from unrelated pre-existing inventory contents.
     * Package-private and pure for direct testing.
     */
    static boolean materialCollected(int blockItemCountAfterDig, int blockItemCountBeforeDig) {
        return blockItemCountAfterDig > blockItemCountBeforeDig;
    }

    /**
     * True for vanilla block ids that reliably drop something when mined bare-handed - the shaft
     * dig only ever expects to encounter ordinary topsoil, never stone/ore/anything requiring a
     * correct tool to drop at all. Package-private and pure for direct testing.
     */
    static boolean yieldsHandDiggableDrop(String blockId) {
        return blockId != null && HAND_DIGGABLE_SOIL_IDS.contains(blockId.trim().toLowerCase(Locale.ROOT));
    }

    private static int hotbarSlot(LocalPlayer player, Item item) {
        for (var slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(item)) return slot;
        return -1;
    }

    private static boolean isHazardBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.BEDROCK) || !state.getFluidState().isEmpty();
    }

    private static Map<String, Object> positionMap(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    /** True while the current day-time falls inside the vanilla night window. Package-private and
     * pure for direct testing. */
    static boolean isNightNow(long dayTime) {
        var timeOfDay = normalize(dayTime);
        return timeOfDay >= 12000 && timeOfDay < 23500;
    }

    /** True once the day-time has wrapped past the vanilla dawn threshold. Package-private and
     * pure for direct testing. */
    static boolean dawnReached(long dayTime) {
        return normalize(dayTime) < 800;
    }

    private static long normalize(long dayTime) {
        return ((dayTime % 24000) + 24000) % 24000;
    }

    /**
     * Plans a straight-down shaft of up to {@code maxDepth} cells starting directly below
     * {@code start}, stopping early (never past a hazardous cell) the first time {@code unsafe}
     * reports a cell should not be dug - e.g. lava, fire, bedrock, or an unloaded chunk. An empty
     * result means digging even the first cell would be unsafe. Package-private and pure (given a
     * plain predicate) for direct testing on synthetic walkability. */
    static List<BlockPos> planShaftCells(BlockPos start, int maxDepth, Predicate<BlockPos> unsafe) {
        var cells = new ArrayList<BlockPos>(maxDepth);
        var current = start;
        for (var depth = 0; depth < maxDepth; depth++) {
            var next = current.below();
            if (unsafe.test(next)) break;
            cells.add(next);
            current = next;
        }
        return List.copyOf(cells);
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
