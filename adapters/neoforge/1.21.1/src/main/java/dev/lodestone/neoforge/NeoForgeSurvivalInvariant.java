// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

/** Pure survival-state decisions shared by goal and supervisor safety checks. */
final class NeoForgeSurvivalInvariant {
    static final int AIR_RESERVE_TICKS = 100;
    static final int AIR_TICKS_PER_RETREAT_EDGE = 20;

    private NeoForgeSurvivalInvariant() { }

    static Action decide(boolean dead, float health, boolean deathScreen,
                         boolean inWater, int airSupply, int maxAirSupply) {
        if (dead || health <= 0.0F || deathScreen) return Action.PLAYER_DIED;
        if (inWater || airSupply < maxAirSupply) return Action.WATER_RETREAT;
        return Action.NORMAL;
    }

    static int maxWaterRetreatEdges(int airSupply) {
        return Math.max(0, (airSupply - AIR_RESERVE_TICKS) / AIR_TICKS_PER_RETREAT_EDGE);
    }

    /**
     * Whether an ordinary (non-recovery) route search may begin from a given origin. Fed {@code
     * NeoForgeWorldSnapshot.originStandable}/{@code hazardBuffer} rather than {@code
     * walkable}/{@code bufferedWalkable} - the origin is wherever the player already stands, so it
     * is never gated on what its own support classifies as (solid ground, leaves, tall grass, ...);
     * see {@code originStandable}'s own doc for the live-caught bug this fixed. Every step the
     * search actually proposes beyond the origin still keeps the full, unrelaxed contract.
     */
    static boolean normalRouteOriginAllowed(boolean originStandable, boolean originHazardFree,
                                            boolean highSafety) {
        return originStandable && (!highSafety || originHazardFree);
    }

    static boolean verticalRecoveryOriginAllowed(boolean walkable, boolean hazard,
                                                  boolean hazardAbove) {
        return walkable && !hazard && !hazardAbove;
    }

    static boolean allowHorizontalWaterInput(boolean verifiedRoute, boolean horizontalEdge) {
        return verifiedRoute && horizontalEdge;
    }

    enum Action {
        NORMAL,
        WATER_RETREAT,
        PLAYER_DIED
    }
}
