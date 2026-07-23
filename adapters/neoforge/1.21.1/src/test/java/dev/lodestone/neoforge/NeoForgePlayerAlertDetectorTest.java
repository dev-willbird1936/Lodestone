// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgePlayerAlertDetectorTest {
    @Test
    void firstTickNeverEmitsBecauseThereIsNoPriorObservationToCompareAgainst() {
        var detector = new NeoForgePlayerAlertDetector();
        var events = detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of());
        assertTrue(events.isEmpty());
    }

    @Test
    void healthDropOfAtLeastOneEmitsHealthDropAlert() {
        var detector = new NeoForgePlayerAlertDetector();
        detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of());
        var events = detector.advance(2, true, 18.5F, 0, 64, 0, 6001, List.of());

        assertEquals(1, events.size());
        assertEquals("minecraft.player.alert.health-drop", events.get(0).name());
        assertEquals(20.0, events.get(0).payload().get("previous"));
        assertEquals(18.5, events.get(0).payload().get("current"));
        assertEquals("unknown", events.get(0).payload().get("cause"));
    }

    @Test
    void subOneHealthChangeDoesNotEmit() {
        var detector = new NeoForgePlayerAlertDetector();
        detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of());
        var events = detector.advance(2, true, 19.6F, 0, 64, 0, 6001, List.of());
        assertTrue(events.isEmpty());
    }

    @Test
    void aliveToDeadTransitionEmitsDeathWithPositionAndDayTimeInsteadOfHealthDrop() {
        var detector = new NeoForgePlayerAlertDetector();
        detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of());
        var events = detector.advance(2, false, 0.0F, 10, 62, -5, 6500, List.of());

        assertEquals(1, events.size());
        assertEquals("minecraft.player.alert.death", events.get(0).name());
        assertEquals(position("x", 10.0, "y", 62.0, "z", -5.0), events.get(0).payload().get("position"));
        assertEquals(6500L, events.get(0).payload().get("dayTime"));
    }

    @Test
    void deadToAliveTransitionEmitsRespawnWithPositionOnly() {
        var detector = new NeoForgePlayerAlertDetector();
        detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of());
        detector.advance(2, false, 0.0F, 10, 62, -5, 6500, List.of());
        var events = detector.advance(3, true, 20.0F, 1, 65, 2, 6600, List.of());

        assertEquals(1, events.size());
        assertEquals("minecraft.player.alert.respawn", events.get(0).name());
        assertEquals(position("x", 1.0, "y", 65.0, "z", 2.0), events.get(0).payload().get("position"));
        assertEquals(1, events.get(0).payload().size());
    }

    @Test
    void dayTimeCrossingIntoNightWindowEmitsNightOnceAndDawnOnceOnExit() {
        var detector = new NeoForgePlayerAlertDetector();
        detector.advance(1, true, 20.0F, 0, 64, 0, 12999, List.of());
        var nightEvents = detector.advance(2, true, 20.0F, 0, 64, 0, 13000, List.of());
        assertEquals(1, nightEvents.size());
        assertEquals("minecraft.player.alert.night", nightEvents.get(0).name());
        assertEquals(13000L, nightEvents.get(0).payload().get("dayTime"));

        // Still night: no repeat event.
        var stillNight = detector.advance(3, true, 20.0F, 0, 64, 0, 20000, List.of());
        assertTrue(stillNight.isEmpty());

        var dawnEvents = detector.advance(4, true, 20.0F, 0, 64, 0, 23000, List.of());
        assertEquals(1, dawnEvents.size());
        assertEquals("minecraft.player.alert.dawn", dawnEvents.get(0).name());
    }

    @Test
    void nightWindowWrapsAcrossMultipleDayTimeCycles() {
        assertTrue(NeoForgePlayerAlertDetector.isNight(13000));
        assertTrue(NeoForgePlayerAlertDetector.isNight(22999));
        assertTrue(NeoForgePlayerAlertDetector.isNight(24000 + 13500));
        assertTrue(!NeoForgePlayerAlertDetector.isNight(23000));
        assertTrue(!NeoForgePlayerAlertDetector.isNight(0));
        assertTrue(!NeoForgePlayerAlertDetector.isNight(12999));
    }

    @Test
    void hostileNearThrottlesToOnceEveryHundredTicksPerEntity() {
        var detector = new NeoForgePlayerAlertDetector();
        var zombie = new NeoForgePlayerAlertDetector.HostileObservation("minecraft:zombie", 42, 8.0);

        var first = detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of(zombie));
        assertEquals(1, first.size());
        assertEquals("minecraft.player.alert.hostile-near", first.get(0).name());
        assertEquals("minecraft:zombie", first.get(0).payload().get("type"));
        assertEquals(42, first.get(0).payload().get("entityId"));
        assertEquals(8.0, first.get(0).payload().get("distance"));

        var stillClose = detector.advance(50, true, 20.0F, 0, 64, 0, 6049, List.of(zombie));
        assertTrue(stillClose.isEmpty(), "same entity within the 100-tick throttle window must not re-notify");

        var afterWindow = detector.advance(101, true, 20.0F, 0, 64, 0, 6100, List.of(zombie));
        assertEquals(1, afterWindow.size(), "throttle window elapsed, entity should notify again");
    }

    @Test
    void hostileBeyondRadiusIsIgnoredEvenIfCallerIncludesIt() {
        var detector = new NeoForgePlayerAlertDetector();
        var farZombie = new NeoForgePlayerAlertDetector.HostileObservation("minecraft:zombie", 1,
                NeoForgePlayerAlertDetector.HOSTILE_RADIUS + 0.01);
        var events = detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of(farZombie));
        assertTrue(events.isEmpty());
    }

    @Test
    void distinctHostileEntitiesNotifyIndependently() {
        var detector = new NeoForgePlayerAlertDetector();
        var zombie = new NeoForgePlayerAlertDetector.HostileObservation("minecraft:zombie", 1, 5.0);
        var skeleton = new NeoForgePlayerAlertDetector.HostileObservation("minecraft:skeleton", 2, 6.0);
        var events = detector.advance(1, true, 20.0F, 0, 64, 0, 6000, List.of(zombie, skeleton));
        assertEquals(2, events.size());
    }

    private static java.util.Map<String, Object> position(String kx, double x, String ky, double y, String kz, double z) {
        return java.util.Map.of(kx, x, ky, y, kz, z);
    }
}
