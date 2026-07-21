// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Drives one real vanilla block break for {@code minecraft.player.interact}'s "attack" action
 * against a targeted block with nonzero hardness.
 *
 * <p>{@code minecraft.player.interact} is a raw, low-level MCP primitive the model calls directly
 * turn-by-turn - invoked once per MCP call, unlike the native goal actors (e.g.
 * {@link NeoForgeNavigationGoal}), which run their own tick loop inside the game engine across a
 * whole multi-minute capability call. But vanilla block breaking still requires the attack key
 * held down across many real client ticks while aimed at the block, accumulating destroy progress
 * each tick until it reaches 1.0; a single momentary {@code KeyMapping.click(...)} never sets
 * {@code isDown()}, so {@code continueDestroyBlock} is never driven and the attack silently no-ops
 * against anything but an instant-break block. See the "attack" case this backs in
 * {@link NeoForgeClientController} for the full history of that bug.
 *
 * <p>Rather than inventing a new execution model, this mirrors the exact hold-and-click
 * convention every block-breaking goal actor in this package already uses - hold
 * {@code keyAttack} down, re-click every 10 ticks, bound the wait with a destroy-speed-derived
 * timeout (see {@link NeoForgeNavigationGoal#mineTimeoutTicks} and
 * {@code NeoForgeNavigationGoal#executeMineStep}) - by registering itself into
 * {@code NeoForgeClientController.ClientBridgeImpl} exactly like a native goal actor (a field,
 * ticked once per real client tick from {@link NeoForgeClientController#onClientTick}) instead of
 * completing synchronously inside the single client-thread callback the "use"/"pick" actions (and
 * "attack" against an entity or an instant-break block) still use. From the MCP caller's
 * perspective this is still a single request/response - the returned future simply does not
 * resolve until the real ticks vanilla block breaking needs have elapsed.
 */
final class NeoForgeAttackHold {
    /**
     * A raw primitive call must stay bounded well inside its capability's {@code timeoutMs}
     * (protocol/catalog/core-capabilities.json's minecraft.player.interact entry), unlike a native
     * goal actor's own multi-minute budget - mirrors the same ~100-tick "bounded normal-input
     * timeout for a single attempt" convention {@code NeoForgeNavigationGoal.PLACE_TIMEOUT_TICKS}
     * and {@code NeoForgeGoalSupervisor.tickObstructionEscape} already use, so a block that is
     * technically breakable but absurdly slow without the right tool (e.g. bare-handed obsidian)
     * fails fast with a clear timeout instead of holding the call open for minutes.
     */
    static final int MAX_HOLD_TICKS = 100;

    /**
     * Outcome of one simulated tick against the held target. Package-private and pure so it can be
     * exercised directly by a regression test without a live ClientLevel/Player.
     */
    enum TickOutcome { HOLDING, WAITING_FOR_AIM, BROKEN, TIMED_OUT }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final BlockPos target;
    private final String blockId;
    private final int timeoutTicks;
    private int ticks;

    NeoForgeAttackHold(InvocationContext invocation, CompletableFuture<Map<String, Object>> result,
                       BlockPos target, double progressPerTick, String blockId) {
        this.invocation = invocation;
        this.result = result;
        this.target = target.immutable();
        this.blockId = blockId;
        this.timeoutTicks = Math.min(NeoForgeNavigationGoal.mineTimeoutTicks(progressPerTick), MAX_HOLD_TICKS);
    }

    boolean done() {
        return result.isDone();
    }

    /** Force-stops this hold outside the normal tick loop - used by session reconcile to abandon
     * an in-flight hold the same way {@code quiesceAndObserve()} force-stops every native goal
     * actor. Does not touch {@code keyAttack} itself; the caller already releases it unconditionally
     * as part of the same reconcile. */
    void fail(Throwable reason) {
        result.completeExceptionally(reason);
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (client.level == null || client.player == null || client.gameMode == null) {
                throw new IllegalStateException("client player/world unavailable during a held attack");
            }
            var player = client.player;
            var state = client.level.getBlockState(target);
            // A fluid may be part of a waterlogged or otherwise non-air block. Only the
            // original block becoming air is a successful break observation.
            var cleared = state.isAir();
            var aimed = !cleared && aimedAtTarget(player);
            var outcome = nextTick(cleared, aimed, ++ticks, timeoutTicks);
            switch (outcome) {
                case BROKEN -> {
                    client.options.keyAttack.setDown(false);
                    result.complete(NeoForgeClientController.interactOutput("attack", true, target, blockId));
                }
                case TIMED_OUT -> {
                    client.options.keyAttack.setDown(false);
                    result.completeExceptionally(new IllegalStateException(
                            "held attack timed out before breaking " + blockId + " at " + target));
                }
                case HOLDING -> {
                    if (ticks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
                    client.options.keyAttack.setDown(true);
                }
                case WAITING_FOR_AIM -> client.options.keyAttack.setDown(false);
            }
        } catch (Throwable failure) {
            client.options.keyAttack.setDown(false);
            result.completeExceptionally(failure);
        }
    }

    private boolean aimedAtTarget(LocalPlayer player) {
        var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target);
    }

    /**
     * Pure per-tick decision: hold (target still present and aimed at), pause (still present but
     * momentarily out of aim - does not abandon, just skips holding this tick and keeps counting
     * toward the timeout), or exit (broken, or the bounded budget is exhausted).
     * {@code ticksElapsed} must already include this tick's increment.
     */
    static TickOutcome nextTick(boolean targetCleared, boolean aimed, int ticksElapsed, int timeoutTicks) {
        if (targetCleared) return TickOutcome.BROKEN;
        if (ticksElapsed > timeoutTicks) return TickOutcome.TIMED_OUT;
        return aimed ? TickOutcome.HOLDING : TickOutcome.WAITING_FOR_AIM;
    }
}
