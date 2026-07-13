// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Pure, loader-neutral and bounded geometry calculations compatible with Vibecraft's shape surface. */
final class GeometryAdapter implements LodestoneAdapter {
    static final String CAPABILITY_ID = "lodestone.geometry.calculate";
    private static final int MAX_COORDINATES = 65_536;
    private static final int MAX_SPHERE_RADIUS = 25;
    private static final int MAX_SHELL_RADIUS = 48;

    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            "lodestone.geometry", "1.0.0", "minecraft", "all", "runtime", Environment.REMOTE);
    private final CapabilityDescriptor contract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + CAPABILITY_ID));

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        return new CapabilityManifest(descriptor,
                List.of(contract.forAdapter(descriptor, Availability.AVAILABLE, null)));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of(CAPABILITY_ID, this::calculate);
    }

    private CompletionStage<Map<String, Object>> calculate(InvocationContext context) {
        context.cancellation().throwIfCancelled();
        var input = context.request().input();
        var shape = String.valueOf(input.get("shape"));
        Map<String, Object> output = switch (shape) {
            case "circle" -> circle(context, requiredInt(input, "radius"), booleanValue(input, "filled", false));
            case "sphere" -> sphere(context, requiredInt(input, "radius"), booleanValue(input, "hollow", true));
            case "dome" -> dome(context, requiredInt(input, "radius"),
                    stringValue(input, "style", "hemisphere"));
            case "ellipse" -> ellipse(context, requiredInt(input, "width"), requiredInt(input, "height"),
                    booleanValue(input, "filled", false));
            case "arch" -> arch(context, requiredInt(input, "width"), requiredInt(input, "height"),
                    intValue(input, "depth", 1));
            default -> throw new IllegalArgumentException("unknown shape: " + shape);
        };
        return CompletableFuture.completedFuture(output);
    }

    private static Map<String, Object> circle(InvocationContext context, int radius, boolean filled) {
        var points = new TreeSet<Point2>();
        if (filled) {
            for (var x = -radius; x <= radius; x++) {
                context.cancellation().throwIfCancelled();
                for (var z = -radius; z <= radius; z++) {
                    if ((long) x * x + (long) z * z <= (long) radius * radius) {
                        add(points, new Point2(x, z));
                    }
                }
            }
        } else {
            var x = 0;
            var z = radius;
            var decision = 3 - 2 * radius;
            while (x <= z) {
                context.cancellation().throwIfCancelled();
                addCircleSymmetry(points, x, z);
                if (decision < 0) {
                    decision += 4 * x + 6;
                } else {
                    decision += 4 * (x - z) + 10;
                    z--;
                }
                x++;
            }
        }
        var output = base("circle", 2, coordinates2(points));
        output.put("radius", radius);
        output.put("filled", filled);
        output.put("asciiPreview", preview(points, radius));
        return Map.copyOf(output);
    }

    private static Map<String, Object> sphere(InvocationContext context, int radius, boolean hollow) {
        if (radius > MAX_SPHERE_RADIUS) {
            throw budgetFailure("sphere radius must be at most " + MAX_SPHERE_RADIUS);
        }
        var points = new TreeSet<Point3>();
        var radiusSquared = (long) radius * radius;
        for (var x = -radius; x <= radius; x++) {
            context.cancellation().throwIfCancelled();
            for (var y = -radius; y <= radius; y++) {
                for (var z = -radius; z <= radius; z++) {
                    var distanceSquared = (long) x * x + (long) y * y + (long) z * z;
                    if (hollow ? Math.abs(Math.sqrt(distanceSquared) - radius) < 0.7
                            : distanceSquared <= radiusSquared) {
                        add(points, new Point3(x, y, z));
                    }
                }
            }
        }
        var output = base("sphere", 3, coordinates3(points));
        output.put("radius", radius);
        output.put("hollow", hollow);
        output.put("worldEditCommand", "//sphere " + (hollow ? "h " : "") + "<block> " + radius);
        return Map.copyOf(output);
    }

    private static Map<String, Object> dome(InvocationContext context, int radius, String style) {
        if (radius > MAX_SHELL_RADIUS) {
            throw budgetFailure("dome radius must be at most " + MAX_SHELL_RADIUS);
        }
        var minimumY = switch (style) {
            case "hemisphere" -> 0;
            case "three_quarter" -> Math.floorDiv(-radius, 2);
            case "low" -> radius / 2;
            default -> throw new IllegalArgumentException("unknown dome style: " + style);
        };
        var points = new TreeSet<Point3>();
        for (var x = -radius; x <= radius; x++) {
            context.cancellation().throwIfCancelled();
            for (var y = minimumY; y <= radius; y++) {
                for (var z = -radius; z <= radius; z++) {
                    var distanceSquared = (long) x * x + (long) y * y + (long) z * z;
                    if (Math.abs(Math.sqrt(distanceSquared) - radius) < 0.7) {
                        add(points, new Point3(x, y, z));
                    }
                }
            }
        }
        var output = base("dome", 3, coordinates3(points));
        output.put("radius", radius);
        output.put("style", style);
        return Map.copyOf(output);
    }

    private static Map<String, Object> ellipse(InvocationContext context, int width, int height, boolean filled) {
        var semiX = width / 2;
        var semiZ = height / 2;
        var points = new TreeSet<Point2>();
        if (filled) {
            for (var x = -semiX; x <= semiX; x++) {
                context.cancellation().throwIfCancelled();
                for (var z = -semiZ; z <= semiZ; z++) {
                    if (insideEllipse(x, z, semiX, semiZ)) add(points, new Point2(x, z));
                }
            }
        } else if (semiX == 0 || semiZ == 0) {
            for (var x = -semiX; x <= semiX; x++) {
                for (var z = -semiZ; z <= semiZ; z++) add(points, new Point2(x, z));
            }
        } else {
            for (var angle = 0; angle < 360; angle++) {
                context.cancellation().throwIfCancelled();
                var radians = Math.toRadians(angle);
                add(points, new Point2((int) (semiX * Math.cos(radians)),
                        (int) (semiZ * Math.sin(radians))));
            }
        }
        var output = base("ellipse", 2, coordinates2(points));
        output.put("width", width);
        output.put("height", height);
        output.put("filled", filled);
        return Map.copyOf(output);
    }

    private static Map<String, Object> arch(InvocationContext context, int width, int height, int depth) {
        if (width < 2) throw new IllegalArgumentException("arch width must be at least 2");
        var radius = width / 2;
        var points = new TreeSet<Point3>();
        for (var x = -radius; x <= radius; x++) {
            context.cancellation().throwIfCancelled();
            var yOffset = Math.min(height, (int) Math.sqrt((long) radius * radius - (long) x * x));
            for (var z = 0; z < depth; z++) {
                for (var y = 0; y < yOffset; y++) {
                    if (Math.abs(x) >= radius - 1 || y == yOffset - 1) {
                        add(points, new Point3(x, y, z));
                    }
                }
            }
        }
        if (points.isEmpty()) throw new IllegalArgumentException("arch dimensions produce no blocks");
        var output = base("arch", 3, coordinates3(points));
        output.put("width", width);
        output.put("height", height);
        output.put("depth", depth);
        return Map.copyOf(output);
    }

    private static boolean insideEllipse(int x, int z, int semiX, int semiZ) {
        if (semiX == 0 && semiZ == 0) return x == 0 && z == 0;
        if (semiX == 0) return x == 0;
        if (semiZ == 0) return z == 0;
        return ((double) x * x) / ((double) semiX * semiX)
                + ((double) z * z) / ((double) semiZ * semiZ) <= 1.0;
    }

    private static void addCircleSymmetry(TreeSet<Point2> points, int x, int z) {
        add(points, new Point2(x, z));
        add(points, new Point2(-x, z));
        add(points, new Point2(x, -z));
        add(points, new Point2(-x, -z));
        add(points, new Point2(z, x));
        add(points, new Point2(-z, x));
        add(points, new Point2(z, -x));
        add(points, new Point2(-z, -x));
    }

    private static <T> void add(TreeSet<T> points, T point) {
        if (points.add(point) && points.size() > MAX_COORDINATES) throw budgetFailure("shape is too large");
    }

    private static IllegalArgumentException budgetFailure(String detail) {
        return new IllegalArgumentException(detail + "; maximum output is 65,536 coordinates");
    }

    private static LinkedHashMap<String, Object> base(String shape, int dimensions, List<List<Integer>> coordinates) {
        if (coordinates.isEmpty()) throw new IllegalArgumentException("shape dimensions produce no coordinates");
        var output = new LinkedHashMap<String, Object>();
        output.put("shape", shape);
        output.put("coordinateDimensions", dimensions);
        output.put("blocksCount", coordinates.size());
        output.put("coordinates", coordinates);
        output.put("bounded", true);
        return output;
    }

    private static List<List<Integer>> coordinates2(TreeSet<Point2> points) {
        var result = new ArrayList<List<Integer>>(points.size());
        points.forEach(point -> result.add(List.of(point.x(), point.z())));
        return List.copyOf(result);
    }

    private static List<List<Integer>> coordinates3(TreeSet<Point3> points) {
        var result = new ArrayList<List<Integer>>(points.size());
        points.forEach(point -> result.add(List.of(point.x(), point.y(), point.z())));
        return List.copyOf(result);
    }

    private static String preview(TreeSet<Point2> points, int radius) {
        var size = radius * 2 + 3;
        var grid = new char[size][size];
        for (var row : grid) java.util.Arrays.fill(row, ' ');
        for (var point : points) grid[point.z() + radius + 1][point.x() + radius + 1] = '#';
        grid[radius + 1][radius + 1] = '+';
        var result = new StringBuilder(size * (size + 1));
        for (var z = 0; z < size; z++) {
            if (z > 0) result.append('\n');
            result.append(grid[z]);
        }
        return result.toString();
    }

    private static int requiredInt(Map<String, Object> input, String key) {
        if (!input.containsKey(key)) throw new IllegalArgumentException(key + " is required for this shape");
        return integer(input.get(key), key);
    }

    private static int intValue(Map<String, Object> input, String key, int fallback) {
        return input.containsKey(key) ? integer(input.get(key), key) : fallback;
    }

    private static int integer(Object raw, String key) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        var value = number.longValue();
        if (number.doubleValue() != value || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return (int) value;
    }

    private static boolean booleanValue(Map<String, Object> input, String key, boolean fallback) {
        return input.containsKey(key) ? Boolean.TRUE.equals(input.get(key)) : fallback;
    }

    private static String stringValue(Map<String, Object> input, String key, String fallback) {
        return input.containsKey(key) ? String.valueOf(input.get(key)) : fallback;
    }

    private record Point2(int x, int z) implements Comparable<Point2> {
        @Override
        public int compareTo(Point2 other) {
            var xOrder = Integer.compare(x, other.x);
            return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
        }
    }

    private record Point3(int x, int y, int z) implements Comparable<Point3> {
        @Override
        public int compareTo(Point3 other) {
            var xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) return xOrder;
            var yOrder = Integer.compare(y, other.y);
            return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
        }
    }
}
