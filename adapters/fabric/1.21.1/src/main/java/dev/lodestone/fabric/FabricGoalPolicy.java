// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import java.util.Locale;
import java.util.Map;

/**
 * Loader-local projection of common goal policy fields.
 *
 * <p>Ported from {@code NeoForgeGoalPolicy} for the {@code minecraft.goal.navigation.safe-waypoint}
 * capability; behavior is bit-for-bit identical to the NeoForge 1.21.1 original.
 *
 * <p>{@code maxDescentBlocks} is deliberately not derived from any request field the way every
 * other member here is: {@link #from} always sets it to {@link #DEFAULT_MAX_DESCENT_BLOCKS} (1),
 * matching {@code FabricSafePathPlanner}'s original hardcoded one-block-descent rule bit-for-bit
 * for every actor that builds its policy directly from {@link #from}.
 */
record FabricGoalPolicy(Intelligence intelligence, Safety safety, String observation,
                        String combatPolicy, boolean allowBlockBreaking, boolean allowBlockPlacing,
                        boolean allowCommands, int maxDescentBlocks) {
    static final int DEFAULT_MAX_DESCENT_BLOCKS = 1;

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

    static FabricGoalPolicy from(Map<String, Object> input) {
        var observation = input.get("observation") == null ? "loaded-chunks"
                : String.valueOf(input.get("observation")).trim().toLowerCase(Locale.ROOT);
        var combatPolicy = input.get("combatPolicy") == null ? "defensive"
                : String.valueOf(input.get("combatPolicy")).trim().toLowerCase(Locale.ROOT);
        if (!observation.equals("loaded-chunks")) throw new IllegalArgumentException(
                "Fabric goal observation must be loaded-chunks");
        if (!combatPolicy.equals("defensive") && !combatPolicy.equals("avoid")
                && !combatPolicy.equals("none")) throw new IllegalArgumentException(
                "Fabric goal combatPolicy must be defensive, avoid, or none");
        return new FabricGoalPolicy(parseIntelligence(input.get("intelligence")),
                parseSafety(input.get("safety")), observation, combatPolicy,
                !Boolean.FALSE.equals(input.get("allowBlockBreaking")),
                !Boolean.FALSE.equals(input.get("allowBlockPlacing")),
                Boolean.TRUE.equals(input.get("allowCommands")),
                DEFAULT_MAX_DESCENT_BLOCKS);
    }

    /**
     * Derives a copy of this policy with a different descent cap - see this record's own doc for
     * why this exists instead of a request-driven field. Every other field is copied unchanged.
     */
    FabricGoalPolicy withMaxDescentBlocks(int value) {
        return new FabricGoalPolicy(intelligence, safety, observation, combatPolicy,
                allowBlockBreaking, allowBlockPlacing, allowCommands, value);
    }

    boolean supervisorEnabled() {
        return intelligence != Intelligence.RAW_V1 || safety != Safety.LOW;
    }

    boolean smartNavigation() {
        return safeNavigationPlanningEnabled() || safety != Safety.LOW;
    }

    boolean obstructionRecoveryEnabled() {
        return intelligence != Intelligence.RAW_V1 && allowBlockBreaking;
    }

    /** Guarded profiles route around obstructions; adaptive may mine a visible obstruction. */
    boolean obstructionMiningEnabled() {
        return intelligence == Intelligence.ADAPTIVE_V1 && allowBlockBreaking;
    }

    /** Symmetric to {@link #obstructionMiningEnabled()}: adaptive may place a support block. */
    boolean obstructionPlacementEnabled() {
        return intelligence == Intelligence.ADAPTIVE_V1 && allowBlockPlacing;
    }

    boolean prerequisitePlanningEnabled() {
        return intelligence != Intelligence.RAW_V1;
    }

    boolean directObservedActionsOnly() {
        return intelligence == Intelligence.RAW_V1;
    }

    boolean safeExposedPrerequisiteSearchEnabled() {
        return intelligence != Intelligence.RAW_V1;
    }

    boolean recursivePrerequisitePlanningEnabled() {
        return intelligence == Intelligence.ADAPTIVE_V1;
    }

    boolean undergroundPrerequisiteAcquisitionEnabled() {
        return recursivePrerequisitePlanningEnabled() && allowBlockBreaking;
    }

    boolean frontierNoveltyRecoveryEnabled() {
        return intelligence == Intelligence.ADAPTIVE_V1;
    }

    String intelligenceLayer() {
        return switch (intelligence) {
            case RAW_V1 -> "direct-observed-actions";
            case GUARDED_V1 -> "prerequisite-tools+safe-exposed-search";
            case ADAPTIVE_V1 -> "recursive-prerequisites+underground-acquisition+novelty-recovery";
        };
    }

    boolean safeNavigationPlanningEnabled() {
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

    boolean hazardAvoidanceEnabled() {
        return safety != Safety.LOW;
    }

    boolean threatPreemptionEnabled() {
        return safety != Safety.LOW;
    }

    boolean fallProtectionEnabled() {
        return highSafety() || intelligence == Intelligence.ADAPTIVE_V1;
    }

    String mode() {
        return intelligence.id() + "+" + safety.id();
    }

    private static Intelligence parseIntelligence(Object value) {
        var normalized = value == null || String.valueOf(value).isBlank()
                ? "guarded-v1" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "raw", "lowest", "raw-v1" -> Intelligence.RAW_V1;
            case "guarded", "medium", "guarded-v1" -> Intelligence.GUARDED_V1;
            case "adaptive", "highest", "adaptive-v1" -> Intelligence.ADAPTIVE_V1;
            // deliberate-v1's extra behavior (realtime lookahead-plan consultation, situational
            // deliberation budget) lives entirely at the engine/model-provider layer above this
            // adapter; this loader has no separate tier for it and intentionally reuses
            // adaptive-v1's guardrails, prerequisite planning, and obstruction mining unchanged.
            case "deliberate", "deliberate-v1", "perfect", "xhigh" -> Intelligence.ADAPTIVE_V1;
            default -> throw new IllegalArgumentException("unknown goal intelligence: " + value);
        };
    }

    private static Safety parseSafety(Object value) {
        var normalized = value == null || String.valueOf(value).isBlank()
                ? "balanced" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "fast" -> Safety.LOW;
            case "balanced", "normal", "medium" -> Safety.BALANCED;
            case "high", "strict", "safe" -> Safety.HIGH;
            default -> throw new IllegalArgumentException("unknown goal safety: " + value);
        };
    }
}
