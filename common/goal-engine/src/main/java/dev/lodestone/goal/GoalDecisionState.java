// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded projection sent to a realtime model. The execution state remains complete locally, but
 * long action traces must not turn every low-latency decision into an ever-growing prompt.
 */
final class GoalDecisionState {
    private static final int MAX_LIST_ITEMS = 32;
    private static final int MAX_STRING_CHARS = 2_048;
    private static final int MAX_DEPTH = 8;

    private GoalDecisionState() {
    }

    static Map<String, Object> project(Map<String, Object> state) {
        var result = new LinkedHashMap<String, Object>();
        state.forEach((key, value) -> result.put(key, projectValue(value, 0)));
        result.put("decisionStateProjection", Map.of(
                "bounded", true,
                "maxListItems", MAX_LIST_ITEMS,
                "maxStringChars", MAX_STRING_CHARS,
                "retainedListSide", "tail"));
        // Keep null-valued facts representable. The execution state is model input, not a
        // domain object that should fail merely because an observation is temporarily absent.
        return Collections.unmodifiableMap(result);
    }

    private static Object projectValue(Object value, int depth) {
        if (value == null) return null;
        if (depth >= MAX_DEPTH) return String.valueOf(value);
        if (value instanceof String text) {
            return text.length() <= MAX_STRING_CHARS
                    ? text : text.substring(text.length() - MAX_STRING_CHARS);
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), projectValue(nested, depth + 1)));
            return result;
        }
        if (value instanceof List<?> list) {
            var start = Math.max(0, list.size() - MAX_LIST_ITEMS);
            var result = new ArrayList<Object>(Math.min(list.size(), MAX_LIST_ITEMS));
            for (var index = start; index < list.size(); index++) {
                result.add(projectValue(list.get(index), depth + 1));
            }
            return result;
        }
        return value;
    }
}
