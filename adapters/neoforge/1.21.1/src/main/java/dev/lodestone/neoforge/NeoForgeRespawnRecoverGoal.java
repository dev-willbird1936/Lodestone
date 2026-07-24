// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.UiBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backs {@code minecraft.goal.survival.respawn-recover}: if a {@link DeathScreen} is showing,
 * clicks its "Respawn" widget directly (the same native widget-click mechanics
 * {@code minecraft.ui.click} uses, without going through that capability's own screen-token/MCP
 * plumbing), waits for the player to come back alive, then optionally walks back to the last
 * known death position (from {@link NeoForgePlayerAlerts#lastDeathPosition()}) and runs the
 * shared {@link NeoForgeDropCollector} to recover whatever is still there.
 */
final class NeoForgeRespawnRecoverGoal {
    private static final int DEATH_SCREEN_GRACE_TICKS = 20;
    private static final double RECOVER_RADIUS = 8.0;
    private static final double APPROACH_ARRIVE_RADIUS = 2.0;

    private enum Stage { DETECT, WAIT_RESPAWN, DECIDE, APPROACH, COLLECT, DONE }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final boolean recoverItems;
    private final int timeoutTicks;
    private final BlockPos deathPosition;

    private Stage stage = Stage.DETECT;
    private int ticks;
    private int detectTicks;
    private boolean wasDead;
    private boolean respawned;
    private NeoForgeGotoMovement movement;
    private NeoForgeDropCollector collector;

    NeoForgeRespawnRecoverGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result,
                               Map<String, Object> lastDeathPosition) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.recoverItems = !Boolean.FALSE.equals(input.get("recoverItems"));
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 2400), 100, 6000);
        this.deathPosition = toBlockPos(lastDeathPosition);
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
            if (stage != Stage.DETECT && stage != Stage.WAIT_RESPAWN && client.screen != null) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.goal.survival.respawn-recover requires survival or adventure mode");
            }
            switch (stage) {
                case DETECT -> tickDetect(client);
                case WAIT_RESPAWN -> tickWaitRespawn(client);
                case DECIDE -> tickDecide(client);
                case APPROACH -> tickApproach(client);
                case COLLECT -> tickCollect(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void tickDetect(Minecraft client) {
        if (client.screen instanceof DeathScreen deathScreen) {
            wasDead = true;
            clickRespawn(client, deathScreen);
            stage = Stage.WAIT_RESPAWN;
            return;
        }
        var player = client.player;
        var currentlyDead = !player.isAlive() || player.getHealth() <= 0.0F;
        if (currentlyDead && ++detectTicks <= DEATH_SCREEN_GRACE_TICKS) {
            return;
        }
        wasDead = false;
        stage = Stage.DECIDE;
    }

    /**
     * Clicks the death screen's "Respawn" widget by projecting its bounds exactly like
     * {@code NeoForgeClientController#projectNode} does, then invoking
     * {@link net.minecraft.client.gui.screens.Screen#mouseClicked} directly - the same mechanics
     * {@code minecraft.ui.click} uses, without that capability's own screen-token/session
     * plumbing (this native actor already owns exclusive input, so no such guard is needed here).
     */
    private void clickRespawn(Minecraft client, DeathScreen screen) {
        for (var child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            if (!"Respawn".equals(widget.getMessage().getString())) continue;
            var bounds = new UiBounds(widget.getX(), widget.getY(),
                    Math.max(0, widget.getWidth()), Math.max(0, widget.getHeight()));
            screen.mouseClicked(bounds.centerX(), bounds.centerY(), 0);
            return;
        }
        throw new IllegalStateException("death screen has no clickable Respawn widget");
    }

    private void tickWaitRespawn(Minecraft client) {
        var player = client.player;
        if (player.isAlive() && player.getHealth() > 0.0F && !(client.screen instanceof DeathScreen)) {
            respawned = true;
            stage = Stage.DECIDE;
        }
    }

    private void tickDecide(Minecraft client) {
        var earlyOutcome = resolveEarlyOutcome(wasDead, recoverItems, deathPosition != null);
        if (earlyOutcome != null) {
            complete(client, earlyOutcome, Map.of());
            return;
        }
        if (client.screen != null) return;
        var dropsPresent = !client.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(deathPosition).inflate(RECOVER_RADIUS), ItemEntity::isAlive).isEmpty();
        if (!dropsPresent) {
            complete(client, "drops-gone", Map.of());
            return;
        }
        movement = new NeoForgeGotoMovement(false, true);
        stage = Stage.APPROACH;
    }

    private void tickApproach(Minecraft client) {
        var outcome = movement.tick(client, client.player, deathPosition, APPROACH_ARRIVE_RADIUS);
        switch (outcome) {
            case ARRIVED, NO_ROUTE, MUTATION_FAILURE -> {
                movement.releaseInput(client);
                collector = new NeoForgeDropCollector(RECOVER_RADIUS, null);
                stage = Stage.COLLECT;
            }
            case MOVING -> { }
        }
    }

    private void tickCollect(Minecraft client) {
        if (collector.tick(client, client.player) == NeoForgeDropCollector.Outcome.DONE) {
            Map<String, Object> recovered = Map.copyOf(collector.collectedDelta(client.player));
            complete(client, recovered.isEmpty() ? "drops-gone" : "recovered", recovered);
        }
    }

    private void complete(Minecraft client, String reason, Map<String, Object> itemsRecovered) {
        releaseInput(client);
        var output = new LinkedHashMap<String, Object>();
        output.put("respawned", respawned);
        output.put("deathPosition", deathPosition == null ? null : positionMap(deathPosition));
        output.put("itemsRecovered", Map.copyOf(itemsRecovered));
        output.put("reason", reason);
        result.complete(output);
        stage = Stage.DONE;
    }

    private void releaseInput(Minecraft client) {
        if (movement != null) movement.releaseInput(client);
        if (collector != null) collector.releaseInput(client);
    }

    /**
     * Pure flow decision across the whole (wasDead, recoverItems, deathPositionKnown) state
     * matrix, resolved before any world/entity query is needed: {@code null} means "keep going -
     * recovery must still check whether the drops are actually still there", every other value is
     * an immediate terminal reason. Package-private and pure for direct testing.
     */
    static String resolveEarlyOutcome(boolean wasDead, boolean recoverItems, boolean deathPositionKnown) {
        if (!wasDead) return "not-dead";
        if (!recoverItems || !deathPositionKnown) return "nothing-to-recover";
        return null;
    }

    private static BlockPos toBlockPos(Map<String, Object> position) {
        if (position == null) return null;
        var x = position.get("x");
        var y = position.get("y");
        var z = position.get("z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;
        return new BlockPos((int) Math.floor(((Number) x).doubleValue()),
                (int) Math.floor(((Number) y).doubleValue()), (int) Math.floor(((Number) z).doubleValue()));
    }

    private static Map<String, Object> positionMap(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
