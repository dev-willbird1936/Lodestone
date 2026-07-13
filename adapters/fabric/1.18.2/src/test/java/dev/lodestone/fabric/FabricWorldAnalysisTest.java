// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricWorldAnalysisTest {
    @Test
    void mapsOnlyExactIdsAndRejectsNonIntegralCoordinates() {
        assertEquals(FabricWorldAnalysis.Operation.HEIGHTMAP,
                FabricWorldAnalysis.operation("minecraft.world.heightmap.read"));
        assertEquals(FabricWorldAnalysis.Operation.LIGHT,
                FabricWorldAnalysis.operation("minecraft.world.light.analyze"));
        assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.operation("minecraft.world.region.scan"));
        assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.heightmapRequest(Map.of(
                        "x", 0.5, "z", 0, "sizeX", 1, "sizeZ", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.lightRequest(Map.of(
                        "x", Double.NaN, "y", 0, "z", 0,
                        "sizeX", 1, "sizeY", 1, "sizeZ", 1)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heightmapUsesZThenXAndPreservesLoadedEmptyAndUnloadedStates() {
        var request = FabricWorldAnalysis.heightmapRequest(Map.of(
                "x", 10, "z", 20, "sizeX", 2, "sizeZ", 2));
        var sampled = new ArrayList<String>();
        var output = FabricWorldAnalysis.heightmap(request, "minecraft:overworld", (x, z) -> {
            sampled.add(x + ":" + z);
            return switch (sampled.size()) {
                case 1 -> new FabricWorldAnalysis.HeightColumn(true, false, 70, "minecraft:stone");
                case 2 -> new FabricWorldAnalysis.HeightColumn(false, false, 0, "");
                case 3 -> new FabricWorldAnalysis.HeightColumn(true, true, 0, "");
                default -> new FabricWorldAnalysis.HeightColumn(true, false, -3, "minecraft:dirt");
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
        assertEquals(Map.of("hasHeightData", true, "minHeight", -3,
                "maxHeight", 70, "heightRange", 73), output.get("stats"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heightmapHonorsSurfaceFlagAndEmptyStatsSentinel() {
        var request = FabricWorldAnalysis.heightmapRequest(Map.of(
                "x", -1, "z", -1, "sizeX", 1, "sizeZ", 1,
                "includeSurfaceBlocks", false));
        var output = FabricWorldAnalysis.heightmap(request, "minecraft:the_void",
                (x, z) -> new FabricWorldAnalysis.HeightColumn(true, true, 0, "minecraft:air"),
                () -> { });

        var column = ((List<Map<String, Object>>) output.get("columns")).get(0);
        assertFalse(column.containsKey("height"));
        assertFalse(column.containsKey("surfaceBlock"));
        assertEquals(Map.of("hasHeightData", false, "minHeight", 0,
                "maxHeight", 0, "heightRange", 0), output.get("stats"));
    }

    @Test
    void heightmapAcceptsMaximumAndRejectsAllEndpointOverflows() {
        var request = FabricWorldAnalysis.heightmapRequest(Map.of(
                "x", Integer.MIN_VALUE, "z", Integer.MIN_VALUE,
                "sizeX", 256, "sizeZ", 256));
        assertEquals(65_536, request.columnCount());
        assertTrue(request.includeSurfaceBlocks());

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> FabricWorldAnalysis.heightmapRequest(Map.of(
                                "x", Integer.MAX_VALUE, "z", 0, "sizeX", 2, "sizeZ", 1)))
                        .getMessage().contains("x")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> FabricWorldAnalysis.heightmapRequest(Map.of(
                                "x", 0, "z", Integer.MAX_VALUE, "sizeX", 1, "sizeZ", 2)))
                        .getMessage().contains("z")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> FabricWorldAnalysis.heightmapRequest(Map.of(
                                "x", 0, "z", 0, "sizeX", 257, "sizeZ", 1)))
                        .getMessage().contains("sizeX")));
    }

    @Test
    void heightmapChecksCancellationBeforeEverySample() {
        var request = FabricWorldAnalysis.heightmapRequest(Map.of(
                "x", 0, "z", 0, "sizeX", 4, "sizeZ", 1));
        var checkpoints = new AtomicInteger();
        var samples = new AtomicInteger();

        assertThrows(TestCancellation.class, () -> FabricWorldAnalysis.heightmap(
                request, "minecraft:overworld", (x, z) -> {
                    samples.incrementAndGet();
                    return new FabricWorldAnalysis.HeightColumn(true, false, 64, "minecraft:stone");
                }, () -> {
                    if (checkpoints.incrementAndGet() == 3) {
                        throw new TestCancellation();
                    }
                }));
        assertEquals(3, checkpoints.get());
        assertEquals(2, samples.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lightUsesYThenZThenXAndKeepsCountsLimitsAndCoordinates() {
        var request = FabricWorldAnalysis.lightRequest(Map.of(
                "x", -3, "y", 0, "z", -3,
                "sizeX", 3, "sizeY", 3, "sizeZ", 3,
                "resolution", 2, "darkSpotLimit", 1, "lightSourceLimit", 1));
        var cells = List.of(
                new FabricWorldAnalysis.LightCell(false, false, "", 0, 0, 0),
                new FabricWorldAnalysis.LightCell(true, true, "minecraft:stone", 0, 0, 0),
                new FabricWorldAnalysis.LightCell(true, true, "minecraft:glowstone", 15, 0, 0),
                new FabricWorldAnalysis.LightCell(true, false, "minecraft:torch", 14, 14, 0),
                new FabricWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 0, 0),
                new FabricWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 8, 4),
                new FabricWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 12, 2),
                new FabricWorldAnalysis.LightCell(true, false, "minecraft:air", 0, 7, 3));
        var sampled = new ArrayList<String>();
        var output = FabricWorldAnalysis.lightAnalysis(request, "minecraft:overworld", -64, 320,
                (x, y, z) -> {
                    sampled.add(x + ":" + y + ":" + z);
                    return cells.get(sampled.size() - 1);
                }, () -> { });

        assertEquals(List.of("-3:0:-3", "-1:0:-3", "-3:0:-1", "-1:0:-1",
                "-3:2:-3", "-1:2:-3", "-3:2:-1", "-1:2:-1"), sampled);
        assertEquals(8, output.get("candidateSamples"));
        assertEquals(5, output.get("analyzedSamples"));
        assertEquals(2, output.get("solidSamples"));
        assertEquals(1, output.get("unloadedSamples"));
        assertEquals(8, (int) output.get("analyzedSamples")
                + (int) output.get("solidSamples") + (int) output.get("unloadedSamples"));
        assertEquals(2, output.get("darkSpotCount"));
        assertEquals(true, output.get("darkSpotsTruncated"));
        assertEquals(2, output.get("lightSourceCount"));
        assertEquals(true, output.get("lightSourcesTruncated"));
        var darkSpots = (List<Map<String, Object>>) output.get("darkSpots");
        assertEquals(Map.of("x", -3, "y", 2, "z", -3), darkSpots.get(0).get("position"));
        var sources = (List<Map<String, Object>>) output.get("lightSources");
        assertEquals("minecraft:glowstone", sources.get(0).get("block"));
        assertEquals(15, sources.get(0).get("emission"));
    }

    @Test
    void lightAcceptsExactSampleCapAndRejectsOnePlaneAndAllAxisOverflows() {
        var exact = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", -64, "z", 0,
                "sizeX", 512, "sizeY", 256, "sizeZ", 512,
                "resolution", 4));
        assertEquals(1_048_576, exact.candidateSamples());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.lightRequest(Map.of(
                        "x", 0, "y", -64, "z", 0,
                        "sizeX", 512, "sizeY", 257, "sizeZ", 512,
                        "resolution", 4))).getMessage().contains("1048576"));
        assertAll(
                () -> assertTrue(overflowFailure("x").getMessage().contains("x")),
                () -> assertTrue(overflowFailure("y").getMessage().contains("y")),
                () -> assertTrue(overflowFailure("z").getMessage().contains("z")));
    }

    @Test
    void lightAcceptsExactBuildHeightAndRejectsBothOutsideEdges() {
        var exact = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", -64, "z", 0,
                "sizeX", 1, "sizeY", 384, "sizeZ", 1,
                "resolution", 4));
        var output = FabricWorldAnalysis.lightAnalysis(exact, "minecraft:overworld", -64, 320,
                (x, y, z) -> new FabricWorldAnalysis.LightCell(false, false, "", 0, 0, 0),
                () -> { });
        assertEquals(96, output.get("unloadedSamples"));

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> analyzeAt(-65, 1)).getMessage().contains("build height")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> analyzeAt(319, 2)).getMessage().contains("build height")));
    }

    @Test
    void lightChecksCancellationBeforeFirstAndEveryLaterSample() {
        var request = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", 0, "z", 0,
                "sizeX", 4, "sizeY", 1, "sizeZ", 1));
        var beforeFirst = new AtomicInteger();
        assertThrows(TestCancellation.class, () -> FabricWorldAnalysis.lightAnalysis(
                request, "minecraft:overworld", -64, 320, (x, y, z) -> {
                    beforeFirst.incrementAndGet();
                    return new FabricWorldAnalysis.LightCell(true, false, "", 0, 0, 0);
                }, () -> { throw new TestCancellation(); }));
        assertEquals(0, beforeFirst.get());

        var checkpoints = new AtomicInteger();
        var samples = new AtomicInteger();
        assertThrows(TestCancellation.class, () -> FabricWorldAnalysis.lightAnalysis(
                request, "minecraft:overworld", -64, 320, (x, y, z) -> {
                    samples.incrementAndGet();
                    return new FabricWorldAnalysis.LightCell(true, false, "", 0, 0, 0);
                }, () -> {
                    if (checkpoints.incrementAndGet() == 3) {
                        throw new TestCancellation();
                    }
                }));
        assertEquals(3, checkpoints.get());
        assertEquals(2, samples.get());
    }

    private static IllegalArgumentException overflowFailure(String axis) {
        var input = new LinkedHashMap<String, Object>();
        input.put("x", 0);
        input.put("y", 0);
        input.put("z", 0);
        input.put("sizeX", 1);
        input.put("sizeY", 1);
        input.put("sizeZ", 1);
        input.put(axis, Integer.MAX_VALUE);
        input.put("size" + axis.toUpperCase(), 2);
        return assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.lightRequest(Map.copyOf(input)));
    }

    private static void analyzeAt(int y, int sizeY) {
        var request = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", y, "z", 0,
                "sizeX", 1, "sizeY", sizeY, "sizeZ", 1));
        FabricWorldAnalysis.lightAnalysis(request, "minecraft:overworld", -64, 320,
                (x, sampleY, z) -> new FabricWorldAnalysis.LightCell(true, false, "", 0, 0, 0),
                () -> { });
    }

    private static final class TestCancellation extends RuntimeException {
    }
}
