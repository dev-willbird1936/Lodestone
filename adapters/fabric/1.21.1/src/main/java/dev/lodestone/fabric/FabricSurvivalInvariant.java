// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

/**
 * Pure survival-state decisions shared by goal and supervisor safety checks.
 *
 * <p>Ported from {@code NeoForgeSurvivalInvariant} for the {@code minecraft.goal.navigation.safe-waypoint}
 * capability; behavior is bit-for-bit identical to the NeoForge 1.21.1 original.
 */
final class FabricSurvivalInvariant {
    static final int AIR_RESERVE_TICKS = 100;
    static final int AIR_TICKS_PER_RETREAT_EDGE = 20;

    private FabricSurvivalInvariant() { }

    static Action decide(boolean dead, float health, boolean deathScreen,
                         boolean inWater, int airSupply, int maxAirSupply) {
        if (dead || health <= 0.0F || deathScreen) return Action.PLAYER_DIED;
        if (inWater || airSupply < maxAirSupply) return Action.WATER_RETREAT;
        return Action.NORMAL;
    }

    static int maxWaterRetreatEdges(int airSupply) {
        return Math.max(0, (airSupply - AIR_RESERVE_TICKS) / AIR_TICKS_PER_RETREAT_EDGE);
    }

    static boolean normalRouteOriginAllowed(boolean walkable, boolean bufferedWalkable,
                                            boolean highSafety) {
        return walkable && (!highSafety || bufferedWalkable);
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
