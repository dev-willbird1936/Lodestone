// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/**
 * Extension point for path-edge cost terms beyond {@link NeoForgeSafePathPlanner}'s own geometry,
 * tier, and fall-damage costs: high-safety mob/lava proximity and fall-through risk, and
 * adaptive-intelligence mine-vs-place cost. Kept in its own file so filling in those real formulas
 * never needs another change to the A* loop itself - it only ever calls {@link #additionalCost}.
 */
final class NeoForgePathCostExtensions {
    // A mutation edge must never be as cheap as an ordinary walked step before the real mine/place
    // formulas exist below - otherwise the router could prefer punching through solid rock over a
    // slightly longer walk. Replaced by real per-block-type mine/place costs in a later pass.
    private static final double MUTATION_BASE_SURCHARGE = 20.0;

    private NeoForgePathCostExtensions() { }

    static double additionalCost(NeoForgeSafePathPlanner.EdgeContext ctx) {
        if (ctx.mutationKind() != NeoForgeSafePathPlanner.MutationKind.NONE) return MUTATION_BASE_SURCHARGE;
        return 0.0;
    }
}
