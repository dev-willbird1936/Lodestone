// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Thin real-Minecraft shim over {@link NeoForgePlayerAlertDetector}: reads live client/world/player
 * state once per tick and republishes any detected transitions as {@code minecraft.player.alert.*}
 * events. Ticked before every native goal actor (see {@code NeoForgeClientController#onClientTick})
 * so alerts reflect this tick's world state even while a goal actor is mid-mutation.
 */
final class NeoForgePlayerAlerts {
    private final NeoForgePlayerAlertDetector detector = new NeoForgePlayerAlertDetector();
    private long tick;
    private volatile Map<String, Object> lastDeathPosition;

    /**
     * Advances the detector from the live client and forwards any detected alerts to
     * {@code publisher}. A no-op whenever the world or player is unavailable (e.g. on the title
     * screen), matching every other tickXxx() method's own guard.
     */
    void tick(Minecraft client, BiConsumer<String, Map<String, Object>> publisher) {
        tick++;
        var player = client.player;
        var level = client.level;
        if (player == null || level == null) return;

        var radius = NeoForgePlayerAlertDetector.HOSTILE_RADIUS;
        var aabb = new AABB(player.getX() - radius, player.getY() - radius, player.getZ() - radius,
                player.getX() + radius, player.getY() + radius, player.getZ() + radius);
        var hostiles = new ArrayList<NeoForgePlayerAlertDetector.HostileObservation>();
        for (var monster : level.getEntitiesOfClass(Monster.class, aabb, Monster::isAlive)) {
            hostiles.add(new NeoForgePlayerAlertDetector.HostileObservation(
                    BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()).toString(),
                    monster.getId(), Math.sqrt(monster.distanceToSqr(player))));
        }

        var events = detector.advance(tick, player.isAlive(), player.getHealth(),
                player.getX(), player.getY(), player.getZ(), level.getDayTime(), hostiles);
        for (var event : events) {
            if ("minecraft.player.alert.death".equals(event.name())) {
                @SuppressWarnings("unchecked")
                var position = (Map<String, Object>) event.payload().get("position");
                lastDeathPosition = position;
            }
            publisher.accept(event.name(), event.payload());
        }
    }

    /** The most recent death position observed, or {@code null} if the player has not died this
     * session - a future respawn-recover competency can read this to walk back to lost drops. */
    Map<String, Object> lastDeathPosition() {
        return lastDeathPosition;
    }
}
