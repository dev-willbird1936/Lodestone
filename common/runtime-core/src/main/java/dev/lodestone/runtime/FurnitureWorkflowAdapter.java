// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.ResultEnvelope;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Loader-neutral furniture placement composed from a bounded, negotiated native block write. */
final class FurnitureWorkflowAdapter implements LodestoneAdapter {
    static final String CAPABILITY_ID = "lodestone.furniture.place";
    static final String BLOCK_WRITE_ID = "minecraft.world.blocks.write";
    private static final String BLOCK_WRITE_VERSION = "1.0";
    private static final int MAX_CHANGES = 64;
    private static final List<String> FACINGS = List.of("north", "east", "south", "west");
    private static final Pattern BLOCK_SPEC = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+])?");

    private final CapabilityRegistry registry;
    private final Map<String, JsonObject> layouts;
    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            "lodestone.workflow", "0.2.0", "minecraft", "negotiated", "runtime", Environment.REMOTE);
    private final CapabilityDescriptor contract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + CAPABILITY_ID));

    FurnitureWorkflowAdapter(CapabilityRegistry registry) {
        this.registry = registry;
        this.layouts = loadLayouts();
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        return new CapabilityManifest(descriptor, List.of(negotiatedDescriptor()));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of(CAPABILITY_ID, this::place);
    }

    private CapabilityDescriptor negotiatedDescriptor() {
        var child = registry.get(BLOCK_WRITE_ID);
        if (child == null || !BLOCK_WRITE_VERSION.equals(child.descriptor().version())) {
            return contract.forAdapter(descriptor, Availability.UNAVAILABLE,
                    prerequisiteReason("Furniture placement requires minecraft.world.blocks.write version 1.0."));
        }
        var childDescriptor = child.descriptor();
        return contract.forAdapter(descriptor, childDescriptor.availability(),
                childDescriptor.availability() == Availability.AVAILABLE ? null : childDescriptor.reason());
    }

    private CompletionStage<Map<String, Object>> place(InvocationContext context) {
        var plan = plan(context);
        var childInput = new LinkedHashMap<String, Object>();
        childInput.put("dimension", plan.dimension());
        childInput.put("changes", plan.changes());
        childInput.put("dryRun", plan.dryRun());
        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
        return invoker.invoke("write.0", BLOCK_WRITE_ID, BLOCK_WRITE_VERSION, Map.copyOf(childInput))
                .thenApply(child -> mergeResult(plan, child));
    }

    private Plan plan(InvocationContext context) {
        var input = context.request().input();
        var requestedId = requiredString(input, "furniture_id");
        var layout = layouts.get(requestedId.toLowerCase(Locale.ROOT));
        if (layout == null) {
            throw new IllegalArgumentException("unknown furniture_id: " + requestedId);
        }
        var originX = requiredInt(input, "origin_x");
        var originY = requiredInt(input, "origin_y");
        var originZ = requiredInt(input, "origin_z");
        var placeOnSurface = !Boolean.FALSE.equals(input.get("place_on_surface"));
        var placementY = addExact(originY, placeOnSurface ? 1 : 0);
        var dimension = input.getOrDefault("dimension", "minecraft:overworld").toString();
        var boundsObject = requiredObject(layout, "bounds");
        var bounds = new Bounds(positiveInt(boundsObject, "width"), positiveInt(boundsObject, "height"),
                positiveInt(boundsObject, "depth"));
        var defaultFacing = optionalString(requiredObject(layout, "origin"), "facing", "north");
        var targetFacing = input.containsKey("facing") ? input.get("facing").toString() : defaultFacing;
        var rotation = rotationSteps(defaultFacing, targetFacing);
        var changes = expand(layout, originX, placementY, originZ, bounds, rotation, context);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("furniture layout contains no executable placements: " + requestedId);
        }
        var dryRun = context.request().dryRun() || Boolean.TRUE.equals(input.get("preview_only"));
        var name = requiredString(layout, "name");
        return new Plan(requiredString(layout, "id"), name, targetFacing,
                Map.of("x", originX, "y", originY, "z", originZ),
                Map.of("x", originX, "y", placementY, "z", originZ),
                Map.of("width", bounds.width(), "height", bounds.height(), "depth", bounds.depth()),
                dimension, dryRun, changes);
    }

    private static List<Map<String, Object>> expand(JsonObject layout, int originX, int originY, int originZ,
                                                     Bounds bounds, int rotation, InvocationContext context) {
        if (!layout.has("placements") || !layout.get("placements").isJsonArray()) {
            throw new IllegalArgumentException("furniture layout placements must be an array");
        }
        var changes = new ArrayList<Map<String, Object>>();
        for (var element : layout.getAsJsonArray("placements")) {
            context.cancellation().throwIfCancelled();
            if (!element.isJsonObject()) throw new IllegalArgumentException("furniture placement must be an object");
            var placement = element.getAsJsonObject();
            var type = requiredString(placement, "type");
            var block = blockSpec(placement, rotation);
            switch (type) {
                case "block" -> {
                    var position = rotate(requiredPosition(placement, "pos"), rotation, bounds);
                    addChange(changes, originX, originY, originZ, position, block);
                }
                case "fill" -> {
                    var from = rotate(requiredPosition(placement, "from"), rotation, bounds);
                    var to = rotate(requiredPosition(placement, "to"), rotation, bounds);
                    for (var y = Math.min(from.y(), to.y()); y <= Math.max(from.y(), to.y()); y++) {
                        for (var z = Math.min(from.z(), to.z()); z <= Math.max(from.z(), to.z()); z++) {
                            for (var x = Math.min(from.x(), to.x()); x <= Math.max(from.x(), to.x()); x++) {
                                context.cancellation().throwIfCancelled();
                                addChange(changes, originX, originY, originZ, new Position(x, y, z), block);
                            }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("unsupported furniture placement type: " + type);
            }
        }
        return List.copyOf(changes);
    }

    private static void addChange(List<Map<String, Object>> changes, int originX, int originY, int originZ,
                                  Position position, String block) {
        if (changes.size() >= MAX_CHANGES) {
            throw new IllegalArgumentException("furniture layout expands beyond " + MAX_CHANGES + " blocks");
        }
        changes.add(Map.of("x", addExact(originX, position.x()),
                "y", addExact(originY, position.y()),
                "z", addExact(originZ, position.z()), "block", block));
    }

    private static Map<String, Object> mergeResult(Plan plan, ResultEnvelope child) {
        if (child.status() != ResultEnvelope.Status.OK) {
            var error = child.error();
            throw new IllegalStateException("native block write failed: " + error.code() + ": " + error.message());
        }
        var nativeOutput = child.output();
        var result = new LinkedHashMap<String, Object>();
        result.put("furnitureId", plan.id());
        result.put("name", plan.name());
        result.put("facing", plan.facing());
        result.put("origin", plan.origin());
        result.put("placementOrigin", plan.placementOrigin());
        result.put("bounds", plan.bounds());
        for (var field : List.of("dimension", "dryRun", "validated", "requestedCount", "changedCount", "changes")) {
            if (!nativeOutput.containsKey(field)) {
                throw new IllegalStateException("native block write omitted required output field: " + field);
            }
            result.put(field, nativeOutput.get(field));
        }
        return Map.copyOf(result);
    }

    private static Position rotate(Position position, int steps, Bounds bounds) {
        return switch (steps) {
            case 0 -> position;
            case 1 -> new Position(bounds.depth() - 1 - position.z(), position.y(), position.x());
            case 2 -> new Position(bounds.width() - 1 - position.x(), position.y(),
                    bounds.depth() - 1 - position.z());
            case 3 -> new Position(position.z(), position.y(), bounds.width() - 1 - position.x());
            default -> throw new IllegalArgumentException("rotation steps must be between 0 and 3");
        };
    }

    private static String blockSpec(JsonObject placement, int rotation) {
        var block = requiredString(placement, "block").toLowerCase(Locale.ROOT);
        if (!block.contains(":")) block = "minecraft:" + block;
        var state = optionalString(placement, "state", "");
        if (!state.isEmpty()) state = rotateState(state, rotation);
        var spec = block + state;
        if (!BLOCK_SPEC.matcher(spec).matches()) {
            throw new IllegalArgumentException("invalid bundled furniture block specification: " + spec);
        }
        return spec;
    }

    private static String rotateState(String state, int steps) {
        if (!state.startsWith("[") || !state.endsWith("]")) {
            throw new IllegalArgumentException("furniture block state must use [key=value] syntax");
        }
        var body = state.substring(1, state.length() - 1);
        if (body.isBlank()) return "";
        var properties = new LinkedHashMap<String, String>();
        for (var token : body.split(",")) {
            var parts = token.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid furniture block state property: " + token);
            }
            properties.put(parts[0].trim(), parts[1].trim());
        }
        properties.computeIfPresent("facing", (ignored, value) -> rotateFacing(value, steps));
        properties.computeIfPresent("lever_direction", (ignored, value) -> rotateFacing(value, steps));
        properties.computeIfPresent("axis", (ignored, value) -> steps % 2 == 1
                ? switch (value) { case "x" -> "z"; case "z" -> "x"; default -> value; }
                : value);
        properties.computeIfPresent("shape", (ignored, value) -> rotateShape(value, steps));
        properties.computeIfPresent("rotation", (ignored, value) -> rotateSixteenth(value, steps));
        var joined = new StringJoiner(",", "[", "]");
        properties.forEach((key, value) -> joined.add(key + "=" + value));
        return joined.toString();
    }

    private static String rotateFacing(String facing, int steps) {
        var index = FACINGS.indexOf(facing);
        return index < 0 ? facing : FACINGS.get((index + steps) % FACINGS.size());
    }

    private static String rotateShape(String shape, int steps) {
        var current = shape;
        for (var index = 0; index < steps; index++) {
            current = switch (current) {
                case "north_south" -> "east_west";
                case "east_west" -> "north_south";
                case "ascending_north" -> "ascending_east";
                case "ascending_east" -> "ascending_south";
                case "ascending_south" -> "ascending_west";
                case "ascending_west" -> "ascending_north";
                case "north_east" -> "south_east";
                case "south_east" -> "south_west";
                case "south_west" -> "north_west";
                case "north_west" -> "north_east";
                default -> current;
            };
        }
        return current;
    }

    private static String rotateSixteenth(String value, int steps) {
        try {
            return Integer.toString(Math.floorMod(Integer.parseInt(value) + steps * 4, 16));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static int rotationSteps(String from, String to) {
        var fromIndex = FACINGS.indexOf(from);
        var toIndex = FACINGS.indexOf(to);
        if (fromIndex < 0 || toIndex < 0) {
            throw new IllegalArgumentException("facing must be north, east, south, or west");
        }
        return Math.floorMod(toIndex - fromIndex, FACINGS.size());
    }

    private static Map<String, JsonObject> loadLayouts() {
        try (var input = FurnitureWorkflowAdapter.class.getResourceAsStream(
                "/vibecraft/minecraft_furniture_layouts.json")) {
            if (input == null) throw new IllegalStateException("bundled furniture layouts are missing");
            // Forge 1.16.5 ships Gson 2.8.0, before the static parser helpers existed.
            var parsed = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) throw new IllegalStateException("bundled furniture layouts must be an array");
            var result = new LinkedHashMap<String, JsonObject>();
            for (var element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) throw new IllegalStateException("furniture layout must be an object");
                var layout = element.getAsJsonObject();
                var id = requiredString(layout, "id").toLowerCase(Locale.ROOT);
                if (result.putIfAbsent(id, layout) != null) {
                    throw new IllegalStateException("duplicate bundled furniture layout id: " + id);
                }
            }
            return Map.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read bundled furniture layouts", failure);
        }
    }

    private static AvailabilityReason prerequisiteReason(String message) {
        return new AvailabilityReason("workflow-prerequisite-unavailable", message,
                Map.of("capability", BLOCK_WRITE_ID, "version", BLOCK_WRITE_VERSION));
    }

    private static int requiredInt(Map<String, Object> value, String field) {
        var raw = value.get(field);
        if (!(raw instanceof Number number)) throw new IllegalArgumentException(field + " must be an integer");
        var asLong = number.longValue();
        if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE || number.doubleValue() != asLong) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        return (int) asLong;
    }

    private static int positiveInt(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("furniture " + field + " must be an integer");
        }
        var result = value.get(field).getAsInt();
        if (result < 1 || result > 64) throw new IllegalArgumentException("furniture " + field + " is out of bounds");
        return result;
    }

    private static JsonObject requiredObject(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonObject()) {
            throw new IllegalArgumentException("furniture " + field + " must be an object");
        }
        return value.getAsJsonObject(field);
    }

    private static Position requiredPosition(JsonObject value, String field) {
        var position = requiredObject(value, field);
        return new Position(requiredInt(position, "x"), requiredInt(position, "y"), requiredInt(position, "z"));
    }

    private static int requiredInt(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.get(field).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("furniture " + field + " must be an integer");
        }
        var number = value.get(field).getAsBigDecimal();
        try {
            return number.intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("furniture " + field + " must be a 32-bit integer", failure);
        }
    }

    private static String requiredString(Map<String, Object> value, String field) {
        var raw = value.get(field);
        if (!(raw instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return string.trim();
    }

    private static String requiredString(JsonObject value, String field) {
        var result = optionalString(value, field, null);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("furniture " + field + " must be a non-empty string");
        }
        return result;
    }

    private static String optionalString(JsonObject value, String field, String fallback) {
        if (!value.has(field) || value.get(field).isJsonNull()) return fallback;
        if (!value.get(field).isJsonPrimitive() || !value.get(field).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("furniture " + field + " must be a string");
        }
        return value.get(field).getAsString().trim().toLowerCase(Locale.ROOT);
    }

    private static int addExact(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("furniture coordinate overflow", failure);
        }
    }

    private record Bounds(int width, int height, int depth) {}

    private record Position(int x, int y, int z) {}

    private record Plan(String id, String name, String facing, Map<String, Object> origin,
                        Map<String, Object> placementOrigin, Map<String, Object> bounds,
                        String dimension, boolean dryRun, List<Map<String, Object>> changes) {}
}
