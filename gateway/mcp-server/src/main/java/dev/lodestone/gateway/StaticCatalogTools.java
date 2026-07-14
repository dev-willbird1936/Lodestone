// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Bounded structured lookup over the bundled, hash-pinned Vibecraft reference catalogs. */
final class StaticCatalogTools {
    private static final int MAX_RESULTS = 50;
    private static final String FURNITURE_URI = "lodestone://vibecraft/furniture/layouts";
    private static final String BUILDING_URI = "lodestone://vibecraft/building/patterns";
    private static final String TERRAIN_URI = "lodestone://vibecraft/terrain/patterns";
    private static final String TEMPLATE_URI = "lodestone://vibecraft/building/templates";

    private final JsonArray furniture;
    private final JsonObject buildingPatterns;
    private final JsonObject terrainPatterns;
    private final JsonObject templates;

    StaticCatalogTools(LodestoneRuntime runtime) {
        // Use the Gson 2.8.0 instance API retained by legacy Forge runtimes.
        var parser = new JsonParser();
        furniture = parser.parse(runtime.readResource(FURNITURE_URI)).getAsJsonArray();
        buildingPatterns = parser.parse(runtime.readResource(BUILDING_URI)).getAsJsonObject()
                .getAsJsonObject("patterns");
        terrainPatterns = parser.parse(runtime.readResource(TERRAIN_URI)).getAsJsonObject()
                .getAsJsonObject("patterns");
        templates = parser.parse(runtime.readResource(TEMPLATE_URI)).getAsJsonObject()
                .getAsJsonObject("templates");
    }

    Map<String, Object> furniture(JsonObject arguments) {
        var action = requiredText(arguments, "action");
        return switch (action) {
            case "search" -> searchFurniture(arguments);
            case "get" -> getFurniture(requiredText(arguments, "furniture_id"));
            default -> throw invalid("action must be search or get");
        };
    }

    Map<String, Object> buildingPatterns(JsonObject arguments) {
        return patterns(arguments, buildingPatterns, BUILDING_URI, "building");
    }

    Map<String, Object> terrainPatterns(JsonObject arguments) {
        return patterns(arguments, terrainPatterns, TERRAIN_URI, "terrain");
    }

    Map<String, Object> buildingTemplates(JsonObject arguments) {
        var action = requiredText(arguments, "action");
        return switch (action) {
            case "list", "search" -> searchTemplates(arguments, action);
            case "get" -> getTemplate(requiredText(arguments, "template_id"), false);
            case "customize" -> getTemplate(requiredText(arguments, "template_id"), true);
            default -> throw invalid("action must be list, search, get, or customize");
        };
    }

    private Map<String, Object> searchFurniture(JsonObject arguments) {
        var query = optionalText(arguments, "query");
        var category = optionalText(arguments, "category");
        var tags = stringList(arguments, "tags");
        var matches = new ArrayList<JsonObject>();
        for (var element : furniture) {
            var item = element.getAsJsonObject();
            if (!matchesText(item, query, "id", "name", "category", "subcategory", "notes")) continue;
            if (category != null && !category.equals(normalized(item, "category"))) continue;
            if (!containsAllTags(item, tags, "tags")) continue;
            matches.add(item);
        }
        matches.sort(Comparator.comparing(item -> text(item, "id", "")));
        var results = matches.stream().limit(MAX_RESULTS).map(StaticCatalogTools::furnitureSummary).toList();
        return Map.of("action", "search", "count", results.size(), "truncated", matches.size() > results.size(),
                "results", results, "resourceUri", FURNITURE_URI);
    }

    private Map<String, Object> getFurniture(String id) {
        for (var element : furniture) {
            var item = element.getAsJsonObject();
            if (id.equalsIgnoreCase(text(item, "id", ""))) {
                return Map.of("action", "get", "found", true, "furniture", toJava(item),
                        "resourceUri", FURNITURE_URI);
            }
        }
        return Map.of("action", "get", "found", false, "furnitureId", id,
                "resourceUri", FURNITURE_URI);
    }

    private Map<String, Object> patterns(JsonObject arguments, JsonObject catalog, String uri, String kind) {
        var action = requiredText(arguments, "action");
        return switch (action) {
            case "browse", "search" -> searchPatterns(arguments, catalog, uri, kind, action);
            case "categories" -> facets(catalog, uri, kind, "category", null);
            case "subcategories" -> facets(catalog, uri, kind, "subcategory",
                    requiredText(arguments, "category"));
            case "tags" -> tags(catalog, uri, kind);
            case "get" -> getPattern(catalog, uri, kind, requiredText(arguments, "pattern_id"));
            default -> throw invalid("action must be browse, categories, subcategories, tags, search, or get");
        };
    }

    private Map<String, Object> searchPatterns(JsonObject arguments, JsonObject catalog, String uri,
                                               String kind, String action) {
        var query = "browse".equals(action) ? null : optionalText(arguments, "query");
        var category = "browse".equals(action) ? null : optionalText(arguments, "category");
        var subcategory = "browse".equals(action) ? null : optionalText(arguments, "subcategory");
        var requestedTags = "browse".equals(action) ? List.<String>of() : stringList(arguments, "tags");
        var matches = new ArrayList<JsonObject>();
        for (var entry : catalog.entrySet()) {
            var pattern = entry.getValue().getAsJsonObject();
            if (!matchesText(pattern, query, "id", "name", "category", "subcategory", "description")) continue;
            if (category != null && !category.equals(normalized(pattern, "category"))) continue;
            if (subcategory != null && !subcategory.equals(normalized(pattern, "subcategory"))) continue;
            if (!containsAllTags(pattern, requestedTags, "tags")) continue;
            matches.add(pattern);
        }
        matches.sort(Comparator.comparing(pattern -> text(pattern, "id", "")));
        var results = matches.stream().limit(MAX_RESULTS).map(StaticCatalogTools::patternSummary).toList();
        return Map.of("action", action, "kind", kind, "count", results.size(),
                "truncated", matches.size() > results.size(), "results", results, "resourceUri", uri,
                "placementAvailable", false,
                "limitation", "Catalog entries are metadata and guidance, not executable layer blueprints.");
    }

    private Map<String, Object> getPattern(JsonObject catalog, String uri, String kind, String id) {
        JsonObject match = null;
        for (var entry : catalog.entrySet()) {
            if (id.equalsIgnoreCase(entry.getKey())
                    || id.equalsIgnoreCase(text(entry.getValue().getAsJsonObject(), "id", ""))) {
                match = entry.getValue().getAsJsonObject();
                break;
            }
        }
        if (match == null) {
            return Map.of("action", "get", "kind", kind, "found", false, "patternId", id,
                    "resourceUri", uri, "placementAvailable", false);
        }
        return Map.of("action", "get", "kind", kind, "found", true, "pattern", toJava(match),
                "resourceUri", uri, "placementAvailable", false,
                "limitation", "No executable structured layer representation is present.");
    }

    private Map<String, Object> facets(JsonObject catalog, String uri, String kind, String field,
                                       String requiredCategory) {
        var counts = new TreeMap<String, Integer>();
        for (var entry : catalog.entrySet()) {
            var pattern = entry.getValue().getAsJsonObject();
            if (requiredCategory != null && !requiredCategory.equals(normalized(pattern, "category"))) continue;
            var value = text(pattern, field, "");
            if (!value.isBlank()) counts.merge(value, 1, Integer::sum);
        }
        var values = counts.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of("action", field.equals("category") ? "categories" : "subcategories", "kind", kind,
                "count", values.size(), "results", values, "resourceUri", uri);
    }

    private Map<String, Object> tags(JsonObject catalog, String uri, String kind) {
        var counts = new TreeMap<String, Integer>();
        for (var entry : catalog.entrySet()) {
            for (var tag : strings(entry.getValue().getAsJsonObject(), "tags")) counts.merge(tag, 1, Integer::sum);
        }
        var values = counts.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of("action", "tags", "kind", kind, "count", values.size(),
                "results", values, "resourceUri", uri);
    }

    private Map<String, Object> searchTemplates(JsonObject arguments, String action) {
        var category = "list".equals(action) ? null : optionalText(arguments, "category");
        var difficulty = "list".equals(action) ? null : optionalText(arguments, "difficulty");
        var styleTags = "list".equals(action) ? List.<String>of() : stringList(arguments, "style_tags");
        var results = new ArrayList<Map<String, Object>>();
        for (var entry : templates.entrySet()) {
            var template = entry.getValue().getAsJsonObject();
            var metadata = template.getAsJsonObject("metadata");
            if (category != null && !category.equals(normalized(metadata, "category"))) continue;
            if (difficulty != null && !difficulty.equals(normalized(metadata, "difficulty"))) continue;
            if (!containsAllTags(metadata, styleTags, "style_tags")) continue;
            results.add(Map.of("templateId", entry.getKey(), "metadata", toJava(metadata)));
        }
        results.sort(Comparator.comparing(result -> String.valueOf(result.get("templateId"))));
        return Map.of("action", action, "count", results.size(), "results", List.copyOf(results),
                "resourceUri", TEMPLATE_URI, "executable", false,
                "limitation", "Templates contain parameter and command guidance, not an executable compiler.");
    }

    private Map<String, Object> getTemplate(String id, boolean customize) {
        JsonObject match = null;
        String matchedId = null;
        for (var entry : templates.entrySet()) {
            if (id.equalsIgnoreCase(entry.getKey())) {
                matchedId = entry.getKey();
                match = entry.getValue().getAsJsonObject();
                break;
            }
        }
        if (match == null) {
            return Map.of("action", customize ? "customize" : "get", "found", false,
                    "templateId", id, "resourceUri", TEMPLATE_URI);
        }
        if (customize) {
            return Map.of("action", "customize", "found", true, "templateId", matchedId,
                    "metadata", toJava(match.get("metadata")), "parameters", toJava(match.get("parameters")),
                    "resourceUri", TEMPLATE_URI, "executable", false);
        }
        return Map.of("action", "get", "found", true, "templateId", matchedId,
                "template", toJava(match), "resourceUri", TEMPLATE_URI, "executable", false,
                "limitation", "Parameter substitution and command execution are intentionally not claimed.");
    }

    private static Map<String, Object> furnitureSummary(JsonObject item) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : List.of("id", "name", "category", "subcategory", "tags", "bounds", "materials")) {
            if (item.has(field) && !item.get(field).isJsonNull()) result.put(field, toJava(item.get(field)));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> patternSummary(JsonObject pattern) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : List.of("id", "name", "category", "subcategory", "tags", "description",
                "difficulty", "dimensions", "materials")) {
            if (pattern.has(field) && !pattern.get(field).isJsonNull()) result.put(field, toJava(pattern.get(field)));
        }
        return Map.copyOf(result);
    }

    private static boolean matchesText(JsonObject value, String query, String... fields) {
        if (query == null) return true;
        for (var field : fields) {
            if (normalized(value, field).contains(query)) return true;
        }
        return strings(value, "tags").stream().anyMatch(tag -> tag.contains(query));
    }

    private static boolean containsAllTags(JsonObject value, List<String> required, String field) {
        if (required.isEmpty()) return true;
        return strings(value, field).containsAll(required);
    }

    private static List<String> strings(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonArray()) return List.of();
        var result = new ArrayList<String>();
        for (var item : value.getAsJsonArray(field)) result.add(item.getAsString().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static List<String> stringList(JsonObject arguments, String field) {
        if (!arguments.has(field) || arguments.get(field).isJsonNull()) return List.of();
        if (!arguments.get(field).isJsonArray()) throw invalid(field + " must be an array");
        var result = new ArrayList<String>();
        for (var item : arguments.getAsJsonArray(field)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || item.getAsString().isBlank()) throw invalid(field + " must contain non-empty strings");
            result.add(item.getAsString().trim().toLowerCase(Locale.ROOT));
        }
        if (result.size() > 16) throw invalid(field + " must contain at most 16 values");
        return List.copyOf(result);
    }

    private static String requiredText(JsonObject arguments, String field) {
        var value = optionalText(arguments, field);
        if (value == null) throw invalid("missing required field: " + field);
        return value;
    }

    private static String optionalText(JsonObject arguments, String field) {
        if (!arguments.has(field) || arguments.get(field).isJsonNull()) return null;
        if (!arguments.get(field).isJsonPrimitive() || !arguments.get(field).getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be a string");
        }
        var value = arguments.get(field).getAsString().trim();
        if (value.isEmpty() || value.length() > 256) throw invalid(field + " must contain 1 to 256 characters");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalized(JsonObject value, String field) {
        return text(value, field, "").toLowerCase(Locale.ROOT);
    }

    private static String text(JsonObject value, String field, String fallback) {
        return value.has(field) && value.get(field).isJsonPrimitive()
                ? value.get(field).getAsString() : fallback;
    }

    private static Object toJava(JsonElement value) {
        return dev.lodestone.protocol.JsonSupport.MAPPER.fromJson(value, Object.class);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
