// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Small dependency-free JSON Schema subset used at the protocol boundary. */
public final class SchemaValidator {
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "anyOf", "oneOf",
            "const", "enum", "items", "minItems", "maxItems", "uniqueItems",
            "minLength", "maxLength", "pattern", "minimum", "maximum");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "object", "array", "string", "boolean", "number", "integer", "null");

    private SchemaValidator() {
    }

    public static List<String> validate(Map<String, Object> schema, Object value) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        var errors = new ArrayList<String>();
        var schemaNode = JsonSupport.MAPPER.toJsonTree(schema);
        validateSchema(schemaNode, "$schema", errors);
        if (!errors.isEmpty()) return List.copyOf(errors);
        validate(schemaNode, JsonSupport.MAPPER.toJsonTree(value), "$", errors);
        return List.copyOf(errors);
    }

    public static List<String> validateSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return List.of();
        var errors = new ArrayList<String>();
        validateSchema(JsonSupport.MAPPER.toJsonTree(schema), "$schema", errors);
        return List.copyOf(errors);
    }

    private static void validate(JsonElement schema, JsonElement value, String path, List<String> errors) {
        if (schema == null || schema.isJsonNull() || !schema.isJsonObject()) {
            return;
        }
        var object = schema.getAsJsonObject();
        if (object.has("anyOf")) {
            var matched = false;
            for (var alternative : object.getAsJsonArray("anyOf")) {
                var alternativeErrors = new ArrayList<String>();
                validate(alternative, value, path, alternativeErrors);
                if (alternativeErrors.isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " must match at least one allowed schema");
                return;
            }
        }
        if (object.has("oneOf")) {
            var matches = 0;
            for (var alternative : object.getAsJsonArray("oneOf")) {
                var alternativeErrors = new ArrayList<String>();
                validate(alternative, value, path, alternativeErrors);
                if (alternativeErrors.isEmpty()) matches++;
            }
            if (matches != 1) {
                errors.add(path + " must match exactly one allowed schema");
                return;
            }
        }
        if (object.has("const") && !object.get("const").equals(value)) {
            errors.add(path + " must equal " + object.get("const"));
            return;
        }
        if (object.has("enum") && !contains(object.getAsJsonArray("enum"), value)) {
            errors.add(path + " must be one of " + object.get("enum"));
            return;
        }
        if (object.has("type") && !matchesType(object.get("type").getAsString(), value)) {
            errors.add(path + " must be " + object.get("type").getAsString());
            return;
        }

        if (value.isJsonObject()) {
            validateObject(object, value.getAsJsonObject(), path, errors);
        } else if (value.isJsonArray()) {
            validateArray(object, value.getAsJsonArray(), path, errors);
        } else if (value.isJsonPrimitive()) {
            validatePrimitive(object, value.getAsJsonPrimitive(), path, errors);
        }
    }

    private static void validateObject(JsonObject schema, JsonObject value, String path, List<String> errors) {
        if (schema.has("required")) {
            for (var required : schema.getAsJsonArray("required")) {
                var name = required.getAsString();
                if (!value.has(name) || value.get(name).isJsonNull()) {
                    errors.add(path + " is missing required property '" + name + "'");
                }
            }
        }
        var properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        var additional = !schema.has("additionalProperties") || schema.get("additionalProperties").getAsBoolean();
        for (var entry : value.entrySet()) {
            var propertySchema = properties.get(entry.getKey());
            if (propertySchema == null) {
                if (!additional) {
                    errors.add(path + " contains unsupported property '" + entry.getKey() + "'");
                }
                continue;
            }
            validate(propertySchema, entry.getValue(), path + "." + entry.getKey(), errors);
        }
    }

    private static void validateArray(JsonObject schema, JsonArray value, String path, List<String> errors) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").getAsInt()) {
            errors.add(path + " must contain at least " + schema.get("minItems").getAsInt() + " items");
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").getAsInt()) {
            errors.add(path + " must contain at most " + schema.get("maxItems").getAsInt() + " items");
        }
        if (schema.has("items")) {
            for (var index = 0; index < value.size(); index++) {
                validate(schema.get("items"), value.get(index), path + "[" + index + "]", errors);
            }
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").getAsBoolean()) {
            var seen = new HashSet<JsonElement>();
            for (var item : value) {
                if (!seen.add(item)) {
                    errors.add(path + " must contain unique items");
                    break;
                }
            }
        }
    }

    private static void validatePrimitive(JsonObject schema, JsonPrimitive value, String path, List<String> errors) {
        if (value.isString()) {
            var length = value.getAsString().length();
            if (schema.has("minLength") && length < schema.get("minLength").getAsInt()) {
                errors.add(path + " must contain at least " + schema.get("minLength").getAsInt() + " characters");
            }
            if (schema.has("maxLength") && length > schema.get("maxLength").getAsInt()) {
                errors.add(path + " must contain at most " + schema.get("maxLength").getAsInt() + " characters");
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").getAsString())
                    .matcher(value.getAsString()).find()) {
                errors.add(path + " must match pattern " + schema.get("pattern").getAsString());
            }
        } else if (value.isNumber()) {
            var number = value.getAsDouble();
            if (!Double.isFinite(number)) {
                errors.add(path + " must be finite");
                return;
            }
            if (schema.has("minimum") && number < schema.get("minimum").getAsDouble()) {
                errors.add(path + " must be at least " + schema.get("minimum").getAsDouble());
            }
            if (schema.has("maximum") && number > schema.get("maximum").getAsDouble()) {
                errors.add(path + " must be at most " + schema.get("maximum").getAsDouble());
            }
        }
    }

    private static boolean contains(JsonArray values, JsonElement target) {
        for (var value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesType(String type, JsonElement value) {
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsDouble() == Math.rint(value.getAsDouble());
            case "null" -> value.isJsonNull();
            default -> false;
        };
    }

    private static void validateSchema(JsonElement schema, String path, List<String> errors) {
        if (schema == null || schema.isJsonNull() || !schema.isJsonObject()) {
            errors.add(path + " must be a schema object");
            return;
        }
        var object = schema.getAsJsonObject();
        for (var field : object.keySet()) {
            if (!SUPPORTED_KEYWORDS.contains(field)) {
                errors.add(path + " contains unsupported schema constraint '" + field + "'");
            }
        }
        if (object.has("type") && (!object.get("type").isJsonPrimitive()
                || !SUPPORTED_TYPES.contains(object.get("type").getAsString()))) {
            errors.add(path + " contains unsupported schema type '" + object.get("type") + "'");
        }
        if (object.has("properties")) {
            if (!object.get("properties").isJsonObject()) {
                errors.add(path + ".properties must be an object");
            } else {
                object.getAsJsonObject("properties").entrySet().forEach(entry ->
                        validateSchema(entry.getValue(), path + ".properties." + entry.getKey(), errors));
            }
        }
        if (object.has("items")) validateSchema(object.get("items"), path + ".items", errors);
        for (var alternatives : List.of("anyOf", "oneOf")) {
            if (!object.has(alternatives)) continue;
            if (!object.get(alternatives).isJsonArray() || object.getAsJsonArray(alternatives).isEmpty()) {
                errors.add(path + "." + alternatives + " must be a non-empty array");
                continue;
            }
            for (var index = 0; index < object.getAsJsonArray(alternatives).size(); index++) {
                validateSchema(object.getAsJsonArray(alternatives).get(index),
                        path + "." + alternatives + "[" + index + "]", errors);
            }
        }
        if (object.has("additionalProperties")
                && (!object.get("additionalProperties").isJsonPrimitive()
                || !object.get("additionalProperties").getAsJsonPrimitive().isBoolean())) {
            errors.add(path + ".additionalProperties must be boolean in the supported subset");
        }
    }
}
