// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.InputNumbers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FabricWorldAnalysis {
    private static final int MAX_HEIGHTMAP_AXIS = 256;
    private static final int MAX_LIGHT_XZ = 512;
    private static final int MAX_LIGHT_Y = 384;
    private static final int MAX_LIGHT_SAMPLES = 1_048_576;

    private FabricWorldAnalysis() {
    }

    static Operation operation(String capability) {
        return switch (capability) {
            case "minecraft.world.heightmap.read" -> Operation.HEIGHTMAP;
            case "minecraft.world.light.analyze" -> Operation.LIGHT;
            default -> throw new IllegalArgumentException(
                    "unsupported world analysis capability: " + capability);
        };
    }

    static HeightmapRequest heightmapRequest(Map<String, Object> input) {
        var x = number(input, "x");
        var z = number(input, "z");
        var sizeX = bounded(input, "sizeX", 1, MAX_HEIGHTMAP_AXIS);
        var sizeZ = bounded(input, "sizeZ", 1, MAX_HEIGHTMAP_AXIS);
        InputNumbers.requireRegionBounds(x, 0, z, sizeX, 1, sizeZ);
        return new HeightmapRequest(x, z, sizeX, sizeZ,
                Math.multiplyExact(sizeX, sizeZ), bool(input, "includeSurfaceBlocks", true));
    }

    static LightRequest lightRequest(Map<String, Object> input) {
        var x = number(input, "x");
        var y = number(input, "y");
        var z = number(input, "z");
        var sizeX = bounded(input, "sizeX", 1, MAX_LIGHT_XZ);
        var sizeY = bounded(input, "sizeY", 1, MAX_LIGHT_Y);
        var sizeZ = bounded(input, "sizeZ", 1, MAX_LIGHT_XZ);
        var resolution = boundedOrDefault(input, "resolution", 1, 1, 4);
        var darkSpotLimit = boundedOrDefault(input, "darkSpotLimit", 100, 0, 256);
        var lightSourceLimit = boundedOrDefault(input, "lightSourceLimit", 100, 0, 256);
        InputNumbers.requireRegionBounds(x, y, z, sizeX, sizeY, sizeZ);

        var samplesX = ceilDiv(sizeX, resolution);
        var samplesY = ceilDiv(sizeY, resolution);
        var samplesZ = ceilDiv(sizeZ, resolution);
        var candidateSamples = (long) samplesX * samplesY * samplesZ;
        if (candidateSamples > MAX_LIGHT_SAMPLES) {
            throw new IllegalArgumentException("light analysis must contain at most 1048576 samples");
        }
        return new LightRequest(x, y, z, sizeX, sizeY, sizeZ, resolution,
                darkSpotLimit, lightSourceLimit, (int) candidateSamples);
    }

    static String mobSpawnRisk(int darkCount, int analyzedSamples) {
        if (darkCount == 0 || analyzedSamples == 0) {
            return "none";
        }
        if ((long) darkCount * 10 < analyzedSamples) {
            return "low";
        }
        if ((long) darkCount * 10 < (long) analyzedSamples * 3) {
            return "medium";
        }
        return "high";
    }

    static List<Suggestion> suggestions(List<DarkSpot> darkSpots, int minY, int maxY) {
        var accumulator = new SuggestionAccumulator(minY, maxY);
        for (var spot : darkSpots) {
            accumulator.accept(spot);
        }
        return accumulator.snapshot();
    }

    static Map<String, Object> heightmap(HeightmapRequest request, String dimension,
                                         HeightSampler sampler, Runnable checkpoint) {
        var columns = new ArrayList<Map<String, Object>>(request.columnCount());
        var stats = new HeightStatsAccumulator();
        var loadedColumns = 0;
        var unloadedColumns = 0;
        for (var offsetZ = 0; offsetZ < request.sizeZ(); offsetZ++) {
            for (var offsetX = 0; offsetX < request.sizeX(); offsetX++) {
                checkpoint.run();
                var x = request.x() + offsetX;
                var z = request.z() + offsetZ;
                var sample = sampler.sample(x, z);
                var column = new LinkedHashMap<String, Object>();
                column.put("x", x);
                column.put("z", z);
                column.put("loaded", sample.loaded());
                column.put("empty", sample.loaded() && sample.empty());
                if (!sample.loaded()) {
                    unloadedColumns++;
                } else {
                    loadedColumns++;
                    if (!sample.empty()) {
                        column.put("height", sample.height());
                        if (request.includeSurfaceBlocks()) {
                            column.put("surfaceBlock", sample.surfaceBlock());
                        }
                        stats.accept(sample.height());
                    }
                }
                columns.add(Map.copyOf(column));
            }
        }

        var snapshot = stats.snapshot();
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", dimension);
        result.put("origin", Map.of("x", request.x(), "z", request.z()));
        result.put("size", Map.of("x", request.sizeX(), "z", request.sizeZ()));
        result.put("columnCount", request.columnCount());
        result.put("loadedColumns", loadedColumns);
        result.put("unloadedColumns", unloadedColumns);
        result.put("columns", List.copyOf(columns));
        result.put("stats", Map.of("hasHeightData", snapshot.hasHeightData(),
                "minHeight", snapshot.minHeight(), "maxHeight", snapshot.maxHeight(),
                "heightRange", snapshot.heightRange()));
        return Map.copyOf(result);
    }

    static Map<String, Object> lightAnalysis(LightRequest request, String dimension,
                                             int minBuildHeight, int maxBuildHeight,
                                             LightSampler sampler, Runnable checkpoint) {
        var lastY = (long) request.y() + request.sizeY() - 1;
        if (request.y() < minBuildHeight || lastY >= maxBuildHeight) {
            throw new IllegalArgumentException(
                    "light analysis region must stay within world build height");
        }

        var light = new LightAccumulator();
        var darkSpots = new ArrayList<Map<String, Object>>(request.darkSpotLimit());
        var lightSources = new ArrayList<Map<String, Object>>(request.lightSourceLimit());
        var suggestionAccumulator = new SuggestionAccumulator(request.y(), (int) lastY);
        var solidSamples = 0;
        var unloadedSamples = 0;
        var darkSpotCount = 0;
        var lightSourceCount = 0;

        for (var offsetY = 0; offsetY < request.sizeY(); offsetY += request.resolution()) {
            for (var offsetZ = 0; offsetZ < request.sizeZ(); offsetZ += request.resolution()) {
                for (var offsetX = 0; offsetX < request.sizeX(); offsetX += request.resolution()) {
                    checkpoint.run();
                    var x = request.x() + offsetX;
                    var y = request.y() + offsetY;
                    var z = request.z() + offsetZ;
                    var sample = sampler.sample(x, y, z);
                    if (!sample.loaded()) {
                        unloadedSamples++;
                        continue;
                    }
                    if (sample.emission() < 0 || sample.emission() > 15) {
                        throw new IllegalArgumentException("light emission must be between 0 and 15");
                    }
                    if (sample.emission() > 0) {
                        lightSourceCount++;
                        if (lightSources.size() < request.lightSourceLimit()) {
                            lightSources.add(Map.of("position", position(x, y, z),
                                    "block", sample.block(), "emission", sample.emission()));
                        }
                    }
                    if (sample.solid()) {
                        solidSamples++;
                        continue;
                    }

                    light.accept(sample.blockLight(), sample.skyLight());
                    var combined = Math.max(sample.blockLight(), sample.skyLight());
                    if (combined < 8) {
                        darkSpotCount++;
                        var darkSpot = new DarkSpot(
                                x, y, z, sample.blockLight(), sample.skyLight(), combined);
                        suggestionAccumulator.accept(darkSpot);
                        if (darkSpots.size() < request.darkSpotLimit()) {
                            darkSpots.add(Map.of("position", position(x, y, z),
                                    "blockLight", sample.blockLight(), "skyLight", sample.skyLight(),
                                    "combinedLight", combined,
                                    "risk", combined < 1 ? "high" : "medium"));
                        }
                    }
                }
            }
        }

        var summary = light.snapshot();
        var distribution = Map.of(
                "wellLit", Map.of("count", summary.wellLitCount(),
                        "percentage", summary.wellLitPercentage()),
                "dim", Map.of("count", summary.dimCount(),
                        "percentage", summary.dimPercentage()),
                "dark", Map.of("count", summary.darkCount(),
                        "percentage", summary.darkPercentage()));
        var suggestions = suggestionAccumulator.snapshot().stream()
                .map(suggestion -> Map.<String, Object>of(
                        "position", position(suggestion.x(), suggestion.y(), suggestion.z()),
                        "suggestedSource", suggestion.suggestedSource(),
                        "reason", suggestion.reason()))
                .toList();

        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", dimension);
        result.put("origin", position(request.x(), request.y(), request.z()));
        result.put("size", position(request.sizeX(), request.sizeY(), request.sizeZ()));
        result.put("resolution", request.resolution());
        result.put("candidateSamples", request.candidateSamples());
        result.put("analyzedSamples", summary.analyzedSamples());
        result.put("solidSamples", solidSamples);
        result.put("unloadedSamples", unloadedSamples);
        result.put("averageCombinedLight", summary.averageCombinedLight());
        result.put("histogram", summary.histogram());
        result.put("distribution", distribution);
        result.put("darkSpotCount", darkSpotCount);
        result.put("darkSpotsTruncated", darkSpotCount > darkSpots.size());
        result.put("darkSpots", List.copyOf(darkSpots));
        result.put("lightSourceCount", lightSourceCount);
        result.put("lightSourcesTruncated", lightSourceCount > lightSources.size());
        result.put("lightSources", List.copyOf(lightSources));
        result.put("mobSpawnRisk", summary.mobSpawnRisk());
        result.put("suggestions", List.copyOf(suggestions));
        return Map.copyOf(result);
    }

    private static Map<String, Object> position(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int number(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        return InputNumbers.exactInt(number, key);
    }

    private static int bounded(Map<String, Object> input, String key, int minimum, int maximum) {
        var parsed = number(input, key);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static int boundedOrDefault(Map<String, Object> input, String key, int fallback,
                                        int minimum, int maximum) {
        return input.get(key) == null ? fallback : bounded(input, key, minimum, maximum);
    }

    private static boolean bool(Map<String, Object> input, String key, boolean fallback) {
        var value = input.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean parsed)) {
            throw new IllegalArgumentException("input field must be boolean: " + key);
        }
        return parsed;
    }

    record HeightmapRequest(int x, int z, int sizeX, int sizeZ, int columnCount,
                            boolean includeSurfaceBlocks) {
    }

    record LightRequest(int x, int y, int z, int sizeX, int sizeY, int sizeZ,
                        int resolution, int darkSpotLimit, int lightSourceLimit,
                        int candidateSamples) {
    }

    record HeightStats(boolean hasHeightData, int minHeight, int maxHeight, int heightRange) {
    }

    @FunctionalInterface
    interface HeightSampler {
        HeightColumn sample(int x, int z);
    }

    record HeightColumn(boolean loaded, boolean empty, int height, String surfaceBlock) {
    }

    @FunctionalInterface
    interface LightSampler {
        LightCell sample(int x, int y, int z);
    }

    record LightCell(boolean loaded, boolean solid, String block, int emission,
                     int blockLight, int skyLight) {
    }

    static final class HeightStatsAccumulator {
        private int count;
        private int minimum = Integer.MAX_VALUE;
        private int maximum = Integer.MIN_VALUE;

        void accept(int height) {
            count++;
            minimum = Math.min(minimum, height);
            maximum = Math.max(maximum, height);
        }

        HeightStats snapshot() {
            return count == 0 ? new HeightStats(false, 0, 0, 0)
                    : new HeightStats(true, minimum, maximum, maximum - minimum);
        }
    }

    static final class LightAccumulator {
        private final int[] histogram = new int[16];
        private long totalLight;
        private int analyzedSamples;

        void accept(int blockLight, int skyLight) {
            if (blockLight < 0 || blockLight > 15 || skyLight < 0 || skyLight > 15) {
                throw new IllegalArgumentException("light values must be between 0 and 15");
            }
            var combined = Math.max(blockLight, skyLight);
            histogram[combined]++;
            totalLight += combined;
            analyzedSamples++;
        }

        LightSummary snapshot() {
            var histogramList = new ArrayList<Integer>(histogram.length);
            var dark = 0;
            var dim = 0;
            var wellLit = 0;
            for (var level = 0; level < histogram.length; level++) {
                histogramList.add(histogram[level]);
                if (level < 8) {
                    dark += histogram[level];
                } else if (level < 12) {
                    dim += histogram[level];
                } else {
                    wellLit += histogram[level];
                }
            }
            var average = analyzedSamples == 0 ? 0.0 : (double) totalLight / analyzedSamples;
            return new LightSummary(analyzedSamples, average,
                    dark, percentage(dark, analyzedSamples),
                    dim, percentage(dim, analyzedSamples),
                    wellLit, percentage(wellLit, analyzedSamples),
                    mobSpawnRisk(dark, analyzedSamples), List.copyOf(histogramList));
        }
    }

    private static double percentage(int count, int total) {
        return total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0;
    }

    record LightSummary(int analyzedSamples, double averageCombinedLight,
                        int darkCount, double darkPercentage,
                        int dimCount, double dimPercentage,
                        int wellLitCount, double wellLitPercentage,
                        String mobSpawnRisk, List<Integer> histogram) {
    }

    record DarkSpot(int x, int y, int z, int blockLight, int skyLight, int combinedLight) {
    }

    record Suggestion(int x, int y, int z, String suggestedSource, String reason) {
    }

    private static final class SuggestionAccumulator {
        private final int minY;
        private final int maxY;
        private final ArrayList<Suggestion> suggestions = new ArrayList<>();
        private final HashSet<SuggestionCell> covered = new HashSet<>();

        private SuggestionAccumulator(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }

        private void accept(DarkSpot spot) {
            if (suggestions.size() >= 20) {
                return;
            }
            var cell = new SuggestionCell(Math.floorDiv(spot.x(), 14),
                    Math.floorDiv(spot.y(), 8), Math.floorDiv(spot.z(), 14));
            if (!covered.add(cell)) {
                return;
            }
            if (spot.y() < minY + 3) {
                suggestions.add(new Suggestion(spot.x(), spot.y(), spot.z(), "lantern",
                        "Floor level - lantern provides good coverage"));
            } else if (spot.y() > maxY - 3) {
                suggestions.add(new Suggestion(spot.x(), spot.y(), spot.z(), "lantern",
                        "Ceiling area - hanging lantern recommended"));
            } else {
                suggestions.add(new Suggestion(spot.x(), spot.y(), spot.z(), "torch",
                        "Wall placement recommended"));
            }
        }

        private List<Suggestion> snapshot() {
            return List.copyOf(suggestions);
        }
    }

    private record SuggestionCell(int x, int y, int z) {
    }

    enum Operation {
        HEIGHTMAP,
        LIGHT
    }
}
