// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.JsonSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GoalStep(String id, GoalStepKind kind, String capability, String capabilityVersion,
                       Map<String, Object> input, List<GoalAssertion> assertions, boolean observeAfter) {
    public GoalStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("goal step id is required");
        id = id.trim();
        kind = kind == null ? GoalStepKind.INVOKE : kind;
        if (kind != GoalStepKind.ASSERT && (capability == null || capability.isBlank())) {
            throw new IllegalArgumentException("non-assert goal step requires capability");
        }
        capabilityVersion = capabilityVersion == null || capabilityVersion.isBlank() ? "1.0" : capabilityVersion;
        input = input == null ? Map.of() : Map.copyOf(input);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

    public static GoalStep observe(String id, String capability, Map<String, Object> input) {
        return new GoalStep(id, GoalStepKind.OBSERVE, capability, "1.0", input, List.of(), false);
    }

    public static GoalStep invoke(String id, String capability, String version, Map<String, Object> input,
                                  boolean observeAfter, GoalAssertion... assertions) {
        return new GoalStep(id, GoalStepKind.INVOKE, capability, version, input,
                List.of(assertions), observeAfter);
    }

    @SuppressWarnings("unchecked")
    static GoalStep fromMap(Map<String, Object> raw) {
        var id = String.valueOf(raw.getOrDefault("id", "step"));
        var kind = GoalStepKind.valueOf(String.valueOf(raw.getOrDefault("kind", "invoke"))
                .replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        var capability = raw.get("capability") == null ? null : String.valueOf(raw.get("capability"));
        var version = raw.get("capabilityVersion") == null ? "1.0" : String.valueOf(raw.get("capabilityVersion"));
        Map<String, Object> input = raw.get("input") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        var assertions = new ArrayList<GoalAssertion>();
        if (raw.get("assertions") instanceof List<?> list) {
            for (var value : list) if (value instanceof Map<?, ?> map) assertions.add(GoalPlan.assertion(castMap(map)));
        }
        return new GoalStep(id, kind, capability, version, input, assertions,
                Boolean.TRUE.equals(raw.get("observeAfter")));
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
