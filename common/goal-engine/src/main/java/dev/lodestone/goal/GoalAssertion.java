// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.JsonSupport;

import java.util.List;
import java.util.Map;

public record GoalAssertion(String path, String operator, Object expected) {
    public GoalAssertion {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("assertion path is required");
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("assertion operator is required");
        path = path.trim();
        operator = operator.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean test(Map<String, Object> state) {
        var actual = GoalValues.read(state, path);
        return switch (operator) {
            case "exists" -> actual != null;
            case "equals", "equal" -> GoalValues.deepEquals(actual, expected);
            case "contains" -> GoalValues.contains(actual, expected);
            case "all_contains" -> GoalValues.allContains(actual, expected);
            case "gte", "at_least" -> GoalValues.number(actual) >= GoalValues.number(expected);
            case "lte", "at_most" -> GoalValues.number(actual) <= GoalValues.number(expected);
            case "not_exists" -> actual == null;
            default -> throw new IllegalArgumentException("unsupported goal assertion operator: " + operator);
        };
    }

    public Map<String, Object> toMap() {
        return Map.of("path", path, "operator", operator, "expected", expected == null ? Map.of() : expected);
    }
}

final class GoalValues {
    private GoalValues() {
    }

    static Object read(Map<String, Object> root, String path) {
        Object current = root;
        for (var token : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(token);
            } else if (current instanceof List<?> list && token.matches("\\d+")) {
                var index = Integer.parseInt(token);
                current = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    static boolean deepEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        return JsonSupport.MAPPER.toJsonTree(left).equals(JsonSupport.MAPPER.toJsonTree(right));
    }

    static boolean contains(Object actual, Object expected) {
        if (actual == null) return false;
        if (deepEquals(actual, expected)) return true;
        if (actual instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(value -> contains(value, expected));
        }
        if (actual instanceof Iterable<?> iterable) {
            for (var value : iterable) if (contains(value, expected)) return true;
        }
        return actual instanceof String text && expected != null && text.contains(String.valueOf(expected));
    }

    static boolean allContains(Object actual, Object expected) {
        if (!(actual instanceof Iterable<?> iterable)) return false;
        var found = false;
        for (var value : iterable) {
            found = true;
            if (!contains(value, expected)) return false;
        }
        return found;
    }

    static double number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("goal assertion value is not numeric");
        return number.doubleValue();
    }
}
