// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

/** Explicit observation and mutation controls passed to native goal actors. */
public record GoalControls(String observation, String combatPolicy,
                           boolean allowBlockBreaking, boolean allowBlockPlacing,
                           boolean allowCommands) {
    public GoalControls(String observation, String combatPolicy,
                        boolean allowBlockBreaking, boolean allowBlockPlacing) {
        this(observation, combatPolicy, allowBlockBreaking, allowBlockPlacing, false);
    }

    public GoalControls {
        observation = normalize(observation, "loaded-chunks");
        combatPolicy = normalize(combatPolicy, "defensive");
        if (!observation.equals("loaded-chunks")) {
            throw new IllegalArgumentException("observation must be loaded-chunks");
        }
        if (!combatPolicy.equals("defensive") && !combatPolicy.equals("avoid")
                && !combatPolicy.equals("none")) {
            throw new IllegalArgumentException("combatPolicy must be defensive, avoid, or none");
        }
    }

    public static GoalControls defaults() {
        return new GoalControls("loaded-chunks", "defensive", true, true, false);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback
                : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
