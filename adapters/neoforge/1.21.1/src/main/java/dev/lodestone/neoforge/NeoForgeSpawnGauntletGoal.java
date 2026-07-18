// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B1 "Spawn Gauntlet" benchmark actor: survive a fixed opening window with the shared safety
 * supervisor active, then reach a waypoint the actor discovers 32 blocks east of its own observed
 * spawn position.
 *
 * <p>Self-contained, like {@link NeoForgeNavigationGoal}: it owns its own duration tracking and
 * waypoint discovery rather than the goal engine orchestrating it step-by-step, and it is not a
 * wrapper around {@code NeoForgeNavigationGoal}. Unlike that actor, the granular per-tick death
 * check here runs unconditionally, before any screen-gating guard, so a mid-run death is never
 * silently hidden behind a {@code DeathScreen} - that ordering mistake is deliberately not
 * repeated here.</p>
 */
final class NeoForgeSpawnGauntletGoal {
    // A freshly created world's chunks up to ~48 blocks from spawn are frequently still
    // generating/loading a mere 2 seconds after the player enters (verified live: the safe path
    // planner treats an unloaded chunk as impassable, so a too-short settle window made every
    // waypoint search fail as if surrounded by a wall). 10 seconds gives chunk generation enough
    // headroom without meaningfully eating into the benchmark's own 90-120 second active window,
    // since none of this counts toward MIN_ACTIVE_TICKS/MAX_ACTIVE_TICKS.
    private static final int WORLD_WAIT_TICKS = 200;
    private static final int WAYPOINT_EAST_OFFSET = 32;
    private static final int WAYPOINT_SEARCH_RADIUS = 16;
    // Live testing against real committed seeds showed the box at this radius routinely contains
    // 900-1600 walkable surfaces, but the nearest 20 to the exact east point are all clustered
    // within a couple of blocks of one another - so when a single local obstacle (a cliff, ravine,
    // or body of water) blocks that immediate cluster, every one of the first 20 probes fails
    // identically even though the wider box still holds plenty of reachable ground further from the
    // exact point. A much larger probe budget lets the search reach into that wider pool instead of
    // giving up while still sitting on top of the same blocked cluster.
    private static final int MAX_WAYPOINT_PROBES = 100;
    static final int MIN_ACTIVE_TICKS = 1_800;
    static final int MAX_ACTIVE_TICKS = 2_400;

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final NeoForgeGoalPolicy policy;
    private final List<String> actions = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final NeoForgeGoalSupervisor supervisor;

    private Stage stage = Stage.WAIT_WORLD;
    private int worldWaitTicks;
    private boolean survival;
    private boolean freshWorld;
    private long worldGameTimeAtStart;
    private BlockPos spawnOrigin;
    private BlockPos waypoint;
    private int activeTicks;
    private boolean reachedWaypoint;
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private int replans;
    private int pathNodesVisited;
    private int stuckTicks;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private boolean directFallback;

    NeoForgeSpawnGauntletGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
        this.supervisor = new NeoForgeGoalSupervisor(policy, actions, diagnostics);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            // Unconditional, before any screen gating below - this is exactly the ordering
            // NeoForgeNavigationGoal gets wrong (its only aliveness check lives inside complete(),
            // reached only once the target is hit, and its tick() no-ops once a DeathScreen opens).
            failFastOnDeath(client);
            if (stage == Stage.WAIT_WORLD) {
                waitForFreshWorld(client);
                return;
            }
            activeTicks++;
            tickActive(client);
            var outcome = decide(activeTicks, reachedWaypoint);
            if (outcome == Outcome.SUCCEEDED) {
                complete(client);
            } else if (outcome == Outcome.TARGET_UNREACHABLE) {
                throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                        + "spawn gauntlet reached its " + MAX_ACTIVE_TICKS
                        + "-tick hard cap without reaching the east waypoint " + waypoint
                        + telemetrySuffix());
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    /**
     * Copied verbatim from {@code NeoForgeSurvivalTreeGoal.failFastOnDeath}: a dead player cannot
     * act, so fail immediately with the real vanilla damage-type cause rather than grinding the
     * remaining tick budget against a death screen.
     */
    private void failFastOnDeath(Minecraft client) {
        var player = client.player;
        if (player == null || (!player.isDeadOrDying() && player.getHealth() > 0.0F)) return;
        var source = player.getLastDamageSource();
        var cause = source == null ? "unknown" : source.type().msgId();
        throw new IllegalStateException("PLAYER_DIED: cause=died:" + cause
                + "; player died during " + stage + " at " + player.blockPosition()
                + telemetrySuffix());
    }

    private void waitForFreshWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (worldWaitTicks++ < WORLD_WAIT_TICKS) return;
        var player = client.player;
        survival = client.gameMode.getPlayerMode() == GameType.SURVIVAL;
        worldGameTimeAtStart = client.level.getGameTime();
        freshWorld = survival && worldGameTimeAtStart <= 6_000;
        if (!survival) throw new IllegalStateException("goal world is not survival");
        if (policy.allowCommands()) {
            throw new IllegalStateException("survival spawn-gauntlet workflow refuses allowCommands=true");
        }
        if (!freshWorld) {
            throw new IllegalStateException("goal requires a fresh world; gameTime=" + worldGameTimeAtStart);
        }
        // Origin and waypoint are discovered here, from the position actually observed once the
        // world has settled - never baked into the goal text the way navigation.safe-waypoint's
        // target is.
        spawnOrigin = player.blockPosition().immutable();
        waypoint = computeWaypoint(client, spawnOrigin);
        stage = Stage.ACTIVE;
    }

    /**
     * Geometric proximity alone is not enough to pick a usable waypoint: the same reachability gap
     * {@code NeoForgeSurvivalTreeGoal.electTrees()} solves for candidate trees ("probe rejected -
     * no bounded route") applies here too - the nearest walkable surface to the east candidate can
     * sit across a ravine, cliff, or body of water that the safe path planner's one-block-descent
     * movement model can never actually cross. A surface only becomes the waypoint once the exact
     * same unbounded search {@link #replan} later relies on during real play actually finds a route
     * to it from the observed spawn position, tried nearest-first. A reduced probe budget was tried
     * first and rejected: even a legitimately reachable target several dozen blocks away routinely
     * needs more than a few thousand visited nodes once safe-navigation's diagonal expansion is
     * active, so anything less than the real search budget produced false unreachable verdicts.
     */
    private BlockPos computeWaypoint(Minecraft client, BlockPos origin) {
        var player = client.player;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var candidate = eastWaypointCandidate(origin, WAYPOINT_EAST_OFFSET);
        var pathfindingOrigin = walkableOriginNear(snapshot, origin);
        var surfaces = nearbySafeSurfacesByDistance(snapshot, candidate);
        var probed = 0;
        for (var surface : surfaces) {
            if (probed >= MAX_WAYPOINT_PROBES) break;
            probed++;
            var route = NeoForgeSafePathPlanner.find(client.level, player, pathfindingOrigin, surface, policy);
            if (!route.isEmpty()) {
                diagnostics.add("waypoint-selection:accepted:" + surface + ":probes=" + probed
                        + ":candidates=" + surfaces.size());
                return surface;
            }
        }
        throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                + "no reachable safe surface observed within " + WAYPOINT_SEARCH_RADIUS
                + " blocks of the east waypoint candidate " + candidate + " after probing " + probed
                + "/" + surfaces.size() + " candidates from origin " + pathfindingOrigin
                + telemetrySuffix());
    }

    /** Every walkable surface within the search cube, nearest-to-the-candidate first. */
    private static List<BlockPos> nearbySafeSurfacesByDistance(NeoForgeWorldSnapshot snapshot, BlockPos candidate) {
        var surfaces = new ArrayList<BlockPos>();
        for (var x = candidate.getX() - WAYPOINT_SEARCH_RADIUS; x <= candidate.getX() + WAYPOINT_SEARCH_RADIUS; x++) {
            for (var z = candidate.getZ() - WAYPOINT_SEARCH_RADIUS; z <= candidate.getZ() + WAYPOINT_SEARCH_RADIUS; z++) {
                for (var y = candidate.getY() - WAYPOINT_SEARCH_RADIUS; y <= candidate.getY() + WAYPOINT_SEARCH_RADIUS; y++) {
                    var position = new BlockPos(x, y, z);
                    if (snapshot.walkable(position)) surfaces.add(position.immutable());
                }
            }
        }
        surfaces.sort(Comparator.comparingDouble(position -> position.distSqr(candidate)));
        return surfaces;
    }

    /**
     * The player's own {@code blockPosition()} is not guaranteed to satisfy {@code walkable()} -
     * standing on a snow layer, slab, or other partial-height block can put the floored feet cell
     * on the block itself rather than the empty air above it. Search a small vertical band the same
     * way {@code NeoForgeSurvivalTreeGoal}'s raw-BFS fallback already does for exactly this case,
     * rather than letting every path search silently fail from an origin the planner refuses.
     */
    private static BlockPos walkableOriginNear(NeoForgeWorldSnapshot snapshot, BlockPos raw) {
        for (var dy = 2; dy >= -3; dy--) {
            var candidate = raw.offset(0, dy, 0);
            if (snapshot.walkable(candidate)) return candidate.immutable();
        }
        return raw;
    }

    /** Pure, directly testable: the fixed east-offset waypoint candidate before surface search. */
    static BlockPos eastWaypointCandidate(BlockPos origin, int offset) {
        return origin.offset(offset, 0, 0);
    }

    /**
     * Pure success/failure decision, directly testable without a live level. Both the minimum
     * 1800-tick (90-second) active-control duration and a reached waypoint must hold for success.
     * Hitting the 2400-tick hard cap without reaching the waypoint is a distinct failure kind
     * (target-unreachable), never conflated with a death: a death is its own unconditional,
     * immediate throw elsewhere in {@code tick()} and never reaches this decision at all.
     */
    static Outcome decide(int activeTicks, boolean reachedWaypoint) {
        if (activeTicks >= MIN_ACTIVE_TICKS && reachedWaypoint) return Outcome.SUCCEEDED;
        if (activeTicks >= MAX_ACTIVE_TICKS) return Outcome.TARGET_UNREACHABLE;
        return Outcome.CONTINUE;
    }

    private void tickActive(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null) return;
        if (client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("goal world is not survival during the active window");
        }
        if (supervisor.tick(client)) return;
        if (client.screen != null) return;
        if (reachedWaypoint) {
            stopMovement(client);
            return;
        }
        if (reached(client.player)) {
            reachedWaypoint = true;
            stopMovement(client);
            return;
        }
        driveTowardWaypoint(client);
    }

    private void driveTowardWaypoint(Minecraft client) {
        var player = client.player;
        if (!policy.smartNavigation()) {
            directFallback = true;
            lookAt(player, waypoint);
            setMovement(client, true, false, waypoint.getY() > player.blockPosition().getY() && player.onGround());
            actions.add("move:raw-direct-to-waypoint");
            return;
        }

        if (path.isEmpty() || pathIndex >= path.size() || activeTicks % 20 == 0) {
            replan(client);
        }
        if (path.isEmpty()) {
            // No safe route from the current stance right now (e.g. a momentarily unstable
            // mid-jump origin) is never treated as a fatal failure on its own tick - the
            // MAX_ACTIVE_TICKS hard cap in decide() already turns a genuinely unreachable waypoint
            // into a TARGET_UNREACHABLE failure, without risking a spurious one-tick false failure.
            stopMovement(client);
            return;
        }

        while (pathIndex < path.size() && closeTo(player, path.get(pathIndex), 0.8)) {
            pathNodesVisited++;
            pathIndex++;
            stuckTicks = 0;
            lastDistance = Double.POSITIVE_INFINITY;
        }
        if (reached(player)) {
            reachedWaypoint = true;
            stopMovement(client);
            return;
        }
        if (pathIndex >= path.size()) {
            replan(client);
            return;
        }

        var next = path.get(pathIndex);
        var distance = horizontalDistance(player, next);
        if (distance >= lastDistance - 0.01) stuckTicks++; else stuckTicks = 0;
        lastDistance = distance;
        if (stuckTicks > 45) {
            diagnostics.add("navigation-stuck:replanning-at-" + player.blockPosition());
            replan(client);
            return;
        }
        lookAt(player, next);
        setMovement(client, true, false, next.getY() > player.blockPosition().getY() && player.onGround());
        actions.add("move:key.forward-held");
    }

    private void replan(Minecraft client) {
        var player = client.player;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var origin = walkableOriginNear(snapshot, player.blockPosition());
        path = NeoForgeSafePathPlanner.find(client.level, player, origin, waypoint, policy);
        pathIndex = path.size() > 1 ? 1 : 0;
        replans++;
        stuckTicks = 0;
        lastDistance = Double.POSITIVE_INFINITY;
        diagnostics.add("replan:" + replans + ":nodes=" + path.size() + ":from="
                + origin + ":to=" + waypoint);
        actions.add(path.isEmpty() ? "observe:no-safe-path" : "observe:loaded-chunk-safe-path");
    }

    private boolean reached(LocalPlayer player) {
        return horizontalDistance(player, waypoint) <= 2.7
                && Math.abs(player.blockPosition().getY() - waypoint.getY()) <= 2;
    }

    private static boolean closeTo(LocalPlayer player, BlockPos position, double radius) {
        return horizontalDistance(player, position) <= radius
                && Math.abs(player.blockPosition().getY() - position.getY()) <= 1;
    }

    private static double horizontalDistance(LocalPlayer player, BlockPos position) {
        var dx = player.getX() - position.getX() - 0.5;
        var dz = player.getZ() - position.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void complete(Minecraft client) {
        var player = client.player;
        var alive = player != null && player.isAlive() && player.getHealth() > 0.0F;
        if (!alive) {
            // Should be unreachable - failFastOnDeath already throws unconditionally every tick -
            // but never report a false SUCCEEDED if death detection was somehow bypassed.
            throw new IllegalStateException("PLAYER_DIED: cause=died:unknown; "
                    + "player died before spawn-gauntlet terminal readback" + telemetrySuffix());
        }
        releaseInput(client);
        result.complete(output(client));
    }

    private Map<String, Object> output(Minecraft client) {
        var player = client.player;
        return Map.ofEntries(
                Map.entry("playerAlive", true),
                Map.entry("reachedWaypoint", reachedWaypoint),
                Map.entry("survivedFullDuration", activeTicks >= MIN_ACTIVE_TICKS),
                Map.entry("elapsedTicks", activeTicks),
                Map.entry("spawnPosition", position(spawnOrigin)),
                Map.entry("waypoint", position(waypoint)),
                Map.entry("finalPosition", position(player.blockPosition())),
                Map.entry("healthAtEnd", player.getHealth()),
                Map.entry("survival", survival),
                Map.entry("freshWorld", freshWorld),
                Map.entry("worldGameTimeAtStart", worldGameTimeAtStart),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("observation", policy.observation()),
                Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("replans", replans),
                Map.entry("plannedPathLength", path.size()),
                Map.entry("pathNodesVisited", pathNodesVisited),
                Map.entry("directFallback", directFallback),
                Map.entry("safetyInterventions", List.copyOf(diagnostics)),
                Map.entry("safetyInterventionCount", diagnostics.size()),
                Map.entry("inputActions", List.copyOf(actions)),
                Map.entry("commandsUsed", false),
                Map.entry("directMutationUsed", false),
                Map.entry("allowCommands", policy.allowCommands()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()));
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    /** Bounded machine-parseable run counters appended to every terminal failure message. */
    private String telemetrySuffix() {
        return " | telemetry{stage=" + stage
                + ",activeTicks=" + activeTicks
                + ",replans=" + replans
                + ",reachedWaypoint=" + reachedWaypoint
                + ",safetyInterventions=" + diagnostics.size()
                + "}";
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

    private static void setMovement(Minecraft client, boolean forward, boolean sprint, boolean jump) {
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(false);
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void releaseInput(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }

    private enum Stage {
        WAIT_WORLD,
        ACTIVE
    }

    enum Outcome {
        CONTINUE,
        SUCCEEDED,
        TARGET_UNREACHABLE
    }
}
