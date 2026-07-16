// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

/** Versioned goal reasoning contract. Safety remains an independent policy axis. */
public enum GoalIntelligence {
    RAW_V1("raw-v1", false, false),
    GUARDED_V1("guarded-v1", true, false),
    ADAPTIVE_V1("adaptive-v1", true, true);

    private final String id;
    private final boolean guardrails;
    private final boolean modelReplanning;

    GoalIntelligence(String id, boolean guardrails, boolean modelReplanning) {
        this.id = id;
        this.guardrails = guardrails;
        this.modelReplanning = modelReplanning;
    }

    public String id() { return id; }

    public boolean guardrailsEnabled() { return guardrails; }

    public boolean modelReplanningEnabled() { return modelReplanning; }

    public static GoalIntelligence parse(String value) {
        if (value == null || value.isBlank()) return RAW_V1;
        var normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "raw", "lowest", "raw-v1" -> RAW_V1;
            case "guarded", "medium", "guarded-v1" -> GUARDED_V1;
            case "adaptive", "highest", "adaptive-v1" -> ADAPTIVE_V1;
            default -> throw new IllegalArgumentException(
                    "intelligence must be raw-v1, guarded-v1, or adaptive-v1");
        };
    }
}
