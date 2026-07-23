// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** One bounded, deterministic client-side hard script. No model or goal planner is involved. */
final class NeoForgeHardScript {
    private final String id;
    private final String kind;
    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final BlockPos target;
    private final BlockPos support;
    private final Direction face;
    private final String expectedFingerprint;
    private final String dimension;
    private final String itemId;
    private final int selectedSlot;
    private final int maxTicks;
    private final String beforeBlock;
    private final boolean mining;
    /** Only set for the "open-screen"/"close-screen" kinds - see {@link #openScreen} and
     * {@link #closeScreen}. The block-targeted fields above are unused (null/empty/-1) then. */
    private final Screen beforeScreen;
    private final String beforeScreenClass;
    private final Supplier<Map<String, Object>> extraOutputSupplier;
    private int ticks;
    private boolean dispatched;
    private boolean done;
    private boolean forcedGrab;

    private NeoForgeHardScript(String id, String kind, InvocationContext invocation,
                               CompletableFuture<Map<String, Object>> result, BlockPos target,
                               BlockPos support, Direction face, String expectedFingerprint,
                               String dimension, String itemId, int selectedSlot, int maxTicks,
                               String beforeBlock, boolean mining) {
        this.id = id;
        this.kind = kind;
        this.invocation = invocation;
        this.result = result;
        this.target = target.immutable();
        this.support = support == null ? null : support.immutable();
        this.face = face;
        this.expectedFingerprint = expectedFingerprint;
        this.dimension = dimension;
        this.itemId = itemId;
        this.selectedSlot = selectedSlot;
        this.maxTicks = maxTicks;
        this.beforeBlock = beforeBlock;
        this.mining = mining;
        this.beforeScreen = null;
        this.beforeScreenClass = "";
        this.extraOutputSupplier = null;
    }

    /**
     * Backs {@code minecraft.ui.inventory.open} and {@code minecraft.ui.screen.close}: no block
     * target is involved, so every block-targeted field above stays at a neutral default and
     * {@link #tick} dispatches straight to {@link #tickOpenScreen}/{@link #tickCloseScreen}.
     */
    private NeoForgeHardScript(String id, String kind, InvocationContext invocation,
                               CompletableFuture<Map<String, Object>> result, int maxTicks,
                               Screen beforeScreen, String beforeScreenClass,
                               Supplier<Map<String, Object>> extraOutputSupplier) {
        this.id = id;
        this.kind = kind;
        this.invocation = invocation;
        this.result = result;
        this.target = null;
        this.support = null;
        this.face = null;
        this.expectedFingerprint = null;
        this.dimension = null;
        this.itemId = "";
        this.selectedSlot = -1;
        this.maxTicks = maxTicks;
        this.beforeBlock = "";
        this.mining = false;
        this.beforeScreen = beforeScreen;
        this.beforeScreenClass = beforeScreenClass;
        this.extraOutputSupplier = extraOutputSupplier;
    }

    static NeoForgeHardScript mine(String id, InvocationContext invocation,
                                   CompletableFuture<Map<String, Object>> result,
                                   BlockPos target, String expectedFingerprint,
                                   String dimension, String beforeBlock, int maxTicks) {
        return new NeoForgeHardScript(id, "mine", invocation, result, target, null, null,
                expectedFingerprint, dimension, "", -1, maxTicks, beforeBlock, true);
    }

    static NeoForgeHardScript place(String id, InvocationContext invocation,
                                    CompletableFuture<Map<String, Object>> result,
                                    BlockPos target, BlockPos support, Direction face,
                                    String expectedFingerprint, String dimension,
                                    String itemId, int selectedSlot, int maxTicks) {
        return new NeoForgeHardScript(id, "place", invocation, result, target, support, face,
                expectedFingerprint, dimension, itemId, selectedSlot, maxTicks, "", false);
    }

    /** Backs {@code minecraft.ui.inventory.open}. {@code extraOutputSupplier} is called once on
     * success to merge in cheap ui-state fields (e.g. screenToken/snapshotRevision). */
    static NeoForgeHardScript openScreen(String id, InvocationContext invocation,
                                        CompletableFuture<Map<String, Object>> result, int maxTicks,
                                        Supplier<Map<String, Object>> extraOutputSupplier) {
        return new NeoForgeHardScript(id, "open-screen", invocation, result, maxTicks, null, "",
                extraOutputSupplier);
    }

    /** Backs {@code minecraft.ui.screen.close}. {@code beforeScreen} is the exact screen instance
     * observed when the call started, so a transition to any other screen counts as success even
     * when Escape does not close it outright. */
    static NeoForgeHardScript closeScreen(String id, InvocationContext invocation,
                                         CompletableFuture<Map<String, Object>> result, int maxTicks,
                                         Screen beforeScreen, String beforeScreenClass) {
        return new NeoForgeHardScript(id, "close-screen", invocation, result, maxTicks, beforeScreen,
                beforeScreenClass, null);
    }

    boolean done() {
        return done;
    }

    boolean mutationDispatched() {
        return dispatched;
    }

    String id() {
        return id;
    }

    String kind() {
        return kind;
    }

    void tick(Minecraft client) {
        if (done) return;
        ticks++;
        try {
            invocation.cancellation().throwIfCancelled();
            switch (kind) {
                case "mine", "place" -> tickBlockTargeted(client);
                case "open-screen" -> tickOpenScreen(client);
                case "close-screen" -> tickCloseScreen(client);
                default -> throw new IllegalStateException("unknown hard script kind: " + kind);
            }
            if (!done && ticks >= maxTicks) fail(timeoutMessage(client));
        } catch (Throwable failure) {
            fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private String timeoutMessage(Minecraft client) {
        return switch (kind) {
            case "open-screen" -> "OPEN_TIMEOUT: inventory screen did not open within " + maxTicks + " ticks";
            case "close-screen" -> "CLOSE_TIMEOUT: " + (client.screen == null ? "screen" : client.screen.getClass().getName())
                    + " did not close within " + maxTicks + " ticks";
            default -> "hard script timed out after " + maxTicks + " ticks";
        };
    }

    void cancel(String reason) {
        if (!done) fail(reason == null ? "hard script cancelled" : reason);
    }

    private void tickBlockTargeted(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null) {
            fail("client world, player, or game mode disappeared");
            return;
        }
        // Keep Focus deliberately permits authentic client input while the launcher window is not foregrounded.
        // Focus is not a gameplay lifecycle signal; a screen, death, world loss, or cancellation is.
        if (client.screen != null || !client.player.isAlive()) {
            fail("hard script cancelled by client lifecycle state");
            return;
        }
        if (!dimension.equals(client.level.dimension().location().toString())) {
            fail("player dimension changed while hard script was running");
            return;
        }
        if (!client.level.isInWorldBounds(target) || !client.level.isLoaded(target)) {
            fail("target chunk is not loaded");
            return;
        }
        if (mining) tickMine(client);
        else tickPlace(client);
    }

    private void tickOpenScreen(Minecraft client) {
        if (client.screen instanceof InventoryScreen) {
            completeOpen(client);
            return;
        }
        if (client.screen != null) {
            fail("OTHER_SCREEN_OPEN: " + client.screen.getClass().getName()
                    + " is open; close it before opening the inventory");
            return;
        }
        if (client.level == null || client.player == null) {
            fail("client player/world is unavailable");
            return;
        }
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            KeyMapping.click(client.options.keyInventory.getKey());
            dispatched = true;
        }
    }

    private void tickCloseScreen(Minecraft client) {
        if (client.screen == null || client.screen != beforeScreen) {
            completeClose(client);
            return;
        }
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            // Mirrors minecraft.ui.key's screen-open branch: dispatch Escape straight to the
            // active screen instead of through a KeyMapping, since Escape is not itself bound to
            // one - see NeoForgeClientController#uiKey.
            client.screen.keyPressed(256, 0, 0);
            dispatched = true;
        }
    }

    private void completeOpen(Minecraft client) {
        var output = new LinkedHashMap<String, Object>();
        output.put("opened", true);
        output.put("alreadyOpen", !dispatched);
        output.put("screenClass", client.screen.getClass().getName());
        if (extraOutputSupplier != null) {
            output.putAll(extraOutputSupplier.get());
        }
        finish(output);
    }

    private void completeClose(Minecraft client) {
        var output = new LinkedHashMap<String, Object>();
        output.put("closed", true);
        output.put("alreadyClosed", !dispatched);
        output.put("beforeScreenClass", beforeScreenClass);
        output.put("afterInWorld", client.level != null && client.player != null);
        finish(output);
    }

    private void tickMine(Minecraft client) {
        var state = client.level.getBlockState(target);
        var current = blockFingerprint(dimension, target, state, null);
        if (!current.equals(expectedFingerprint)) {
            if (dispatched) completeMine(state);
            else fail("TARGET_CHANGED before mining dispatch");
            return;
        }
        var player = client.player;
        var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || !target.equals(blockHit.getBlockPos())) {
            aimAt(player, target);
            client.options.keyAttack.setDown(false);
            return;
        }
        forceMouseGrab(client);
        client.options.keyAttack.setDown(true);
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            KeyMapping.click(client.options.keyAttack.getKey());
            dispatched = true;
        }
    }

    private void tickPlace(Minecraft client) {
        var state = client.level.getBlockState(target);
        var decision = placeDecision(matchesPlacedItemBlock(state),
                !sameBlockFingerprint(dimension, target, state, expectedFingerprint));
        if (decision == PlaceDecision.SUCCESS) {
            completePlace(state);
            return;
        }
        if (decision == PlaceDecision.TARGET_CHANGED) {
            fail("TARGET_CHANGED before placement dispatch");
            return;
        }
        var player = client.player;
        if (player.getInventory().selected != selectedSlot) {
            fail("selected hotbar slot changed while hard script was running");
            return;
        }
        var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)
                || !support.equals(blockHit.getBlockPos()) || blockHit.getDirection() != face) {
            aimAtFace(player, support, face);
            client.options.keyUse.setDown(false);
            return;
        }
        forceMouseGrab(client);
        client.options.keyUse.setDown(true);
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            KeyMapping.click(client.options.keyUse.getKey());
            dispatched = true;
        }
    }

    /**
     * Vanilla {@code Minecraft#tick} only advances held-attack destroy progress while the mouse
     * is grabbed, so a background/unfocused window turns held mining into no-op arm taps
     * (live-observed: repeated 300-tick mine timeouts whenever another app takes focus). Force
     * the grabbed flag - without touching cursor mode, so the OS cursor stays free for the user -
     * while a block-targeted script drives input; {@link #releaseInputs} restores it.
     */
    private void forceMouseGrab(Minecraft client) {
        if (client.mouseHandler.isMouseGrabbed()) return;
        forcedGrab = setGrabFlag(client, true);
    }

    private static boolean setGrabFlag(Minecraft client, boolean value) {
        try {
            var field = MouseHandler.class.getDeclaredField("mouseGrabbed");
            field.setAccessible(true);
            field.setBoolean(client.mouseHandler, value);
            return true;
        } catch (ReflectiveOperationException e) {
            // Obfuscated production runtime: the dev-only field name is absent, so grab-dependent
            // input keeps requiring a focused window there.
            return false;
        }
    }

    private void completeMine(BlockState after) {
        var output = new LinkedHashMap<String, Object>();
        output.put("scriptId", id);
        output.put("kind", kind);
        output.put("ticksElapsed", ticks);
        output.put("completed", true);
        output.put("position", position(target));
        output.put("beforeBlock", beforeBlock);
        output.put("afterBlock", blockId(after));
        finish(output);
    }

    private void completePlace(BlockState after) {
        var output = new LinkedHashMap<String, Object>();
        output.put("scriptId", id);
        output.put("kind", kind);
        output.put("ticksElapsed", ticks);
        output.put("completed", true);
        output.put("position", position(target));
        output.put("item", itemId);
        output.put("selectedSlot", selectedSlot);
        output.put("afterBlock", blockId(after));
        finish(output);
    }

    private void finish(Map<String, Object> output) {
        if (done) return;
        done = true;
        releaseInputs();
        result.complete(Map.copyOf(output));
    }

    private void fail(String message) {
        if (done) return;
        done = true;
        releaseInputs();
        result.completeExceptionally(new IllegalStateException(message));
    }

    private void releaseInputs() {
        var client = Minecraft.getInstance();
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        if (forcedGrab && !client.isWindowActive()) {
            setGrabFlag(client, false);
        }
        forcedGrab = false;
    }

    /**
     * Outcome of comparing the live destination cell against its pre-dispatch snapshot while a
     * place hard script is running. Deliberately a pure enum/decision pair (no Minecraft classes)
     * so the decision itself is unit-testable without a live client - see
     * {@link #matchesPlacedItemBlock} for the one real-registry lookup this depends on.
     */
    enum PlaceDecision {
        /** The destination already holds the block form of the item being placed: the placement
         * dispatched and landed, regardless of whether {@link #dispatched} was observed true yet. */
        SUCCESS,
        /** The destination changed away from its pre-dispatch snapshot into something other than
         * the placed item's block: another actor (grief, decay, a neighbour update) claimed the
         * cell first. */
        TARGET_CHANGED,
        /** The destination still matches its pre-dispatch snapshot (including "still the original
         * replaceable plant"): keep aiming and clicking. */
        CONTINUE
    }

    static PlaceDecision placeDecision(boolean destinationMatchesPlacedItemBlock,
                                        boolean destinationFingerprintChanged) {
        if (destinationMatchesPlacedItemBlock) return PlaceDecision.SUCCESS;
        if (destinationFingerprintChanged) return PlaceDecision.TARGET_CHANGED;
        return PlaceDecision.CONTINUE;
    }

    /** True when {@code state} is already the block form of {@link #itemId}, i.e. the placement
     * landed - even if it landed into a destination that started out as a replaceable plant such
     * as grass, so the fingerprint captured before dispatch no longer matches. */
    private boolean matchesPlacedItemBlock(BlockState state) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        return item instanceof BlockItem blockItem && state.is(blockItem.getBlock());
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    static String serializedState(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    static String blockFingerprint(String dimension, BlockPos pos, BlockState state, String face) {
        return fingerprint(dimension, pos, serializedState(state), face);
    }

    static String fingerprint(String dimension, BlockPos pos, String serializedState, String face) {
        var canonical = "b1|" + dimension + "|" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()
                + "|" + serializedState + "|" + (face == null ? "" : face);
        return "b1:" + sha256(canonical);
    }

    static boolean sameBlockFingerprint(String dimension, BlockPos pos, BlockState state, String expected) {
        return blockFingerprint(dimension, pos, state, null).equals(expected);
    }

    static Map<String, Object> snapshot(String dimension, BlockPos pos, BlockState state) {
        return Map.of("dimension", dimension, "position", position(pos), "block", blockId(state),
                "state", serializedState(state), "blockFingerprint", blockFingerprint(dimension, pos, state, null));
    }

    static Map<String, Object> position(BlockPos pos) {
        return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var byteValue : bytes) hex.append(String.format("%02x", byteValue));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    static void aimAt(LocalPlayer player, BlockPos pos) {
        aimAt(player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    static void aimAtFace(LocalPlayer player, BlockPos support, Direction face) {
        var center = support.getCenter().add(face.getStepX() * 0.49, face.getStepY() * 0.49,
                face.getStepZ() * 0.49);
        aimAt(player, center.x, center.y, center.z);
    }

    static void aimAt(LocalPlayer player, double x, double y, double z) {
        var dx = x - player.getX();
        var dy = y - player.getEyeY();
        var dz = z - player.getZ();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setYHeadRot(player.getYRot());
        player.setXRot((float) Math.max(-89.0, Math.min(89.0,
                -Math.toDegrees(Math.atan2(dy, horizontal)))));
    }
}
