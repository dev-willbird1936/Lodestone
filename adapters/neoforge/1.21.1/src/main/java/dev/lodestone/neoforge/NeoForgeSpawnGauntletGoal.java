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
    // Paired with the floor above to bound the accepted waypoint to a genuine "~32 blocks away"
    // annulus rather than letting it drift arbitrarily far outward; 48 mirrors the old per-direction
    // box's own outer reach (candidate distance 32 + WAYPOINT_SEARCH_RADIUS 16). See
    // computeWaypoint's doc for why the annulus itself (not a per-direction box) is searched now.
    private static final double MAX_WAYPOINT_HORIZONTAL_DISTANCE = 48.0;
    // The safe path planner's own DY_OFFSETS already enumerate neighbor candidates up to 3 blocks
    // below the current position (NeoForgeSafePathPlanner.DY_OFFSETS); the only thing that ever
    // rejected them was this actor's own descent cap, threaded through as
    // NeoForgeGoalPolicy.maxDescentBlocks() (default 1, unchanged for every other actor). A 2-3
    // block drop is real vanilla fall damage math away from ever being unsafe: the default real
    // safeFallDistance is 3.0, so estimatedFallDamage already returns exactly zero for any drop this
    // shallow (NeoForgeSafePathPlannerTest's existing fall-damage tests cover that formula). Relaxed
    // only for this actor's own policy instance (see the constructor), not the shared default, so no
    // other actor's edge set changes at all.
    private static final int MAX_DESCENT_BLOCKS = 3;
    // Off by default: zero behavior or latency change for real scoring runs. Flip on with
    // -Dlodestone.spawnGauntlet.reachabilityDiagnostic=true to fold a
    // NeoForgeSafePathPlanner.floodFillReachable summary (under both the default 1-block descent cap
    // and this actor's own widened cap) into the TARGET_UNREACHABLE failure message, so offline
    // investigation of a specific failing seed can read the real reachable-distance ceiling straight
    // out of that seed's own captured failure message - no new MCP capability, input field, or
    // schema change needed, and no live 90+ second active window required (the annulus search
    // already fails fast, before ever reaching Stage.ACTIVE, for every seed this is meant to explain).
    private static final boolean REACHABILITY_DIAGNOSTIC_ENABLED =
            Boolean.getBoolean("lodestone.spawnGauntlet.reachabilityDiagnostic");
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
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input()).withMaxDescentBlocks(MAX_DESCENT_BLOCKS);
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
     * no bounded route") applies here too - the nearest walkable-looking surface can still sit
     * across a ravine, cliff, or body of water the safe path planner's one-block-descent movement
     * model can never actually cross.
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
     * constant's own doc). The candidate-generation helper (now {@link #nearbySafeSurfacesInAnnulus})
     * enforces the floor on the candidate set itself, before {@code findToAny} ever runs, so every
     * candidate reachable from this method is provably far enough from the observed spawn tile
     * regardless of which one gets accepted - this is not a preference that a smarter search order
     * could still accidentally defeat.
     *
     * <p>Restricting the search to due east was always this implementation's own arbitrary choice,
     * not a spec requirement - the task's own authoritative wording (the header of {@code
     * verification/seeds/b1-spawn-gauntlet.txt}, which explicitly quotes "the task spec") says only
     * "reach a waypoint 32 blocks away horizontally," and {@code GoalTaskCatalog}'s declarative
     * assertions for this task never reference a direction at all. An earlier iteration replaced due
     * east with a union of the 4 cardinal directions on that same basis, but a second-opinion review
     * caught a real geometric bug in how that union was built: each direction's box was only {@link
     * #WAYPOINT_SEARCH_RADIUS} wide off-axis at a {@link #WAYPOINT_OFFSET}-block reach, covering
     * roughly 60 degrees of compass around that direction's own axis - four of those cover roughly
     * 240 of the 360 degrees around the observed spawn tile at the target distance, leaving the
     * remaining ~120 degrees (split across the four gaps between adjacent cardinal boxes) never a
     * candidate at all. A diagonal escape route (a ravine floor, a hillside bench running northeast,
     * or similar) sitting in one of those gaps would produce exactly the same "hundreds of candidates
     * searched in every direction, zero reachable" signature this method's diagnostics report for a
     * genuinely fully-encircled spawn - the two cases were indistinguishable from that signature
     * alone, which is why the gap went unnoticed through the 4/20 -&gt; 5/20 rescoring that followed
     * the cardinal-direction change.
     *
     * <p>This now drops the direction-box framing entirely and enumerates every walkable surface in
     * the full 360-degree horizontal annulus between {@link #MIN_WAYPOINT_HORIZONTAL_DISTANCE} and
     * {@link #MAX_WAYPOINT_HORIZONTAL_DISTANCE} of the observed spawn tile (see {@link
     * #nearbySafeSurfacesInAnnulus}), handing that one combined set to a single {@code findToAny}
     * call exactly as before - the same "one shared A* expansion beats restarting per attempt or per
     * direction" principle, just with no angular gaps left for a real escape route to hide in.
     */
    private BlockPos computeWaypoint(Minecraft client, BlockPos origin) {
        var player = client.player;
        var snapshot = NeoForgeWorldSnapshot.capture(client.level, policy, player);
        var pathfindingOrigin = walkableOriginNear(snapshot, origin);
        var surfaces = nearbySafeSurfacesInAnnulus(snapshot, origin);
        var route = NeoForgeSafePathPlanner.findToAny(client.level, player, pathfindingOrigin, surfaces, policy,
                new NeoForgeSafePathPlanner.ArrivalSpec(3.0, 5.0));
        if (!route.isEmpty()) {
            var accepted = route.getLast();
            diagnostics.add("waypoint-selection:accepted:" + accepted + ":candidates=" + surfaces.size());
            return accepted;
        }
        var diagnosticSummary = REACHABILITY_DIAGNOSTIC_ENABLED
                ? " | " + reachabilityDiagnosticSummary(client, player, pathfindingOrigin, origin) : "";
        throw new IllegalStateException("TARGET_UNREACHABLE: cause=target:unreachable; "
                + "no reachable safe surface observed in the " + MIN_WAYPOINT_HORIZONTAL_DISTANCE + "-"
                + MAX_WAYPOINT_HORIZONTAL_DISTANCE + "-block annulus around observed spawn " + origin
                + " across all " + surfaces.size() + " candidates searched together from origin "
                + pathfindingOrigin + diagnosticSummary + telemetrySuffix());
    }

    /**
     * Offline-investigation only (see {@link #REACHABILITY_DIAGNOSTIC_ENABLED}): flood-fills the
     * real reachable area from the pathfinding origin under both the shared default 1-block descent
     * cap and this actor's own widened cap, and reports each one's actual maximum reachable
     * horizontal distance and whether the {@link #withinWaypointAnnulus} band is reachable at all
     * under it - directly answering "how far could this spawn actually reach" for a specific failing
     * seed without needing to guess from a full live 20-seed re-run first.
     */
    private String reachabilityDiagnosticSummary(Minecraft client, LocalPlayer player, BlockPos pathfindingOrigin,
                                                  BlockPos origin) {
        var defaultCapPolicy = policy.withMaxDescentBlocks(NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS);
        var reachableDefaultCap = NeoForgeSafePathPlanner.floodFillReachable(client.level, player,
                pathfindingOrigin, defaultCapPolicy, 45_000);
        var reachableWidenedCap = NeoForgeSafePathPlanner.floodFillReachable(client.level, player,
                pathfindingOrigin, policy, 45_000);
        return "reachabilityDiagnostic{descentCap=" + NeoForgeGoalPolicy.DEFAULT_MAX_DESCENT_BLOCKS
                + ":visited=" + reachableDefaultCap.size()
                + ",maxHorizontalDistance=" + maxHorizontalDistanceFrom(origin, reachableDefaultCap)
                + ",annulusReachable=" + anyWithinWaypointAnnulus(origin, reachableDefaultCap)
                + ";descentCap=" + policy.maxDescentBlocks()
                + ":visited=" + reachableWidenedCap.size()
                + ",maxHorizontalDistance=" + maxHorizontalDistanceFrom(origin, reachableWidenedCap)
                + ",annulusReachable=" + anyWithinWaypointAnnulus(origin, reachableWidenedCap) + "}";
    }

    /** Pure, directly testable: the farthest horizontal distance from origin among a set of
     * positions, 0.0 for an empty set (an unreachable origin's own flood-fill). */
    static double maxHorizontalDistanceFrom(BlockPos origin, List<BlockPos> positions) {
        var max = 0.0;
        for (var position : positions) max = Math.max(max, horizontalDistance(origin, position));
        return max;
    }

    /** Pure, directly testable: whether any position in the set falls within {@link
     * #withinWaypointAnnulus}'s band relative to origin. */
    static boolean anyWithinWaypointAnnulus(BlockPos origin, List<BlockPos> positions) {
        for (var position : positions) if (withinWaypointAnnulus(horizontalDistance(origin, position))) return true;
        return false;
    }

    /**
     * Every walkable surface anywhere in the full 360-degree horizontal annulus around the observed
     * spawn tile (see {@link #withinWaypointAnnulus}), nearest-to-{@link #WAYPOINT_OFFSET}-blocks-
     * away first - see {@link #computeWaypoint}'s doc for why the annulus, not a per-direction box,
     * is searched now. The sort order is cosmetic only: {@link NeoForgeSafePathPlanner#findToAny}
     * accepts the first candidate its own graph expansion reaches regardless of input order (this
     * was already true of the old per-direction lists), so it does not bias which candidate actually
     * gets accepted.
     */
    private static List<BlockPos> nearbySafeSurfacesInAnnulus(NeoForgeWorldSnapshot snapshot, BlockPos origin) {
        var surfaces = new ArrayList<BlockPos>();
        var radius = (int) Math.ceil(MAX_WAYPOINT_HORIZONTAL_DISTANCE);
        for (var x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (var z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                var columnDistance = horizontalDistance(origin, new BlockPos(x, origin.getY(), z));
                if (!withinWaypointAnnulus(columnDistance)) continue;
                for (var y = origin.getY() - WAYPOINT_SEARCH_RADIUS; y <= origin.getY() + WAYPOINT_SEARCH_RADIUS; y++) {
                    var position = new BlockPos(x, y, z);
                    if (snapshot.walkable(position)) surfaces.add(position.immutable());
                }
            }
        }
        surfaces.sort(Comparator.comparingDouble(
                position -> Math.abs(horizontalDistance(origin, position) - WAYPOINT_OFFSET)));
        return surfaces;
    }

    /**
     * Pure, directly testable: whether a horizontal distance from the observed spawn tile falls
     * within the accepted waypoint annulus. Kept as its own predicate, isolated from world access,
     * the same way {@code NeoForgeSafePathPlanner}'s {@code cornerClear}/{@code mineCandidateEligible}
     * are - so the min/max boundary behavior is testable without a live level.
     */
    static boolean withinWaypointAnnulus(double horizontalDistanceFromSpawn) {
        return horizontalDistanceFromSpawn >= MIN_WAYPOINT_HORIZONTAL_DISTANCE
                && horizontalDistanceFromSpawn <= MAX_WAYPOINT_HORIZONTAL_DISTANCE;
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
