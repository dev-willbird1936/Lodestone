// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeWorldAnalysisTest {
    @Test
    void mapsOnlyExactWorldAnalysisIds() {
        assertEquals(NeoForgeWorldAnalysis.Operation.HEIGHTMAP,
                NeoForgeWorldAnalysis.operation("minecraft.world.heightmap.read"));
        assertEquals(NeoForgeWorldAnalysis.Operation.LIGHT,
                NeoForgeWorldAnalysis.operation("minecraft.world.light.analyze"));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeWorldAnalysis.operation("minecraft.world.region.scan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heightmapPreservesZMajorOrderAndHonestColumnStates() {
        var request = NeoForgeWorldAnalysis.heightmapRequest(Map.of(
                "x", 10, "z", 20, "sizeX", 2, "sizeZ", 2));
        var sampled = new ArrayList<String>();
        var output = NeoForgeWorldAnalysis.heightmap(request, "minecraft:overworld", (x, z) -> {
            sampled.add(x + ":" + z);
            return switch (sampled.size()) {
                case 1 -> new NeoForgeWorldAnalysis.HeightColumn(true, false, 70, "minecraft:stone");
                case 2 -> new NeoForgeWorldAnalysis.HeightColumn(false, false, 0, "");
                case 3 -> new NeoForgeWorldAnalysis.HeightColumn(true, true, 0, "");
                default -> new NeoForgeWorldAnalysis.HeightColumn(true, false, -3, "minecraft:dirt");
            };
        }, () -> { });

        assertEquals(List.of("10:20", "11:20", "10:21", "11:21"), sampled);
        assertEquals(4, output.get("columnCount"));
        assertEquals(3, output.get("loadedColumns"));
        assertEquals(1, output.get("unloadedColumns"));
        var columns = (List<Map<String, Object>>) output.get("columns");
        assertEquals(Map.of("x", 10, "z", 20, "loaded", true, "empty", false,
                "height", 70, "surfaceBlock", "minecraft:stone"), columns.get(0));
        assertEquals(Map.of("x", 11, "z", 20, "loaded", false, "empty", false), columns.get(1));
        assertEquals(Map.of("x", 10, "z", 21, "loaded", true, "empty", true), columns.get(2));
        var stats = (Map<String, Object>) output.get("stats");
        assertEquals(Map.of("hasHeightData", true, "minHeight", -3,
                "maxHeight", 70, "heightRange", 73), stats);
    }

    @Test
    @SuppressWarnings("unchecked")
    void heightmapOmitsSurfaceBlockWhenDisabled() {
        var request = NeoForgeWorldAnalysis.heightmapRequest(Map.of(
                "x", 0, "z", 0, "sizeX", 1, "sizeZ", 1,
                "includeSurfaceBlocks", false));
        var output = NeoForgeWorldAnalysis.heightmap(request, "minecraft:overworld",
                (x, z) -> new NeoForgeWorldAnalysis.HeightColumn(true, false, 4, "minecraft:stone"),
                () -> { });

        var column = ((List<Map<String, Object>>) output.get("columns")).getFirst();
        assertFalse(column.containsKey("surfaceBlock"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lightAnalysisUsesYZXOrderAndReportsExactCaps() {
        var request = NeoForgeWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", 0, "z", 0,
                "sizeX", 3, "sizeY", 3, "sizeZ", 3,
                "resolution", 2, "darkSpotLimit", 1, "lightSourceLimit", 1));
        var cells = List.of(
                new NeoForgeWorldAnalysis.LightCell(false, false, "", 0, 0, 0),
                new NeoForgeWorldAnalysis.LightCell(true, true, "minecraft:stone", 0, 0, 0),
                new NeoForgeWorldAnalysis.LightCell(true, true, "minecraft:glowstone", 15, 0, 0),
                new NeoForgeWorldAnalysis.LightCell(true, false, "minecraft:torch", 14, 14, 0),
                new NeoForgeWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 0, 0),
                new NeoForgeWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 8, 4),
                new NeoForgeWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 12, 2),
                new NeoForgeWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 7, 3));
        var sampled = new ArrayList<String>();
        var output = NeoForgeWorldAnalysis.lightAnalysis(request, "minecraft:overworld", -64, 320,
                (x, y, z) -> {
                    sampled.add(x + ":" + y + ":" + z);
                    return cells.get(sampled.size() - 1);
                }, () -> { });

        assertEquals(List.of("0:0:0", "2:0:0", "0:0:2", "2:0:2",
                "0:2:0", "2:2:0", "0:2:2", "2:2:2"), sampled);
        assertEquals(8, output.get("candidateSamples"));
        assertEquals(5, output.get("analyzedSamples"));
        assertEquals(2, output.get("solidSamples"));
        assertEquals(1, output.get("unloadedSamples"));
        assertEquals(8.2, output.get("averageCombinedLight"));
        assertEquals(2, output.get("darkSpotCount"));
        assertEquals(true, output.get("darkSpotsTruncated"));
        assertEquals(2, output.get("lightSourceCount"));
        assertEquals(true, output.get("lightSourcesTruncated"));
        assertEquals("high", output.get("mobSpawnRisk"));

        var darkSpots = (List<Map<String, Object>>) output.get("darkSpots");
        assertEquals(1, darkSpots.size());
        assertEquals(Map.of("x", 0, "y", 2, "z", 0), darkSpots.getFirst().get("position"));
        assertEquals("high", darkSpots.getFirst().get("risk"));
        var sources = (List<Map<String, Object>>) output.get("lightSources");
        assertEquals("minecraft:glowstone", sources.getFirst().get("block"));
        assertEquals(15, sources.getFirst().get("emission"));
        var distribution = (Map<String, Map<String, Object>>) output.get("distribution");
        assertEquals(Map.of("count", 2, "percentage", 40.0), distribution.get("dark"));
        assertEquals(Map.of("count", 1, "percentage", 20.0), distribution.get("dim"));
        assertEquals(Map.of("count", 2, "percentage", 40.0), distribution.get("wellLit"));
    }

    @Test
    void lightAnalysisRejectsRegionOutsideBuildHeight() {
        var request = NeoForgeWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", -65, "z", 0,
                "sizeX", 1, "sizeY", 1, "sizeZ", 1));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> NeoForgeWorldAnalysis.lightAnalysis(request, "minecraft:overworld", -64, 320,
                        (x, y, z) -> new NeoForgeWorldAnalysis.LightCell(true, false,
                                "minecraft:air", 0, 0, 0), () -> { }));
        assertTrue(failure.getMessage().contains("build height"));
    }

    @Test
    void heightStatsUseObservedHeights() {
        var stats = new NeoForgeWorldAnalysis.HeightStatsAccumulator();
        stats.accept(72);
        stats.accept(-12);
        stats.accept(31);

        var snapshot = stats.snapshot();
        assertTrue(snapshot.hasHeightData());
        assertEquals(-12, snapshot.minHeight());
        assertEquals(72, snapshot.maxHeight());
        assertEquals(84, snapshot.heightRange());
    }

    @Test
    void emptyHeightStatsUseExplicitZeroSentinel() {
        var snapshot = new NeoForgeWorldAnalysis.HeightStatsAccumulator().snapshot();

        assertEquals(new NeoForgeWorldAnalysis.HeightStats(false, 0, 0, 0), snapshot);
    }

    @Test
    void summarizesCombinedLightHistogramAndBands() {
        var samples = new NeoForgeWorldAnalysis.LightAccumulator();
        samples.accept(0, 0);
        samples.accept(7, 3);
        samples.accept(8, 4);
        samples.accept(11, 9);
        samples.accept(12, 5);
        samples.accept(15, 15);

        var summary = samples.snapshot();
        assertEquals(6, summary.analyzedSamples());
        assertEquals(53.0 / 6.0, summary.averageCombinedLight());
        assertEquals(2, summary.darkCount());
        assertEquals(2, summary.dimCount());
        assertEquals(2, summary.wellLitCount());
        assertEquals(33.3, summary.darkPercentage());
        assertEquals("high", summary.mobSpawnRisk());
        assertEquals(16, summary.histogram().size());
        assertEquals(1, summary.histogram().get(15));
    }

    @Test
    void mobSpawnRiskUsesSpecifiedRatioThresholds() {
        assertEquals("none", NeoForgeWorldAnalysis.mobSpawnRisk(0, 10));
        assertEquals("low", NeoForgeWorldAnalysis.mobSpawnRisk(9, 100));
        assertEquals("medium", NeoForgeWorldAnalysis.mobSpawnRisk(10, 100));
        assertEquals("high", NeoForgeWorldAnalysis.mobSpawnRisk(30, 100));
        assertEquals("none", NeoForgeWorldAnalysis.mobSpawnRisk(0, 0));
    }

    @Test
    void suggestionsGroupNegativeCoordinatesAndClassifyHeight() {
        var darkSpots = List.of(
                new NeoForgeWorldAnalysis.DarkSpot(-1, 0, -1, 0, 0, 0),
                new NeoForgeWorldAnalysis.DarkSpot(-13, 1, -13, 0, 0, 0),
                new NeoForgeWorldAnalysis.DarkSpot(-15, 9, -15, 0, 0, 0),
                new NeoForgeWorldAnalysis.DarkSpot(14, 5, 14, 0, 0, 0));

        var suggestions = NeoForgeWorldAnalysis.suggestions(darkSpots, 0, 9);
        assertEquals(3, suggestions.size());
        assertEquals("lantern", suggestions.get(0).suggestedSource());
        assertTrue(suggestions.get(0).reason().startsWith("Floor"));
        assertEquals("lantern", suggestions.get(1).suggestedSource());
        assertTrue(suggestions.get(1).reason().startsWith("Ceiling"));
        assertEquals("torch", suggestions.get(2).suggestedSource());
    }

    @Test
    void parsesHeightmapBoundsAndDefaultsSurfaceBlocks() {
        var request = NeoForgeWorldAnalysis.heightmapRequest(Map.of(
                "x", -32, "z", 48, "sizeX", 256, "sizeZ", 1));

        assertEquals(-32, request.x());
        assertEquals(48, request.z());
        assertEquals(256, request.sizeX());
        assertEquals(1, request.sizeZ());
        assertEquals(256, request.columnCount());
        assertTrue(request.includeSurfaceBlocks());
    }

    @Test
    void rejectsHeightmapAxisOver256() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NeoForgeWorldAnalysis.heightmapRequest(Map.of(
                        "x", 0, "z", 0, "sizeX", 257, "sizeZ", 1)));

        assertTrue(failure.getMessage().contains("sizeX"));
    }

    @Test
    void rejectsHeightmapCoordinateOverflow() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NeoForgeWorldAnalysis.heightmapRequest(Map.of(
                        "x", Integer.MAX_VALUE, "z", 0, "sizeX", 2, "sizeZ", 1)));

        assertTrue(failure.getMessage().contains("x"));
    }

    @Test
    void parsesLightDefaultsAndCeilingSampleCount() {
        var request = NeoForgeWorldAnalysis.lightRequest(Map.of(
                "x", 1, "y", -64, "z", 2,
                "sizeX", 7, "sizeY", 5, "sizeZ", 9,
                "resolution", 2));

        assertEquals(2, request.resolution());
        assertEquals(4 * 3 * 5, request.candidateSamples());
        assertEquals(100, request.darkSpotLimit());
        assertEquals(100, request.lightSourceLimit());
    }

    @Test
    void rejectsLightSampleBudgetOver1048576() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NeoForgeWorldAnalysis.lightRequest(Map.of(
                        "x", 0, "y", -64, "z", 0,
                        "sizeX", 512, "sizeY", 384, "sizeZ", 512,
                        "resolution", 4)));

        assertTrue(failure.getMessage().contains("1048576"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "minecraft.world.heightmap.read",
            "minecraft.world.light.analyze"
    })
    void registersAndDelegatesClientWorldAnalysis(String capability) {
        var invoked = new AtomicReference<String>();
        var adapter = new NeoForgeAdapter();
        adapter.attachClientBridge(new NeoForgeAdapter.ClientBridge() {
            @Override
            public boolean available(String candidate) {
                return true;
            }

            @Override
            public java.util.concurrent.CompletionStage<Map<String, Object>> invoke(
                    String candidate, InvocationContext invocation) {
                invoked.set(candidate);
                return CompletableFuture.completedFuture(Map.of("capability", candidate));
            }

            @Override
            public java.util.concurrent.CompletionStage<Map<String, Object>> reconcileSession() {
                return CompletableFuture.completedFuture(Map.of());
            }
        });

        var handler = adapter.handlers().get(capability);
        assertNotNull(handler, "world analysis must have a native client handler");
        var output = handler.invoke(invocation(capability)).toCompletableFuture().join();
        assertEquals(capability, invoked.get());
        assertEquals(capability, output.get("capability"));
    }

    private static InvocationContext invocation(String capability) {
        var request = new RequestEnvelope(
                ProtocolVersion.CURRENT,
                "request-1",
                "session-1",
                capability,
                "1.0",
                Map.of(),
                null,
                null,
                false);
        return new InvocationContext(request, CancellationToken.none(), Runnable::run, Map.of());
    }
}
