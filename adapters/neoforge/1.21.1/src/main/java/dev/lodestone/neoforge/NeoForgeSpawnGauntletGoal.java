// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B1 "Spawn Gauntlet" benchmark actor: survive a fixed opening window with the shared safety
 * supervisor active, then reach a waypoint the actor discovers 32 blocks away, horizontally, from
 * its own observed spawn position - see {@link #computeWaypoint}'s doc for why that is no longer
 * "32 blocks east specifically" despite this class's name and its git history.
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
    private static final int WAYPOINT_OFFSET = 32;
    private static final int WAYPOINT_SEARCH_RADIUS = 16;
    // findToAny (see computeWaypoint's doc) accepts the first candidate its shared-graph search
    // actually walks onto, which - unlike the old nearest-to-the-exact-point probing order - has no
    // preference at all for candidates close to the real 32-block target over candidates close to
    // the player's own observed spawn tile. Live-verified without this floor: seed 1 (spawn
    // BlockPos{x=102,y=64,z=180}) accepted BlockPos{x=118,y=64,z=187}, only ~17.5 blocks away -
    // almost half the intended offset, because that was simply the nearest reachable member of the
    // search box to the search's own origin, not to the east candidate point. This floor rejects any
    // candidate the search box would otherwise offer that sits closer to the observed spawn tile
    // than this, so whichever reachable candidate findToAny actually accepts is provably far enough
    // away regardless of which one it happens to be - it does not merely bias the search back toward
    // preferring the exact point.
    private static final double MIN_WAYPOINT_HORIZONTAL_DISTANCE = 28.0;
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
                        + "-tick hard cap without reaching the waypoint " + waypoint
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
     * movement model can never actually cross.
     *
     * <p>An earlier version of this method probed candidates one at a time, nearest-to-the-exact-
     * point first, each via its own independent {@link NeoForgeSafePathPlanner#find} search, and
     * gave up after a fixed number of probes (raised from 20 to 100 once already, per the removed
     * {@code MAX_WAYPOINT_PROBES} comment). Official scoring against 20 real seeds showed that
     * escalation still wasn't structural enough: 18/20 seeds failed, and every one of them had
     * exhausted exactly the 100-probe cap while the wider search box still held 600-2000+ more
     * candidates it never even tried (see {@code verification/evidence/
     * b1-official-scoring-20260718-retry-20260718T030312Z}). The nearest 100 surfaces to the exact
     * east point are still all clustered within a few blocks of one another, so a single local
     * obstacle fails every one of them identically no matter how high the probe count goes -
     * proximity-first probing with any fixed budget was the wrong shape of fix.
     *
     * <p>This now runs one shared-graph search across every candidate surface in the box at once,
     * via {@link NeoForgeSafePathPlanner#findToAny} (the same reachability-first primitive
     * {@code NeoForgeNetherGoal.findResourceApproachWaypoint} already uses for candidate mining
     * vantages). A single A* expansion naturally explores outward from the observed spawn position
     * and accepts the first candidate it actually walks onto, so one blocked local cluster no
     * longer stops the search from reaching a reachable surface elsewhere in the same box - and
     * unlike the old per-candidate loop, the search graph is only ever built and explored once
     * instead of being restarted from scratch for up to 100 candidates in a row.
     *
     * <p>That shared search has no notion of "close to the exact east point" at all, though - it
     * simply accepts whichever candidate its own expansion from the observed spawn tile reaches
     * first, which {@link #MIN_WAYPOINT_HORIZONTAL_DISTANCE} exists to keep from quietly becoming a
     * short walk instead of a genuine ~32-block traversal (live-verified regression: see that
     * constant's own doc). {@link #nearbySafeSurfacesByDistance} enforces the floor on the candidate
     * set itself, before {@code findToAny} ever runs, so every candidate reachable from this method
     * is provably far enough from the observed spawn tile regardless of which one gets accepted -
     * this is not a preference that a smarter search order could still accidentally defeat.
     *
     * <p>Restricting the box to due east was always this implementation's own arbitrary choice, not
     * a spec requirement - the task's own authoritative wording (the header of {@code
     * verification/seeds/b1-spawn-gauntlet.txt}, which explicitly quotes "the task spec") says only
     * "reach a waypoint 32 blocks away horizontally," and {@code GoalTaskCatalog}'s declarative
     * assertions for this task never reference a direction at all. Restricting the search to one
     * direction meant that whenever the terrain due east of a given spawn was entirely unreachable
     * (a cliff, ravine, or body of water spanning the whole east-side box, not merely a locally
     * blocked cluster within it) the goal failed even when perfectly reachable ground sat 32 blocks
     * away in another direction. Official 20-seed rescoring after the {@link
     * #MIN_WAYPOINT_HORIZONTAL_DISTANCE} fix landed only 4/20 successes, with all 16 failures still
     * reporting zero reachable candidates across the entire (correctly, completely searched)
     * east-side box - confirming this as a distinct, geometric failure mode the shared-graph search
     * itself cannot solve no matter how completely it searches one direction's box.
     *
     * <p>This now unions the candidate surfaces from all 4 cardinal directions (east, west, north,
     * south - see {@link #waypointCandidate}) into one combined, deduplicated set and hands that
     * whole set to a single {@code findToAny} call, exactly as before: one shared A* expansion still
     * only ever explores the graph once, and naturally gravitates toward whichever direction
     * actually has a reachable surface without this method needing to encode any priority order or
     * try directions sequentially (which would reintroduce the same restart-per-attempt inefficiency
     * the original {@code findToAny} redesign eliminated). Diagonal (northeast/northwest/southeast/
     * southwest) directions are deliberately not included: {@code Direction.Plane.HORIZONTAL}'s 4
     * cardinals already cover the full compass at the same 32-block radius, and doubling to 8
     * directions would roughly double the merged candidate set's size for coverage that mostly
     * duplicates what a 16-block search radius around each cardinal point already reaches into the
     * diagonal quadrants.
     */
    private BlockPos computeWaypoint(Minecraft client, BlockPos origin) {
        var player = client.player;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var pathfindingOrigin = walkableOriginNear(snapshot, origin);
        var merged = new LinkedHashMap<Long, BlockPos>();
        var perDirectionCounts = new StringBuilder();
        for (var direction : Direction.Plane.HORIZONTAL) {
            var candidate = waypointCandidate(origin, WAYPOINT_OFFSET, direction);
            var directionSurfaces = nearbySafeSurfacesByDistance(snapshot, origin, candidate);
            for (var surface : directionSurfaces) merged.putIfAbsent(surface.asLong(), surface);
            if (!perDirectionCounts.isEmpty()) perDirectionCounts.append(',');
            perDirectionCounts.append(direction.getName()).append('=').append(directionSurfaces.size());
        }
        var surfaces = List.copyOf(merged.values());
        var route = NeoForgeSafePathPlanner.findToAny(client.level, player, pathfindingOrigin, surfaces, policy,
                new NeoForgeSafePathPlanner.ArrivalSpec(3.0, 5.0));
        if (!route.isEmpty()) {
            var accepted = route.getLast();
            diagnostics.add("waypoint-selection:accepted:" + accepted + ":candidates=" + surfaces.size()
                    + ":byDirection=" + perDirectionCounts);
            return accepted;
        }
        throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                + "no reachable safe surface observed within " + WAYPOINT_SEARCH_RADIUS
                + " blocks of any of the 4 cardinal waypoint candidates around observed spawn " + origin
                + " across all " + surfaces.size() + " candidates (" + perDirectionCounts
                + ") searched together from origin " + pathfindingOrigin
                + telemetrySuffix());
    }

    /**
     * Every walkable surface within the search cube around one direction's candidate, nearest-to-
     * that-candidate first, excluding anything closer to the observed spawn tile than {@link
     * #MIN_WAYPOINT_HORIZONTAL_DISTANCE} - see that constant's doc for why the distance floor is
     * necessary now that {@link #computeWaypoint} hands the merged, multi-direction list to {@link
     * NeoForgeSafePathPlanner#findToAny} instead of probing it in this sorted order itself. {@code
     * origin} is always the observed spawn tile regardless of which direction's box is being built,
     * so the floor means the same thing - "far enough from spawn" - for every direction alike.
     */
    private static List<BlockPos> nearbySafeSurfacesByDistance(NeoForgeWorldSnapshot snapshot, BlockPos origin,
                                                                BlockPos candidate) {
        var surfaces = new ArrayList<BlockPos>();
        for (var x = candidate.getX() - WAYPOINT_SEARCH_RADIUS; x <= candidate.getX() + WAYPOINT_SEARCH_RADIUS; x++) {
            for (var z = candidate.getZ() - WAYPOINT_SEARCH_RADIUS; z <= candidate.getZ() + WAYPOINT_SEARCH_RADIUS; z++) {
                for (var y = candidate.getY() - WAYPOINT_SEARCH_RADIUS; y <= candidate.getY() + WAYPOINT_SEARCH_RADIUS; y++) {
                    var position = new BlockPos(x, y, z);
                    if (!snapshot.walkable(position)) continue;
                    if (horizontalDistance(origin, position) < MIN_WAYPOINT_HORIZONTAL_DISTANCE) continue;
                    surfaces.add(position.immutable());
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

    /**
     * Pure, directly testable: the fixed-offset waypoint candidate in a given cardinal direction,
     * before surface search. {@link #computeWaypoint} calls this once per direction in {@code
     * Direction.Plane.HORIZONTAL} and unions the resulting candidate boxes; see that method's doc
     * for why no single direction (east included) is treated as a spec requirement.
     */
    static BlockPos waypointCandidate(BlockPos origin, int offset, Direction direction) {
        return origin.relative(direction, offset);
    }

    /**
     * Pure, directly testable: the fixed east-offset waypoint candidate before surface search - kept
     * as its own named case of {@link #waypointCandidate} because it predates the multi-direction
     * search and is still exercised directly by this class's own unit tests.
     */
    static BlockPos eastWaypointCandidate(BlockPos origin, int offset) {
        return waypointCandidate(origin, offset, Direction.EAST);
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

    /**
     * Block-to-block variant of {@link #horizontalDistance(LocalPlayer, BlockPos)}, for candidate
     * filtering against the observed spawn tile rather than the live player position.
     */
    private static double horizontalDistance(BlockPos from, BlockPos to) {
        var dx = to.getX() - from.getX();
        var dz = to.getZ() - from.getZ();
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
