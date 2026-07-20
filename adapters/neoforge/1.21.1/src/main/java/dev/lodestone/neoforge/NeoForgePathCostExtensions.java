// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Extension point for path-edge cost terms beyond {@link NeoForgeSafePathPlanner}'s own geometry,
 * tier, and fall-damage costs: high-safety mob/lava proximity and fall-through risk, and
 * adaptive-intelligence mine-vs-place cost. Kept in its own file so filling in those real formulas
 * never needs another change to the A* loop itself - it only ever calls {@link #additionalCost}.
 */
final class NeoForgePathCostExtensions {
    // Tunable preference weights, not derived game-mechanics values - how strongly proximity to a
    // real threat or to lava should discourage an otherwise-cheaper edge at high safety.
    private static final double MOB_PROXIMITY_TARGETING_WEIGHT = 10.0;
    private static final double MOB_PROXIMITY_DEFAULT_WEIGHT = 5.0;
    private static final double LAVA_PROXIMITY_RADIUS = 6.0;
    private static final double LAVA_PROXIMITY_WEIGHT = 8.0;
    // A legitimate technique, but one that interacts with this adapter's reactive sneak-brake in a
    // way that could cause an unplanned fall-through - a soft preference, not a veto.
    private static final double SCAFFOLDING_DESCENT_SOFT_COST = 3.0;
    // A well-known emergent-physics approximation (not a stored game constant): a plausible average
    // number of game ticks to walk one block at normal speed, used only to normalize a real
    // break-time-in-ticks into the "1.0 == one walked block" units the rest of the router uses.
    private static final double WALK_TICKS_PER_BLOCK = 4.6;
    private static final double PLACE_BASE_COST = 1.0;
    private static final double PLACE_QUANTITY_PENALTY_WEIGHT = 8.0;

    private NeoForgePathCostExtensions() { }

    static double additionalCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        var cost = 0.0;
        if (ctx.policy().highSafety()) {
            cost += mobProximityCost(ctx) + lavaProximityCost(ctx) + fallThroughCost(ctx);
        }
        cost += switch (ctx.mutationKind()) {
            case NONE -> 0.0;
            case MINE -> mineCost(ctx);
            case PLACE -> placeCost(ctx);
        };
        return cost;
    }

    // --- 1. Mob-proximity cost (HIGH safety only) ---

    private static double mobProximityCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        var total = 0.0;
        for (var threat : ctx.snapshot().nearbyThreats()) {
            total += mobProximityCost(Math.sqrt(ctx.to().distSqr(threat.position())),
                    threat.followRange(), threat.targetingPlayer());
        }
        return total;
    }

    /**
     * Pure quadratic-falloff cost against one real threat's own follow range: zero at or beyond
     * that range, maximal at the mob's own position, weighted higher for a mob already targeting
     * the player.
     */
    static double mobProximityCost(double distance, double followRange, boolean targetingPlayer) {
        if (followRange <= 0.0 || distance >= followRange) return 0.0;
        var closeness = (followRange - distance) / followRange;
        return closeness * closeness * (targetingPlayer ? MOB_PROXIMITY_TARGETING_WEIGHT : MOB_PROXIMITY_DEFAULT_WEIGHT);
    }

    // --- 2. Lava-proximity cost (HIGH safety only) ---

    private static double lavaProximityCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        var total = 0.0;
        for (var lava : ctx.snapshot().nearbyLava()) {
            total += lavaProximityCost(Math.sqrt(ctx.to().distSqr(lava)));
        }
        return total;
    }

    /**
     * Same quadratic-falloff shape as {@link #mobProximityCost(double, double, boolean)}, but at a
     * wider fixed radius and a higher peak weight - lava's failure mode (near-certain death plus
     * continued burning) is qualitatively worse than the trivial hazards {@code bufferedWalkable}
     * already screens out entirely.
     */
    static double lavaProximityCost(double distance) {
        if (distance >= LAVA_PROXIMITY_RADIUS) return 0.0;
        var closeness = (LAVA_PROXIMITY_RADIUS - distance) / LAVA_PROXIMITY_RADIUS;
        return closeness * closeness * LAVA_PROXIMITY_WEIGHT;
    }

    // --- 3. Fall-through / state-dependent support (HIGH safety only) ---

    private static double fallThroughCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        var level = ctx.level();
        var support = ctx.to().below();
        if (!level.hasChunkAt(support)) return 0.0;
        var supportBlock = level.getBlockState(support).getBlock();
        var supportIsPowderSnow = supportBlock instanceof PowderSnowBlock;
        var canWalkOnPowderSnow = supportIsPowderSnow && PowderSnowBlock.canEntityWalkOnPowderSnow(ctx.player());
        var supportIsScaffolding = supportBlock instanceof ScaffoldingBlock;
        var descending = ctx.to().getY() < ctx.from().getY();
        return fallThroughCost(supportIsPowderSnow, canWalkOnPowderSnow, supportIsScaffolding, descending);
    }

    /**
     * Pure fall-through decision: powder snow relied on as support without the real vanilla check
     * passing is a correctness/reachability failure (hard exclusion), never merely a preference;
     * descending a scaffolding column is a legitimate technique carrying only residual risk (soft
     * cost). Scaffolding as a horizontal surface (not descending) is ordinary solid ground - no
     * penalty either way.
     */
    static double fallThroughCost(boolean supportIsPowderSnow, boolean canWalkOnPowderSnow,
                                   boolean supportIsScaffolding, boolean descending) {
        if (supportIsPowderSnow && !canWalkOnPowderSnow) return Double.POSITIVE_INFINITY;
        if (supportIsScaffolding && descending) return SCAFFOLDING_DESCENT_SOFT_COST;
        return 0.0;
    }

    // --- 4. Mine cost ---

    private static double mineCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        if (!ctx.policy().obstructionMiningEnabled()) return Double.POSITIVE_INFINITY;
        var total = 0.0;
        for (var target : ctx.mutationTargets()) {
            if (!NeoForgeGoalActionGuard.canBreakObstruction(ctx.level(), ctx.player(), target, ctx.policy())) {
                return Double.POSITIVE_INFINITY;
            }
            var state = ctx.level().getBlockState(target);
            total += mineCost(state.getDestroyProgress(ctx.player(), ctx.level(), target));
        }
        return total;
    }

    /**
     * Real vanilla break-time (already folding in tool efficiency, haste, underwater, and
     * off-ground penalties via {@code getDestroyProgress}) normalized to "1.0 == one walked block"
     * units. Deliberately does not search for a better tool - a bare-hand/wrong-tool break is
     * already priced slower by the real formula, so the search naturally prefers detouring over
     * slow mining without any separate tool-optimization logic.
     */
    static double mineCost(double progressPerTick) {
        if (progressPerTick <= 0.0) return Double.POSITIVE_INFINITY;
        return Math.ceil(1.0 / progressPerTick) / WALK_TICKS_PER_BLOCK;
    }

    // --- 5. Place cost ---

    private static double placeCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        if (!ctx.policy().obstructionPlacementEnabled()) return Double.POSITIVE_INFINITY;
        // ctx.mutationTargets() is the actual gap being filled (candidate.below() in
        // NeoForgeSafePathPlanner.mutationCandidate's terms), never ctx.to() (the candidate cell
        // itself, one cell too high). safePlacementSite(target) computes support=target.below()
        // internally and requires THAT to present a sturdy up-face to click against; calling it
        // with ctx.to() would make support equal the very gap this edge exists to fill, which is
        // never sturdy by construction (placeCandidateEligible already requires it non-solid) - so
        // every genuine PLACE candidate would be rejected here before ever reaching the inventory
        // check below, silently making every PLACE edge cost POSITIVE_INFINITY forever.
        if (ctx.mutationTargets().isEmpty()
                || !ctx.snapshot().safePlacementSite(ctx.mutationTargets().getFirst())) return Double.POSITIVE_INFINITY;
        var chosen = selectPlacementItem(ctx.player().getInventory());
        return chosen == null ? Double.POSITIVE_INFINITY : placeCost(chosen.count());
    }

    /**
     * Base cost plus a penalty inversely proportional to how many of the chosen item the player
     * owns - cheap to give up one of 64 dirt, expensive to give up the last one.
     */
    static double placeCost(int ownedCount) {
        if (ownedCount <= 0) return Double.POSITIVE_INFINITY;
        return PLACE_BASE_COST + PLACE_QUANTITY_PENALTY_WEIGHT / ownedCount;
    }

    /** One inventory-owned block candidate for a PLACE edge: the item itself and how many are owned. */
    record PlacementItem(Item item, int count) { }

    /**
     * Highest-owned-quantity qualifying full-cube {@link BlockItem} in the player's inventory, or
     * {@code null} if none qualify (the v1 "mutation:acquire-unavailable" scope cut - no
     * acquire-planning for a block the player doesn't already own). Its own reusable static method,
     * not inlined into the cost formula, since a later out-of-scope-for-this-pass executing actor
     * will want to re-derive the same choice at execution time, tolerating inventory drift between
     * planning and arrival.
     */
    static PlacementItem selectPlacementItem(Inventory inventory) {
        var counts = new LinkedHashMap<Item, Integer>();
        for (var stack : inventory.items) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            var shape = blockItem.getBlock().defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!Block.isShapeFullBlock(shape)) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return null;
        var items = List.copyOf(counts.keySet());
        var candidates = new ArrayList<PlacementCandidate>(items.size());
        for (var index = 0; index < items.size(); index++) {
            candidates.add(new PlacementCandidate(index, counts.get(items.get(index))));
        }
        var winner = selectHighestCount(candidates);
        return new PlacementItem(items.get(winner.index()), winner.count());
    }

    /**
     * One (identity, owned-quantity) candidate; {@code index} is an opaque back-reference the
     * caller resolves to a real item, keeping this decision itself free of any live game object.
     */
    record PlacementCandidate(int index, int count) { }

    /** Pure highest-quantity tie-break, isolated from inventory access so it's directly unit-testable. */
    static PlacementCandidate selectHighestCount(List<PlacementCandidate> candidates) {
        PlacementCandidate best = null;
        for (var candidate : candidates) {
            if (best == null || candidate.count() > best.count()) best = candidate;
        }
        return best;
    }
}
