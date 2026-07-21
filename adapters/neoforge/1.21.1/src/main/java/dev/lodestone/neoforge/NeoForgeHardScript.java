// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private int ticks;
    private boolean dispatched;
    private boolean done;

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
            if (client.level == null || client.player == null || client.gameMode == null) {
                fail("client world, player, or game mode disappeared");
                return;
            }
            if (client.screen != null || !client.isWindowActive() || !client.player.isAlive()) {
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
            if (!done && ticks >= maxTicks) fail("hard script timed out after " + maxTicks + " ticks");
        } catch (Throwable failure) {
            fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    void cancel(String reason) {
        if (!done) fail(reason == null ? "hard script cancelled" : reason);
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
        client.options.keyAttack.setDown(true);
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            KeyMapping.click(client.options.keyAttack.getKey());
            dispatched = true;
        }
    }

    private void tickPlace(Minecraft client) {
        var state = client.level.getBlockState(target);
        if (!sameBlockFingerprint(dimension, target, state, expectedFingerprint) && dispatched) {
            completePlace(state);
            return;
        }
        if (!sameBlockFingerprint(dimension, target, state, expectedFingerprint) && !state.canBeReplaced()) {
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
        client.options.keyUse.setDown(true);
        if (ticks == 1 || ticks % 5 == 0) {
            invocation.cancellation().commitMutation();
            KeyMapping.click(client.options.keyUse.getKey());
            dispatched = true;
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
