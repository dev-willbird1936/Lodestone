// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.List;

public record GoalSegment(String id, String description, List<GoalStep> steps, List<GoalAssertion> assertions) {
    public GoalSegment {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("goal segment id is required");
        id = id.trim();
        description = description == null ? "" : description;
        steps = steps == null ? List.of() : List.copyOf(steps);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }
}
