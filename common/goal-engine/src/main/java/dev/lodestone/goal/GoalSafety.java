// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

/** Independent risk budget for goal execution. */
public enum GoalSafety {
    LOW("low"), BALANCED("balanced"), HIGH("high");

    private final String id;

    GoalSafety(String id) { this.id = id; }

    public String id() { return id; }

    /** Safety policies are explicit sub-policies, not hidden intelligence side effects. */
    public boolean hazardAvoidanceEnabled() { return this != LOW; }

    public boolean fallProtectionEnabled() { return this != LOW; }

    public boolean threatPreemptionEnabled() { return this != LOW; }

    public boolean progressMayBePreempted() { return this == HIGH; }

    public static GoalSafety parse(String value) {
        if (value == null || value.isBlank()) return BALANCED;
        var normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "low", "fast" -> LOW;
            case "medium", "balanced", "normal" -> BALANCED;
            case "high", "strict", "safe" -> HIGH;
            default -> throw new IllegalArgumentException("safety must be low, medium, or high");
        };
    }
}
