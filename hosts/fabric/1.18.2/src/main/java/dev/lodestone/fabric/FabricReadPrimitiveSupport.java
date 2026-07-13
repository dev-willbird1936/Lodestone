// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.InputNumbers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Pure validation and deterministic bounding shared by Fabric client read primitives. */
final class FabricReadPrimitiveSupport {
    private FabricReadPrimitiveSupport() {
    }

    static int boundedInt(Map<String, Object> input, String key, int fallback, int minimum, int maximum) {
        var raw = input.get(key);
        var value = raw == null ? fallback : exactInt(raw, key);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    static double boundedDouble(
            Map<String, Object> input, String key, double fallback, double minimum, double maximum) {
        var raw = input.get(key);
        if (raw != null && !(raw instanceof Number)) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        var value = raw == null ? fallback : ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be a finite number between " + minimum + " and " + maximum);
        }
        return value;
    }

    static String boundedText(String value, int maximumLength) {
        if (maximumLength < 0) {
            throw new IllegalArgumentException("maximumLength must be non-negative");
        }
        if (value == null) {
            return "";
        }
        var codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maximumLength) {
            return value;
        }
        var end = value.offsetByCodePoints(0, maximumLength);
        return value.substring(0, end);
    }

    static String requiredSchemaText(Map<String, Object> input, String key, int maximumCodePoints) {
        return schemaText(input.get(key), key, maximumCodePoints, false);
    }

    static String optionalSchemaText(Map<String, Object> input, String key, int maximumCodePoints) {
        return schemaText(input.get(key), key, maximumCodePoints, true);
    }

    static <T> BoundedValues<T> sortedBounded(
            Collection<T> source, Comparator<? super T> comparator, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        var sorted = new ArrayList<>(source);
        sorted.sort(comparator);
        var count = Math.min(sorted.size(), limit);
        return new BoundedValues<>(List.copyOf(sorted.subList(0, count)), sorted.size() > limit);
    }

    private static int exactInt(Object raw, String key) {
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        return InputNumbers.exactInt(number, key);
    }

    private static String schemaText(Object raw, String key, int maximumCodePoints, boolean optional) {
        if (maximumCodePoints < 1) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        if (raw == null && optional) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException("input field must be a string: " + key);
        }
        var length = value.codePointCount(0, value.length());
        if (length < 1 || length > maximumCodePoints) {
            throw new IllegalArgumentException(
                    key + " must contain between 1 and " + maximumCodePoints + " Unicode characters");
        }
        return value;
    }

    record BoundedValues<T>(List<T> values, boolean truncated) {
    }
}
