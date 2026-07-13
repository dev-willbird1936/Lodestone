// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exactly-one selector for a snapshot-guarded UI mutation. */
public record UiSelector(String nodeId, List<Integer> path, String label, Double x, Double y) {
    public UiSelector {
        path = path == null ? null : List.copyOf(path);
        var targets = (nodeId == null ? 0 : 1) + (path == null ? 0 : 1)
                + (label == null ? 0 : 1) + (x == null && y == null ? 0 : 1);
        if (targets != 1 || (x == null) != (y == null)) {
            throw new IllegalArgumentException("UI selector requires exactly one of nodeId, path, label, or x/y");
        }
        if (nodeId != null && nodeId.isBlank()) throw new IllegalArgumentException("nodeId must not be blank");
        if (label != null && label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        if (path != null && (path.isEmpty() || path.size() > UiLimits.DEFAULT.maxDepth() + 1
                || path.stream().anyMatch(index -> index < 0))) {
            throw new IllegalArgumentException("UI selector path is outside bounds");
        }
        if (x != null && (!Double.isFinite(x) || !Double.isFinite(y))) {
            throw new IllegalArgumentException("UI coordinates must be finite");
        }
    }

    public static UiSelector from(Map<String, Object> input) {
        var nodeId = string(input.get("nodeId"));
        var label = string(input.get("label"));
        List<Integer> path = null;
        if (input.get("path") instanceof List<?> rawPath) {
            var converted = new ArrayList<Integer>(rawPath.size());
            for (var index = 0; index < rawPath.size(); index++) {
                var value = rawPath.get(index);
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("UI path element must be numeric: " + index);
                }
                converted.add(InputNumbers.exactInt(number, "path[" + index + "]"));
            }
            path = converted;
        } else if (input.containsKey("path")) {
            throw new IllegalArgumentException("UI path must be an array");
        }
        var x = finiteNumber(input.get("x"), "x");
        var y = finiteNumber(input.get("y"), "y");
        return new UiSelector(nodeId, path, label, x, y);
    }

    public UiNode resolve(List<UiNode> nodes) {
        if (x != null) return null;
        var matches = nodes.stream().filter(node -> nodeId != null ? node.nodeId().equals(nodeId)
                : path != null ? node.path().equals(path) : label.equals(node.label())).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("UI target was not found in guarded snapshot");
        if (matches.size() > 1) {
            throw new IllegalArgumentException("UI target is ambiguous; candidates="
                    + matches.stream().map(UiNode::nodeId).toList());
        }
        var node = matches.get(0);
        if (Boolean.FALSE.equals(node.active()) || Boolean.FALSE.equals(node.visible())
                || !node.actions().contains("click") || node.bounds() == null) {
            throw new IllegalStateException("UI target is hidden, inactive, or not clickable: " + node.nodeId());
        }
        return node;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Double finiteNumber(Object value, String field) {
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("UI coordinate must be finite: " + field);
        }
        return number.doubleValue();
    }
}
