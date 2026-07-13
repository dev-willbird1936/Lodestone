// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void mapsOnlyExactWorldAnalysisIds() {
        assertEquals(FabricWorldAnalysis.Operation.HEIGHTMAP,
                FabricWorldAnalysis.operation("minecraft.world.heightmap.read"));
        assertEquals(FabricWorldAnalysis.Operation.LIGHT,
                FabricWorldAnalysis.operation("minecraft.world.light.analyze"));
        assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.operation("minecraft.world.region.scan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heightmapPreservesZMajorOrderAndHonestColumnStates() {
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
    void heightmapOmitsSurfaceBlocksAndUsesEmptyStatsSentinel() {
        var request = FabricWorldAnalysis.heightmapRequest(Map.of(
                "x", -1, "z", -1, "sizeX", 1, "sizeZ", 1,
                "includeSurfaceBlocks", false));
        var output = FabricWorldAnalysis.heightmap(request, "minecraft:the_void",
                (x, z) -> new FabricWorldAnalysis.HeightColumn(true, true, 0, "minecraft:air"),
                () -> { });

        var column = ((List<Map<String, Object>>) output.get("columns")).getFirst();
        assertFalse(column.containsKey("height"));
        assertFalse(column.containsKey("surfaceBlock"));
        assertEquals(Map.of("hasHeightData", false, "minHeight", 0,
                "maxHeight", 0, "heightRange", 0), output.get("stats"));
    }

    @Test
    void heightmapAcceptsMaximumColumnsAndRejectsEveryCoordinateOverflow() {
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
    void lightAnalysisUsesYZXOrderAndReportsExactCapsAndInvariants() {
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
        assertEquals(8.2, output.get("averageCombinedLight"));
        assertEquals(2, output.get("darkSpotCount"));
        assertEquals(true, output.get("darkSpotsTruncated"));
        assertEquals(2, output.get("lightSourceCount"));
        assertEquals(true, output.get("lightSourcesTruncated"));
        assertEquals("high", output.get("mobSpawnRisk"));

        var histogram = (List<Integer>) output.get("histogram");
        assertEquals(16, histogram.size());
        assertEquals(5, histogram.stream().mapToInt(Integer::intValue).sum());
        var darkSpots = (List<Map<String, Object>>) output.get("darkSpots");
        assertEquals(Map.of("x", -3, "y", 2, "z", -3), darkSpots.getFirst().get("position"));
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
    @SuppressWarnings("unchecked")
    void lightAnalysisHonorsZeroFindingLimitsWithoutLosingCounts() {
        var request = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", 0, "z", 0,
                "sizeX", 1, "sizeY", 1, "sizeZ", 1,
                "darkSpotLimit", 0, "lightSourceLimit", 0));
        var output = FabricWorldAnalysis.lightAnalysis(request, "minecraft:overworld", -64, 320,
                (x, y, z) -> new FabricWorldAnalysis.LightCell(
                        true, false, "minecraft:redstone_torch", 7, 0, 0), () -> { });

        assertEquals(1, output.get("darkSpotCount"));
        assertEquals(true, output.get("darkSpotsTruncated"));
        assertTrue(((List<Map<String, Object>>) output.get("darkSpots")).isEmpty());
        assertEquals(1, output.get("lightSourceCount"));
        assertEquals(true, output.get("lightSourcesTruncated"));
        assertTrue(((List<Map<String, Object>>) output.get("lightSources")).isEmpty());
    }

    @Test
    void lightRequestAcceptsExactSampleCapAndRejectsOneSamplingPlaneMore() {
        var exact = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", -64, "z", 0,
                "sizeX", 512, "sizeY", 256, "sizeZ", 512,
                "resolution", 4));
        assertEquals(1_048_576, exact.candidateSamples());

        var failure = assertThrows(IllegalArgumentException.class,
                () -> FabricWorldAnalysis.lightRequest(Map.of(
                        "x", 0, "y", -64, "z", 0,
                        "sizeX", 512, "sizeY", 257, "sizeZ", 512,
                        "resolution", 4)));
        assertTrue(failure.getMessage().contains("1048576"));
    }

    @Test
    void lightRequestRejectsOverflowOnEveryAxis() {
        assertAll(
                () -> assertTrue(overflowFailure("x").getMessage().contains("x")),
                () -> assertTrue(overflowFailure("y").getMessage().contains("y")),
                () -> assertTrue(overflowFailure("z").getMessage().contains("z")));
    }

    @Test
    void lightAnalysisAcceptsExactBuildHeightAndRejectsEitherOutsideEdge() {
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
                        () -> FabricWorldAnalysis.lightAnalysis(
                                FabricWorldAnalysis.lightRequest(Map.of(
                                        "x", 0, "y", -65, "z", 0,
                                        "sizeX", 1, "sizeY", 1, "sizeZ", 1)),
                                "minecraft:overworld", -64, 320,
                                (x, y, z) -> new FabricWorldAnalysis.LightCell(
                                        true, false, "", 0, 0, 0), () -> { }))
                        .getMessage().contains("build height")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> FabricWorldAnalysis.lightAnalysis(
                                FabricWorldAnalysis.lightRequest(Map.of(
                                        "x", 0, "y", 319, "z", 0,
                                        "sizeX", 1, "sizeY", 2, "sizeZ", 1)),
                                "minecraft:overworld", -64, 320,
                                (x, y, z) -> new FabricWorldAnalysis.LightCell(
                                        true, false, "", 0, 0, 0), () -> { }))
                        .getMessage().contains("build height")));
    }

    @Test
    void lightAnalysisChecksCancellationBeforeFirstAndEveryLaterSample() {
        var request = FabricWorldAnalysis.lightRequest(Map.of(
                "x", 0, "y", 0, "z", 0,
                "sizeX", 4, "sizeY", 1, "sizeZ", 1));
        var beforeFirstSamples = new AtomicInteger();
        assertThrows(TestCancellation.class, () -> FabricWorldAnalysis.lightAnalysis(
                request, "minecraft:overworld", -64, 320, (x, y, z) -> {
                    beforeFirstSamples.incrementAndGet();
                    return new FabricWorldAnalysis.LightCell(true, false, "", 0, 0, 0);
                }, () -> { throw new TestCancellation(); }));
        assertEquals(0, beforeFirstSamples.get());

        var checkpoints = new AtomicInteger();
        var laterSamples = new AtomicInteger();
        assertThrows(TestCancellation.class, () -> FabricWorldAnalysis.lightAnalysis(
                request, "minecraft:overworld", -64, 320, (x, y, z) -> {
                    laterSamples.incrementAndGet();
                    return new FabricWorldAnalysis.LightCell(true, false, "", 0, 0, 0);
                }, () -> {
                    if (checkpoints.incrementAndGet() == 3) {
                        throw new TestCancellation();
                    }
                }));
        assertEquals(3, checkpoints.get());
        assertEquals(2, laterSamples.get());
    }

    @Test
    void lightSummaryAndSuggestionsKeepSpecifiedThresholdsAndNegativeCells() {
        var samples = new FabricWorldAnalysis.LightAccumulator();
        for (var level : List.of(0, 7, 8, 11, 12, 15)) {
            samples.accept(level, 0);
        }
        var summary = samples.snapshot();
        assertEquals(2, summary.darkCount());
        assertEquals(2, summary.dimCount());
        assertEquals(2, summary.wellLitCount());
        assertEquals(33.3, summary.darkPercentage());
        assertEquals("high", summary.mobSpawnRisk());
        assertEquals("low", FabricWorldAnalysis.mobSpawnRisk(9, 100));
        assertEquals("medium", FabricWorldAnalysis.mobSpawnRisk(10, 100));
        assertEquals("high", FabricWorldAnalysis.mobSpawnRisk(30, 100));

        var suggestions = FabricWorldAnalysis.suggestions(List.of(
                new FabricWorldAnalysis.DarkSpot(-1, 0, -1, 0, 0, 0),
                new FabricWorldAnalysis.DarkSpot(-13, 1, -13, 0, 0, 0),
                new FabricWorldAnalysis.DarkSpot(-15, 9, -15, 0, 0, 0),
                new FabricWorldAnalysis.DarkSpot(14, 5, 14, 0, 0, 0)), 0, 9);
        assertEquals(3, suggestions.size());
        assertEquals("lantern", suggestions.get(0).suggestedSource());
        assertTrue(suggestions.get(0).reason().startsWith("Floor"));
        assertEquals("lantern", suggestions.get(1).suggestedSource());
        assertTrue(suggestions.get(1).reason().startsWith("Ceiling"));
        assertEquals("torch", suggestions.get(2).suggestedSource());
    }

    private static IllegalArgumentException overflowFailure(String axis) {
        var input = new java.util.LinkedHashMap<String, Object>();
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

    private static final class TestCancellation extends RuntimeException {
    }
}
