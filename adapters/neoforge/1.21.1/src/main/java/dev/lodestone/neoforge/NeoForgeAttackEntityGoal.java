// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backs {@code minecraft.goal.combat.attack-entity}: engage one specific already-observed entity
 * by numeric id (unlike the older benchmark {@code minecraft.goal.combat.attack-nearest}, which
 * always picks the nearest hostile itself). Approaches with the shared
 * {@link NeoForgeGotoMovement} engine, swings only when the vanilla attack-strength meter is full
 * (no spam-clicking), and hands off to the shared {@link NeoForgeDropCollector} for a brief loot
 * sweep once the target is confirmed dead.
 */
final class NeoForgeAttackEntityGoal {
    private static final double MELEE_RANGE = 2.5;
    private static final double MELEE_RANGE_SQ = MELEE_RANGE * MELEE_RANGE;
    private static final double ATTACK_STRENGTH_READY = 0.9;
    private static final double PLAYER_ENDANGERED_HEALTH = 8.0;
    private static final double LOOT_RADIUS = 6.0;

    /** Pure per-tick target-resolution outcome, package-private and testable without a live
     * client - see {@link #resolveTargetState}. */
    enum ResolutionOutcome { CONTINUE, KILLED, TARGET_LOST }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final int entityId;
    private final double maxChaseBlocks;
    private final int timeoutTicks;
    private final NeoForgeGotoMovement movement = new NeoForgeGotoMovement(false, true);

    private int ticks;
    private BlockPos startPosition;
    private Float lastObservedHealth;
    private int hits;
    private String targetType = "";
    private boolean collecting;
    private NeoForgeDropCollector collector;

    NeoForgeAttackEntityGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.entityId = requiredInt(input, "entityId");
        this.maxChaseBlocks = clamp(numberOrDefault(input, "maxChaseBlocks", 24), 4, 64);
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 1200), 100, 2400);
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
            if (client.level == null || client.player == null || client.gameMode == null
                    || client.screen != null) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.goal.combat.attack-entity requires survival or adventure mode");
            }
            var player = client.player;
            if (startPosition == null) startPosition = player.blockPosition();

            if (collecting) {
                tickCollect(client);
                return;
            }

            var candidate = client.level.getEntity(entityId);
            var target = candidate instanceof LivingEntity living ? living : null;
            trackHits(target);
            var outcome = resolveTargetState(target != null, target != null && target.isAlive());
            switch (outcome) {
                case TARGET_LOST -> complete(client, "target-lost");
                case KILLED -> beginCollect(client, target);
                case CONTINUE -> tickEngage(client, player, target);
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void tickEngage(Minecraft client, net.minecraft.client.player.LocalPlayer player, LivingEntity target) {
        if (isPlayerEndangered(player.getHealth())) {
            complete(client, "player-endangered");
            return;
        }
        if (!withinChaseBudget(startPosition, target.blockPosition(), maxChaseBlocks)) {
            complete(client, "fled-too-far");
            return;
        }
        var distanceSqr = player.distanceToSqr(target);
        if (distanceSqr > MELEE_RANGE_SQ) {
            var outcome = movement.tick(client, player, target.blockPosition(), MELEE_RANGE - 0.5);
            if (outcome == NeoForgeGotoMovement.Outcome.NO_ROUTE
                    || outcome == NeoForgeGotoMovement.Outcome.MUTATION_FAILURE) {
                complete(client, "target-lost");
            }
            return;
        }
        movement.releaseInput(client);
        aimAt(player, target);
        var scale = player.getAttackStrengthScale(0.5F);
        if (shouldSwing(distanceSqr, scale, MELEE_RANGE)) {
            KeyMapping.click(client.options.keyAttack.getKey());
        }
    }

    private void trackHits(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            lastObservedHealth = null;
            return;
        }
        var health = target.getHealth();
        if (lastObservedHealth != null && health < lastObservedHealth) hits++;
        lastObservedHealth = health;
    }

    private void beginCollect(Minecraft client, LivingEntity target) {
        movement.releaseInput(client);
        stopAttack(client);
        targetType = target == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        collecting = true;
        collector = new NeoForgeDropCollector(LOOT_RADIUS, null);
    }

    private void tickCollect(Minecraft client) {
        if (collector.tick(client, client.player) == NeoForgeDropCollector.Outcome.DONE) {
            complete(client, "killed");
        }
    }

    private void complete(Minecraft client, String reason) {
        releaseInput(client);
        var output = new LinkedHashMap<String, Object>();
        output.put("killed", "killed".equals(reason));
        output.put("targetType", targetType);
        output.put("hits", hits);
        output.put("ticksElapsed", ticks);
        output.put("lootCollected", collector == null ? Map.of() : Map.copyOf(collector.collectedDelta(client.player)));
        output.put("reason", reason);
        result.complete(Map.copyOf(output));
    }

    private void releaseInput(Minecraft client) {
        movement.releaseInput(client);
        if (collector != null) collector.releaseInput(client);
        stopAttack(client);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    private static void aimAt(net.minecraft.client.player.LocalPlayer player, LivingEntity target) {
        var aim = target.position().add(0.0, Math.max(0.4, target.getBbHeight() * 0.5), 0.0);
        var eye = player.getEyePosition();
        var dx = aim.x - eye.x;
        var dy = aim.y - eye.y;
        var dz = aim.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    /** True once the target's attack strength meter (0.9+) and melee range both clear - never
     * spam-clicks a fresh unready swing. Package-private and pure for direct testing. */
    static boolean shouldSwing(double distanceSqr, double attackStrengthScale, double meleeRange) {
        return distanceSqr <= meleeRange * meleeRange && attackStrengthScale >= ATTACK_STRENGTH_READY;
    }

    /** True while the target has not wandered farther than {@code maxChaseBlocks} from the
     * position the player started the chase at. Package-private and pure for direct testing. */
    static boolean withinChaseBudget(BlockPos start, BlockPos current, double maxChaseBlocks) {
        return start.distSqr(current) <= maxChaseBlocks * maxChaseBlocks;
    }

    /** True once the player's own health has dropped low enough that the fight should be
     * abandoned rather than pressed. Package-private and pure for direct testing. */
    static boolean isPlayerEndangered(double health) {
        return health < PLAYER_ENDANGERED_HEALTH;
    }

    /** Per-tick target resolution: {@code present} is whether {@code client.level.getEntity(id)}
     * still resolved to a living entity this tick, {@code alive} whether it reported alive. A
     * previously-present, now-dead target is a kill; a target that has vanished outright (already
     * removed, chunk-unloaded, or despawned) without ever being observed dead is lost instead.
     * Package-private and pure for direct testing. */
    static ResolutionOutcome resolveTargetState(boolean present, boolean alive) {
        if (present && !alive) return ResolutionOutcome.KILLED;
        if (!present) return ResolutionOutcome.TARGET_LOST;
        return ResolutionOutcome.CONTINUE;
    }

    private static int requiredInt(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        return number.intValue();
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
