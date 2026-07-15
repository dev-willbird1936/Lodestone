// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GoalPlan(String id, String goal, List<GoalSegment> segments, Map<String, Object> metadata) {
    public GoalPlan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("goal plan id is required");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal plan goal is required");
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("goal plan needs at least one segment");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean completionPredicateReady() {
        return Boolean.TRUE.equals(metadata.get("completionPredicateReady"))
                && segments.stream().anyMatch(segment -> !segment.assertions().isEmpty()
                || segment.steps().stream().anyMatch(step -> !step.assertions().isEmpty()));
    }

    @SuppressWarnings("unchecked")
    public static GoalPlan fromMap(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("plan must be an object");
        var segments = new ArrayList<GoalSegment>();
        if (!(raw.get("segments") instanceof List<?> segmentValues) || segmentValues.isEmpty()) {
            throw new IllegalArgumentException("plan.segments must be a non-empty array");
        }
        for (var segmentValue : segmentValues) {
            if (!(segmentValue instanceof Map<?, ?> segmentMap)) throw new IllegalArgumentException("plan segment must be an object");
            var segment = castMap(segmentMap);
            var steps = new ArrayList<GoalStep>();
            if (!(segment.get("steps") instanceof List<?> stepValues) || stepValues.isEmpty()) {
                throw new IllegalArgumentException("plan segment steps must be a non-empty array");
            }
            for (var stepValue : stepValues) {
                if (!(stepValue instanceof Map<?, ?> stepMap)) throw new IllegalArgumentException("plan step must be an object");
                steps.add(GoalStep.fromMap(castMap(stepMap)));
            }
            var assertions = new ArrayList<GoalAssertion>();
            if (segment.get("assertions") instanceof List<?> assertionValues) {
                for (var value : assertionValues) {
                    if (value instanceof Map<?, ?> map) assertions.add(assertion(castMap(map)));
                }
            }
            segments.add(new GoalSegment(String.valueOf(segment.getOrDefault("id", "segment-" + segments.size())),
                    String.valueOf(segment.getOrDefault("description", "")), steps, assertions));
        }
        Map<String, Object> metadata = raw.get("metadata") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        return new GoalPlan(String.valueOf(raw.getOrDefault("id", "custom-plan")),
                String.valueOf(raw.getOrDefault("goal", "custom Minecraft goal")), segments, metadata);
    }

    static GoalAssertion assertion(Map<String, Object> raw) {
        return new GoalAssertion(String.valueOf(raw.getOrDefault("path", "")),
                String.valueOf(raw.getOrDefault("operator", "exists")), raw.get("expected"));
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
