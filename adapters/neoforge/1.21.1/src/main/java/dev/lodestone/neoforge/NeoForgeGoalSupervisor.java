// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic tick-level preemption layer shared by survival actors. */
final class NeoForgeGoalSupervisor {
    private final NeoForgeGoalPolicy policy;
    private final Collection<String> actions;
    private final Collection<String> diagnostics;
    private int recoveryTicks;
    private int brakeTicks;
    private Vec3 lastPosition;
    private int stalledMovementTicks;
    private BlockPos obstructionClearTarget;
    private int obstructionClearTicks;
    private BlockPos obstructionEscapeTarget;
    private final Set<Long> rejectedEscapeTargets = new HashSet<>();
    private double obstructionEscapeLastDistance = Double.POSITIVE_INFINITY;
    private int obstructionEscapeNoProgressTicks;
    private int obstructionEscapeTicks;
    private long navigationReplanEpoch;
    private long navigationAbandonEpoch;
    private String movementIntent;
    private BlockPos recoveryClusterAnchor;
    private final Set<Long> latchedEscapeTargets = new HashSet<>();
    private boolean obstructionClearUsedInCluster;
    private int waterRecoveryTicks;
    private final NeoForgeSuffocationReflex suffocationReflex = new NeoForgeSuffocationReflex();
    private int suffocationEscapeTicks;

    NeoForgeGoalSupervisor(NeoForgeGoalPolicy policy, Collection<String> actions,
                           Collection<String> diagnostics) {
        this.policy = policy;
        this.actions = actions;
        this.diagnostics = diagnostics;
    }

    boolean tick(Minecraft client) {
        var movementExpected = client.options.keyUp.isDown() || client.options.keySprint.isDown()
                || client.options.keyJump.isDown();
        return tick(client, movementExpected, null);
    }

    boolean tick(Minecraft client, boolean movementExpected) {
        return tick(client, movementExpected, null);
    }

    boolean tick(Minecraft client, boolean movementExpected, String intent) {
        if (!policy.supervisorEnabled() || client.level == null || client.player == null
                || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || client.screen != null) return false;
        var player = client.player;
        updateMovementIntent(player, movementExpected, intent);

        if (policy.hazardAvoidanceEnabled() && (player.isInLava() || player.isOnFire())) {
            return escapeFireOrLava(client, player);
        }

        if (tickImmediateSurvival(client)) return true;

        if (obstructionClearTarget != null) return tickObstructionClear(client, player);
        if (obstructionEscapeTarget != null) return tickObstructionEscape(client, player);

        if (preventBareHandToolMining(client, player)) return true;

        if (recoverObstructionOrStall(client, player, movementExpected)) return true;

        if (policy.fallProtectionEnabled() && unsafeForwardDrop(client, player)) {
            stopMovement(client);
            client.options.keyShift.setDown(true);
            actions.add("safety:block-unsafe-forward-drop");
            diagnostics.add("fall-risk:unsafe-forward-drop-at-" + player.blockPosition());
            recoveryTicks = 6;
            return true;
        }

        if (brakeTicks > 0) {
            client.options.keyShift.setDown(true);
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            brakeTicks--;
            actions.add("safety:brake-fall");
            if (brakeTicks == 0) client.options.keyShift.setDown(false);
            return true;
        }

        if (player.fallDistance > 2.0F && !player.onGround() && policy.fallProtectionEnabled()) {
            brakeTicks = 5;
            diagnostics.add("fall-risk:braking-inputs");
            return true;
        }

        var threat = findThreat(player);
        if (policy.threatPreemptionEnabled() && threat != null
                && (!policy.combatPolicy().equals("none") || policy.highSafety())) {
            if (recoveryTicks > 0) recoveryTicks--;
            if (policy.combatPolicy().equals("avoid") || policy.combatPolicy().equals("none")) {
                retreat(client, player, threat);
            }
            else defendOrRetreat(client, player, threat);
            return true;
        }
        if (recoveryTicks > 0) {
            recoveryTicks--;
            setRecoveryMovement(client, false);
            return true;
        }
        return false;
    }

    /** Highest-priority normal-input survival response. Call before goal liveness or navigation. */
    boolean tickImmediateSurvival(Minecraft client) {
        if (!policy.supervisorEnabled() || !policy.hazardAvoidanceEnabled()
                || client.level == null || client.player == null) return false;
        var player = client.player;
        if (player.isInLava() || player.isOnFire()) return escapeFireOrLava(client, player);
        if (tickSuffocationEscape(client, player)) return true;
        var decision = NeoForgeSurvivalInvariant.decide(player.isDeadOrDying(), player.getHealth(),
                false, player.isInWater(), player.getAirSupply(), player.getMaxAirSupply());
        if (decision != NeoForgeSurvivalInvariant.Action.WATER_RETREAT) {
            waterRecoveryTicks = 0;
            return false;
        }

        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyJump.setDown(true);

        var route = NeoForgeSafePathPlanner.findWaterRetreat(client.level, player, player.blockPosition(),
                policy, player.getAirSupply());
        if (route.size() >= 2) {
            var next = route.get(1);
            var horizontal = next.getX() != player.blockPosition().getX()
                    || next.getZ() != player.blockPosition().getZ();
            var allowHorizontal = NeoForgeSurvivalInvariant.allowHorizontalWaterInput(true, horizontal);
            if (allowHorizontal) lookAt(player, next);
            client.options.keyUp.setDown(allowHorizontal);
            actions.add("safety:verified-water-retreat");
            if (waterRecoveryTicks++ % 20 == 0) {
                diagnostics.add("water-retreat:air=" + player.getAirSupply() + "/"
                        + player.getMaxAirSupply() + ":edges=" + (route.size() - 1)
                        + ":exit=" + route.getLast());
            }
        } else {
            // With no verified connected edge, never guess horizontally. Continue ascending so
            // a failed goal does not abandon the player underwater while air remains.
            client.options.keyUp.setDown(false);
            actions.add("safety:water-ascent-no-horizontal-route");
            if (waterRecoveryTicks++ % 20 == 0) {
                diagnostics.add("water-retreat:no-air-budgeted-route:air=" + player.getAirSupply()
                        + "/" + player.getMaxAirSupply());
            }
        }
        recoveryTicks = 0;
        return true;
    }

    /**
     * Debounced escape for a player embedded in solid terrain. Suffocation damage is
     * independent of water/air state, so this must run even while no movement is expected
     * (stationary mining was exactly the observed death). Escape uses only ordinary input:
     * walk out to an adjacent free cell when one exists, otherwise mine the embedding
     * block. Tool preservation deliberately loses here — a bare hand against stone is
     * still better than dying in a wall.
     */
    private boolean tickSuffocationEscape(Minecraft client, LocalPlayer player) {
        var action = suffocationReflex.tick(!player.isSpectator() && player.isInWall());
        switch (action) {
            case NONE -> {
                return false;
            }
            case ESCAPED -> {
                stopMovement(client);
                client.options.keyAttack.setDown(false);
                actions.add("safety:suffocation-escaped");
                diagnostics.add("suffocation-escape:escaped-at:" + player.blockPosition());
                suffocationEscapeTicks = 0;
                navigationReplanEpoch++;
                return true;
            }
            case BUDGET_EXPIRED -> {
                stopMovement(client);
                client.options.keyAttack.setDown(false);
                actions.add("safety:suffocation-escape-budget-expired");
                diagnostics.add("suffocation-escape:budget-expired-at:" + player.blockPosition());
                suffocationEscapeTicks = 0;
                navigationReplanEpoch++;
                return true;
            }
            case ESCAPE -> {
                // fall through to the active escape below
            }
        }
        client.options.keyUse.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyShift.setDown(false);
        if (suffocationEscapeTicks == 0) {
            actions.add("safety:escape-suffocation");
            diagnostics.add("suffocation-escape:in-wall-at:" + player.blockPosition());
        }
        suffocationEscapeTicks++;
        var exit = adjacentSafeEscape(client, player);
        if (exit != null) {
            client.options.keyAttack.setDown(false);
            lookAt(player, exit);
            setRecoveryMovement(client, exit.getY() > player.getY() + 0.35);
            return true;
        }
        stopMovement(client);
        var feet = player.blockPosition();
        var head = feet.above();
        var target = !client.level.getBlockState(head).getCollisionShape(client.level, head).isEmpty()
                ? head : feet;
        var state = client.level.getBlockState(target);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            client.options.keyAttack.setDown(false);
            return true;
        }
        lookAt(player, Vec3.atCenterOf(target));
        if (suffocationEscapeTicks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
        client.options.keyAttack.setDown(true);
        if (suffocationEscapeTicks % 20 == 1) {
            diagnostics.add("suffocation-escape:mine:" + state.getBlock().getName().getString()
                    + ":" + target);
        }
        return true;
    }

    private boolean escapeFireOrLava(Minecraft client, LocalPlayer player) {
        var route = NeoForgeSafePathPlanner.findHazardRetreat(client.level, player, player.blockPosition(), policy);
        if (route.size() >= 2) {
            lookAt(player, route.get(1));
            setRecoveryMovement(client, true);
            actions.add("safety:escape-fire-or-lava");
            if (recoveryTicks++ % 10 == 0) {
                diagnostics.add("hazard-recovery:route=" + route.getLast()
                        + ":edges=" + (route.size() - 1));
            }
        } else {
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keyJump.setDown(true);
            actions.add("safety:emergency-jump");
            if (recoveryTicks++ % 10 == 0) diagnostics.add("hazard-recovery:no-safe-route-observed");
        }
        return true;
    }

    long navigationReplanEpoch() {
        return navigationReplanEpoch;
    }

    long navigationAbandonEpoch() {
        return navigationAbandonEpoch;
    }

    private void updateMovementIntent(LocalPlayer player, boolean movementExpected, String intent) {
        if (!movementExpected || !Objects.equals(movementIntent, intent)) {
            movementIntent = movementExpected ? intent : null;
            recoveryClusterAnchor = null;
            latchedEscapeTargets.clear();
            obstructionClearUsedInCluster = false;
            rejectedEscapeTargets.clear();
            stalledMovementTicks = 0;
            lastPosition = player.position();
            return;
        }
        if (recoveryClusterAnchor != null
                && player.blockPosition().distManhattan(recoveryClusterAnchor) >= 3) {
            recoveryClusterAnchor = null;
            latchedEscapeTargets.clear();
            obstructionClearUsedInCluster = false;
            rejectedEscapeTargets.clear();
        }
    }

    /**
     * Intelligent execution owns prerequisite planning. Stop an unsafe low-level attack
     * before it turns into a long stone/dirt tunnel; bootstrap wood and foliage remain valid
     * bare-hand resources for survival plans.
     */
    private boolean preventBareHandToolMining(Minecraft client, LocalPlayer player) {
        if (!policy.toolPrerequisiteGuardEnabled() || !client.options.keyAttack.isDown()) return false;
        var activeThreat = findThreat(player);
        if (activeThreat != null && player.distanceToSqr(activeThreat) <= 20.25) {
            // Combat/defense input is allowed to reach a close hostile; it must not be
            // mistaken for a mining request merely because a block is behind the mob.
            return false;
        }
        var hit = player.pick(5.0F, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) return false;
        var state = client.level.getBlockState(blockHit.getBlockPos());
        if (state.isAir() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || bareHandSafeSoftObstacle(state)
                || (state.getFluidState().isEmpty() && player.getMainHandItem().isCorrectToolForDrops(state))) {
            return false;
        }
        client.options.keyAttack.setDown(false);
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        actions.add("intelligence:defer-tool-required-block");
        diagnostics.add("prerequisite-recovery:tool-required-block:" + state.getBlock().getName().getString());
        return true;
    }

    /** Normal-input recovery for a player wedged in terrain, foliage, or a visible block. */
    private boolean recoverObstructionOrStall(Minecraft client, LocalPlayer player,
                                              boolean movementExpected) {
        var position = player.position();
        if (!movementExpected) {
            lastPosition = position;
            stalledMovementTicks = 0;
            return false;
        }
        var horizontalDx = lastPosition == null ? 0.0 : position.x - lastPosition.x;
        var horizontalDz = lastPosition == null ? 0.0 : position.z - lastPosition.z;
        if (lastPosition != null && horizontalDx * horizontalDx + horizontalDz * horizontalDz < 0.0625) {
            stalledMovementTicks++;
        } else {
            // Use a short net-progress window so jump height and collision jitter cannot hide
            // a waypoint loop, while ordinary walking resets within a few ticks.
            stalledMovementTicks = 0;
            lastPosition = position;
        }

        var feet = player.blockPosition();
        if (recoveryClusterAnchor == null) recoveryClusterAnchor = feet.immutable();
        var feetBlocked = !client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty();
        var head = feet.above();
        var headBlocked = !client.level.getBlockState(head).getCollisionShape(client.level, head).isEmpty();
        var stalled = stalledMovementTicks > (policy.highSafety() ? 30 : 60);
        if (!feetBlocked && !headBlocked && !player.isInWall() && !stalled) return false;
        if (stalled && !feetBlocked && !headBlocked && !player.isInWall() && !player.onGround()) {
            return false;
        }

        var hit = player.pick(5.0, 1.0F, false);
        if (policy.obstructionMiningEnabled() && !obstructionClearUsedInCluster
                && hit instanceof BlockHitResult blockHit) {
            var block = blockHit.getBlockPos();
            var state = client.level.getBlockState(block);
            var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && canClearWithCurrentTool(player, state)
                    && snapshot.safeMiningSite(feet, block, player.getEyePosition(), Vec3.atCenterOf(block))
                    && hasBufferedRetreat(client, player, snapshot)) {
                lookAt(player, Vec3.atCenterOf(block));
                stopMovement(client);
                obstructionClearTarget = block.immutable();
                obstructionClearTicks = 0;
                obstructionClearUsedInCluster = true;
                actions.add("safety:clear-visible-obstruction");
                diagnostics.add("obstruction-recovery:attack:" + state.getBlock().getName().getString());
                stalledMovementTicks = 0;
                return true;
            }
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                diagnostics.add("obstruction-recovery:defer-hard-block-until-tool");
            }
        }

        rejectedEscapeTargets.clear();
        if (latchedEscapeTargets.size() >= 2) {
            abandonNavigationCluster(client, player, "repeated-local-cluster");
            return true;
        }
        obstructionEscapeTarget = adjacentSafeEscape(client, player);
        if (obstructionEscapeTarget == null) {
            abandonNavigationCluster(client, player, "no-adjacent-safe-cell");
            return true;
        }
        obstructionEscapeLastDistance = Double.POSITIVE_INFINITY;
        obstructionEscapeNoProgressTicks = 0;
        obstructionEscapeTicks = 0;
        latchedEscapeTargets.add(obstructionEscapeTarget.asLong());
        stopMovement(client);
        actions.add("safety:escape-obstruction");
        diagnostics.add("obstruction-recovery:adjacent-escape-to:" + obstructionEscapeTarget);
        stalledMovementTicks = 0;
        return true;
    }

    private boolean tickObstructionClear(Minecraft client, LocalPlayer player) {
        stopMovement(client);
        var state = client.level.getBlockState(obstructionClearTarget);
        if (state.isAir()) {
            client.options.keyAttack.setDown(false);
            diagnostics.add("obstruction-recovery:cleared:" + obstructionClearTarget);
            obstructionClearTarget = null;
            obstructionClearTicks = 0;
            stalledMovementTicks = 0;
            lastPosition = player.position();
            navigationReplanEpoch++;
            return true;
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        if (!snapshot.safeMiningSite(player.blockPosition(), obstructionClearTarget,
                player.getEyePosition(), Vec3.atCenterOf(obstructionClearTarget))
                || !hasBufferedRetreat(client, player, snapshot)) {
            client.options.keyAttack.setDown(false);
            diagnostics.add("obstruction-recovery:hazard-gate-abandon:" + obstructionClearTarget);
            obstructionClearTarget = null;
            obstructionClearTicks = 0;
            navigationReplanEpoch++;
            return true;
        }
        if (++obstructionClearTicks > obstructionClearLimit(state)
                || !canClearWithCurrentTool(player, state)) {
            client.options.keyAttack.setDown(false);
            var failed = obstructionClearTarget;
            obstructionClearTarget = null;
            obstructionClearTicks = 0;
            stalledMovementTicks = 0;
            lastPosition = player.position();
            navigationReplanEpoch++;
            actions.add("safety:abandon-unresolved-obstruction");
            diagnostics.add("obstruction-recovery:abandon-and-replan:" + failed);
            return true;
        }
        lookAt(player, Vec3.atCenterOf(obstructionClearTarget));
        var hit = player.pick(5.0F, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(obstructionClearTarget)) {
            if (obstructionClearTicks % 10 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
            actions.add("safety:clear-ray-targeted-obstruction");
        } else {
            client.options.keyAttack.setDown(false);
        }
        return true;
    }

    private boolean tickObstructionEscape(Minecraft client, LocalPlayer player) {
        client.options.keyAttack.setDown(false);
        if (++obstructionEscapeTicks > 100) {
            var failed = obstructionEscapeTarget;
            clearObstructionEscape(client);
            throw new IllegalStateException("bounded adjacent obstruction escape timed out at " + failed);
        }
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        if (!snapshot.walkable(obstructionEscapeTarget)) {
            rejectAndRetargetEscape(client, player);
            return true;
        }
        var distance = player.distanceToSqr(Vec3.atCenterOf(obstructionEscapeTarget));
        if (distance <= 1.0 && snapshot.walkable(player.blockPosition())) {
            diagnostics.add("obstruction-recovery:escaped-to:" + player.blockPosition());
            clearObstructionEscape(client);
            stalledMovementTicks = 0;
            lastPosition = player.position();
            navigationReplanEpoch++;
            return true;
        }
        if (distance < obstructionEscapeLastDistance - 0.02) obstructionEscapeNoProgressTicks = 0;
        else obstructionEscapeNoProgressTicks++;
        obstructionEscapeLastDistance = distance;
        if (obstructionEscapeNoProgressTicks > 30) {
            rejectAndRetargetEscape(client, player);
            return true;
        }
        lookAt(player, Vec3.atCenterOf(obstructionEscapeTarget));
        setRecoveryMovement(client, player.onGround()
                && obstructionEscapeTarget.getY() > player.getY() + 0.35);
        actions.add("safety:move-to-adjacent-escape");
        return true;
    }

    private void rejectAndRetargetEscape(Minecraft client, LocalPlayer player) {
        rejectedEscapeTargets.add(obstructionEscapeTarget.asLong());
        if (rejectedEscapeTargets.size() >= 3) {
            var failed = obstructionEscapeTarget;
            clearObstructionEscape(client);
            throw new IllegalStateException("no reachable adjacent high-safety escape after three attempts near " + failed);
        }
        if (latchedEscapeTargets.size() >= 2) {
            abandonNavigationCluster(client, player, "two-adjacent-cells-exhausted");
            return;
        }
        obstructionEscapeTarget = adjacentSafeEscape(client, player);
        if (obstructionEscapeTarget == null) {
            clearObstructionEscape(client);
            throw new IllegalStateException("no remaining adjacent high-safety obstruction escape");
        }
        obstructionEscapeLastDistance = Double.POSITIVE_INFINITY;
        obstructionEscapeNoProgressTicks = 0;
        latchedEscapeTargets.add(obstructionEscapeTarget.asLong());
        diagnostics.add("obstruction-recovery:retarget-adjacent-escape:" + obstructionEscapeTarget);
    }

    private BlockPos adjacentSafeEscape(Minecraft client, LocalPlayer player) {
        var feet = player.blockPosition();
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var facing = player.getDirection();
        var directions = List.of(facing.getOpposite(), facing.getClockWise(),
                facing.getCounterClockWise(), facing);
        for (var direction : directions) {
            for (var dy : new int[]{0, 1, -1}) {
                var candidate = feet.relative(direction).offset(0, dy, 0);
                if (rejectedEscapeTargets.contains(candidate.asLong())
                        || latchedEscapeTargets.contains(candidate.asLong())
                        || !snapshot.walkable(candidate)) continue;
                var moved = player.getBoundingBox().move(direction.getStepX(), dy, direction.getStepZ());
                if (client.level.noCollision(player, moved)) return candidate.immutable();
            }
        }
        return null;
    }

    private boolean hasBufferedRetreat(Minecraft client, LocalPlayer player,
                                       NeoForgeWorldSnapshot snapshot) {
        var feet = player.blockPosition();
        var arrival = new NeoForgeSafePathPlanner.ArrivalSpec(0.8, 0.8);
        for (var direction : Direction.Plane.HORIZONTAL) {
            for (var dy : new int[]{0, 1, -1}) {
                var candidate = feet.relative(direction).offset(0, dy, 0);
                if (!snapshot.bufferedWalkable(candidate)) continue;
                var path = NeoForgeSafePathPlanner.find(client.level, player, feet, candidate, policy, arrival);
                if (snapshot.safeMiningPath(path)) return true;
            }
        }
        return false;
    }

    private void clearObstructionEscape(Minecraft client) {
        stopMovement(client);
        client.options.keyAttack.setDown(false);
        obstructionEscapeTarget = null;
        rejectedEscapeTargets.clear();
        obstructionEscapeLastDistance = Double.POSITIVE_INFINITY;
        obstructionEscapeNoProgressTicks = 0;
        obstructionEscapeTicks = 0;
    }

    private void abandonNavigationCluster(Minecraft client, LocalPlayer player, String reason) {
        var anchor = recoveryClusterAnchor;
        clearObstructionEscape(client);
        obstructionClearTarget = null;
        obstructionClearTicks = 0;
        recoveryClusterAnchor = null;
        latchedEscapeTargets.clear();
        rejectedEscapeTargets.clear();
        obstructionClearUsedInCluster = false;
        stalledMovementTicks = 0;
        lastPosition = player.position();
        navigationReplanEpoch++;
        navigationAbandonEpoch++;
        actions.add("safety:abandon-local-navigation-cluster");
        diagnostics.add("obstruction-recovery:abandon-cluster:" + reason + ":" + anchor);
    }

    private static boolean canClearWithCurrentTool(LocalPlayer player, BlockState state) {
        var held = player.getMainHandItem();
        // Bootstrap resources and soft obstructions are safe to clear without mining hard terrain.
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || bareHandSafeSoftObstacle(state)) return true;
        return !held.isEmpty() && held.isCorrectToolForDrops(state);
    }

    private static int obstructionClearLimit(BlockState state) {
        // Bare-hand logs can take the full vanilla three-second break time. Leave margin for
        // the client/server block-state round trip without extending tool-required mining.
        if (state.is(BlockTags.LOGS)) return 120;
        if (state.is(BlockTags.LEAVES) || bareHandSafeSoftObstacle(state)) return 90;
        return 60;
    }

    private static boolean bareHandSafeSoftObstacle(BlockState state) {
        if (!state.getFluidState().isEmpty() || state.requiresCorrectToolForDrops()
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)) {
            return false;
        }
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD) || state.is(Blocks.MOSS_BLOCK);
    }

    private boolean unsafeForwardDrop(Minecraft client, LocalPlayer player) {
        if (!client.options.keyUp.isDown() && !client.options.keySprint.isDown()) return false;
        var feet = player.blockPosition();
        var ahead = feet.relative(player.getDirection());
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        if (snapshot.walkable(ahead)) return false;
        // A solid block at the player's feet is normally an obstacle or a one-block rise,
        // not a fall.  The old check treated every non-walkable block ahead as a drop and
        // consequently cancelled exploration whenever the player faced a sand/stone slope.
        // Let the actor jump onto a safe one-block rise and let obstruction recovery handle
        // a real wall; only inspect lower landing surfaces when the forward column is clear.
        if (snapshot.walkable(ahead.above())) return false;
        var aheadState = client.level.getBlockState(ahead);
        var aheadHeadState = client.level.getBlockState(ahead.above());
        if (!aheadState.getCollisionShape(client.level, ahead).isEmpty()
                || !aheadHeadState.getCollisionShape(client.level, ahead.above()).isEmpty()) {
            return false;
        }
        for (var dy = -1; dy >= -4; dy--) {
            var candidate = new BlockPos(ahead.getX(), feet.getY() + dy, ahead.getZ());
            if (snapshot.walkable(candidate)) return dy <= -2;
        }
        return true;
    }

    private LivingEntity findThreat(LocalPlayer player) {
        var last = player.getLastHurtByMob();
        if (last != null && last.isAlive() && player.distanceToSqr(last) <= 144.0) return last;
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12.0),
                        mob -> mob.isAlive() && mob.getTarget() == player)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private void defendOrRetreat(Minecraft client, LocalPlayer player, LivingEntity threat) {
        var close = player.distanceToSqr(threat) <= 12.25;
        var weak = player.getHealth() <= 4.0F;
        if (close && !weak && !player.getMainHandItem().isEmpty()) {
            lookAt(player, threat.position());
            client.options.keyAttack.setDown(true);
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(!policy.highSafety() && player.onGround());
            actions.add("safety:defend-active-threat");
            diagnostics.add("threat-response:attack:" + threat.getType());
            recoveryTicks = 8;
            return;
        }
        var dx = player.getX() - threat.getX();
        var dz = player.getZ() - threat.getZ();
        var length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        var retreat = new BlockPos((int) Math.floor(player.getX() + dx / length * 5),
                player.blockPosition().getY(), (int) Math.floor(player.getZ() + dz / length * 5));
        lookAt(player, retreat);
        client.options.keyAttack.setDown(false);
        setRecoveryMovement(client, !policy.highSafety());
        actions.add("safety:retreat-from-active-threat");
        diagnostics.add("threat-response:retreat:" + threat.getType());
        recoveryTicks = 10;
    }

    private void retreat(Minecraft client, LocalPlayer player, LivingEntity threat) {
        var dx = player.getX() - threat.getX();
        var dz = player.getZ() - threat.getZ();
        var length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        lookAt(player, new BlockPos((int) Math.floor(player.getX() + dx / length * 5),
                player.blockPosition().getY(), (int) Math.floor(player.getZ() + dz / length * 5)));
        client.options.keyAttack.setDown(false);
        setRecoveryMovement(client, !policy.highSafety());
        actions.add("safety:avoid-active-threat");
        diagnostics.add("threat-response:avoid:" + threat.getType());
        recoveryTicks = 10;
    }

    private static void setRecoveryMovement(Minecraft client, boolean jump) {
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(jump);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void lookAt(LocalPlayer player, BlockPos target) {
        lookAt(player, new net.minecraft.world.phys.Vec3(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static void lookAt(LocalPlayer player, net.minecraft.world.phys.Vec3 target) {
        var eye = player.getEyePosition();
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setYHeadRot(player.getYRot());
        player.setXRot((float) Math.max(-89.0, Math.min(89.0,
                -Math.toDegrees(Math.atan2(dy, horizontal)))));
    }
}
