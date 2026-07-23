// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, Minecraft-class-free state machine backing {@link NeoForgePlayerAlerts}. Advances once per
 * client tick from plain observed values and returns the bounded set of alert events (if any) that
 * transitioned this tick, so the transition logic itself is unit-testable without a live client -
 * see {@code NeoForgePlayerAlertDetectorTest}.
 */
final class NeoForgePlayerAlertDetector {
    private static final double HEALTH_DROP_THRESHOLD = 1.0;
    private static final long NIGHT_START_DAY_TIME = 13000;
    private static final long NIGHT_END_DAY_TIME = 23000;
    private static final long HOSTILE_THROTTLE_TICKS = 100;
    static final double HOSTILE_RADIUS = 12.0;

    private final Map<Integer, Long> hostileLastNotifiedTick = new HashMap<>();
    private Float previousHealth;
    private Boolean previousAlive;
    private Boolean previousNight;

    /** One nearby hostile candidate observed this tick, already filtered to alive Monster entities. */
    record HostileObservation(String type, int entityId, double distance) {
    }

    record AlertEvent(String name, Map<String, Object> payload) {
    }

    /**
     * Advances the detector by one tick and returns every alert that transitioned. {@code tick}
     * must be monotonically increasing (used only to throttle repeat hostile-near notifications
     * per entity); {@code hostiles} need only contain candidates already within whatever radius the
     * caller wants alerted (this method re-filters to {@link #HOSTILE_RADIUS} regardless).
     */
    List<AlertEvent> advance(long tick, boolean alive, float health, double x, double y, double z,
                              long dayTime, List<HostileObservation> hostiles) {
        var events = new ArrayList<AlertEvent>();

        if (Boolean.TRUE.equals(previousAlive) && !alive) {
            events.add(new AlertEvent("minecraft.player.alert.death",
                    Map.of("position", position(x, y, z), "dayTime", dayTime)));
        } else if (Boolean.FALSE.equals(previousAlive) && alive) {
            events.add(new AlertEvent("minecraft.player.alert.respawn", Map.of("position", position(x, y, z))));
        } else if (alive && previousHealth != null && health <= previousHealth - HEALTH_DROP_THRESHOLD) {
            events.add(new AlertEvent("minecraft.player.alert.health-drop",
                    Map.of("previous", (double) previousHealth, "current", (double) health, "cause", "unknown")));
        }

        var night = isNight(dayTime);
        if (Boolean.FALSE.equals(previousNight) && night) {
            events.add(new AlertEvent("minecraft.player.alert.night", Map.of("dayTime", dayTime)));
        } else if (Boolean.TRUE.equals(previousNight) && !night) {
            events.add(new AlertEvent("minecraft.player.alert.dawn", Map.of("dayTime", dayTime)));
        }

        hostileLastNotifiedTick.values().removeIf(last -> tick - last >= HOSTILE_THROTTLE_TICKS);
        for (var hostile : hostiles) {
            if (hostile.distance() > HOSTILE_RADIUS) continue;
            if (hostileLastNotifiedTick.containsKey(hostile.entityId())) continue;
            hostileLastNotifiedTick.put(hostile.entityId(), tick);
            events.add(new AlertEvent("minecraft.player.alert.hostile-near",
                    Map.of("type", hostile.type(), "entityId", hostile.entityId(), "distance", hostile.distance())));
        }

        previousHealth = alive ? health : null;
        previousAlive = alive;
        previousNight = night;
        return List.copyOf(events);
    }

    /** Package-private and pure for direct testing. */
    static boolean isNight(long dayTime) {
        var timeOfDay = ((dayTime % 24000) + 24000) % 24000;
        return timeOfDay >= NIGHT_START_DAY_TIME && timeOfDay < NIGHT_END_DAY_TIME;
    }

    private static Map<String, Object> position(double x, double y, double z) {
        return Map.of("x", x, "y", y, "z", z);
    }
}
