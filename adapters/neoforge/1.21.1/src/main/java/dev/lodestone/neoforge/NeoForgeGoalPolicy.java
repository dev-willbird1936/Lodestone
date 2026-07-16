// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import java.util.Locale;
import java.util.Map;

/** Loader-local projection of common goal policy fields. */
record NeoForgeGoalPolicy(Intelligence intelligence, Safety safety, String observation,
                          String combatPolicy, boolean allowBlockBreaking, boolean allowBlockPlacing) {
    enum Intelligence {
        RAW_V1("raw-v1"), GUARDED_V1("guarded-v1"), ADAPTIVE_V1("adaptive-v1");

        private final String id;

        Intelligence(String id) { this.id = id; }

        String id() { return id; }
    }

    enum Safety {
        LOW("low"), BALANCED("balanced"), HIGH("high");

        private final String id;

        Safety(String id) { this.id = id; }

        String id() { return id; }
    }

    static NeoForgeGoalPolicy from(Map<String, Object> input) {
        var observation = input.get("observation") == null ? "loaded-chunks"
                : String.valueOf(input.get("observation")).trim().toLowerCase(Locale.ROOT);
        var combatPolicy = input.get("combatPolicy") == null ? "defensive"
                : String.valueOf(input.get("combatPolicy")).trim().toLowerCase(Locale.ROOT);
        if (!observation.equals("loaded-chunks")) throw new IllegalArgumentException(
                "NeoForge goal observation must be loaded-chunks");
        if (!combatPolicy.equals("defensive") && !combatPolicy.equals("avoid")
                && !combatPolicy.equals("none")) throw new IllegalArgumentException(
                "NeoForge goal combatPolicy must be defensive, avoid, or none");
        return new NeoForgeGoalPolicy(parseIntelligence(input.get("intelligence")),
                parseSafety(input.get("safety")), observation, combatPolicy,
                !Boolean.FALSE.equals(input.get("allowBlockBreaking")),
                !Boolean.FALSE.equals(input.get("allowBlockPlacing")));
    }

    boolean supervisorEnabled() {
        return intelligence != Intelligence.RAW_V1 || safety != Safety.LOW;
    }

    boolean smartNavigation() {
        return supervisorEnabled();
    }

    boolean obstructionRecoveryEnabled() {
        return intelligence != Intelligence.RAW_V1 && allowBlockBreaking;
    }

    boolean prerequisitePlanningEnabled() {
        return intelligence != Intelligence.RAW_V1;
    }

    /** Intelligent actors must not spend goal budget punching tool-required terrain. */
    boolean toolPrerequisiteGuardEnabled() {
        return prerequisitePlanningEnabled();
    }

    boolean actionSegmentReplanningEnabled() {
        return intelligence == Intelligence.ADAPTIVE_V1;
    }

    boolean highSafety() {
        return safety == Safety.HIGH;
    }

    boolean fallProtectionEnabled() {
        return highSafety() || intelligence == Intelligence.ADAPTIVE_V1;
    }

    String mode() {
        return intelligence.id() + "+" + safety.id();
    }

    private static Intelligence parseIntelligence(Object value) {
        var normalized = value == null ? "raw-v1" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "raw", "lowest", "raw-v1" -> Intelligence.RAW_V1;
            case "guarded", "medium", "guarded-v1" -> Intelligence.GUARDED_V1;
            case "adaptive", "highest", "adaptive-v1" -> Intelligence.ADAPTIVE_V1;
            default -> throw new IllegalArgumentException("unknown goal intelligence: " + value);
        };
    }

    private static Safety parseSafety(Object value) {
        var normalized = value == null ? "low" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "fast" -> Safety.LOW;
            case "balanced", "normal", "medium" -> Safety.BALANCED;
            case "high", "strict", "safe" -> Safety.HIGH;
            default -> throw new IllegalArgumentException("unknown goal safety: " + value);
        };
    }
}
