// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loader-neutral projection of one node from a bounded native client UI snapshot. */
public record UiNode(String nodeId, List<Integer> path, int depth, String type, String parentType,
                     String label, String narration, UiBounds bounds, boolean focused,
                     Boolean active, Boolean visible, Set<String> actions,
                     Integer textLength, Boolean textPresent) {
    public UiNode {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        path = path == null ? List.of() : List.copyOf(path);
        if (path.size() > UiLimits.DEFAULT.maxDepth() + 1 || path.stream().anyMatch(index -> index < 0)) {
            throw new IllegalArgumentException("UI node path is outside bounds");
        }
        if (depth < 0 || depth > UiLimits.DEFAULT.maxDepth()) {
            throw new IllegalArgumentException("UI node depth is outside bounds");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("UI node type must not be blank");
        }
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        if (textLength != null && textLength < 0) {
            throw new IllegalArgumentException("textLength must not be negative");
        }
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("nodeId", nodeId);
        result.put("path", path);
        result.put("depth", depth);
        result.put("type", type);
        if (parentType != null && !parentType.isBlank()) result.put("parentType", parentType);
        if (label != null && !label.isBlank()) result.put("label", label);
        if (narration != null && !narration.isBlank()) result.put("narration", narration);
        if (bounds != null) result.put("bounds", bounds.toMap());
        result.put("focused", focused);
        if (active != null) result.put("active", active);
        if (visible != null) result.put("visible", visible);
        result.put("actions", actions.stream().sorted().toList());
        if (textLength != null) result.put("textLength", textLength);
        if (textPresent != null) result.put("textPresent", textPresent);
        return Map.copyOf(result);
    }
}
