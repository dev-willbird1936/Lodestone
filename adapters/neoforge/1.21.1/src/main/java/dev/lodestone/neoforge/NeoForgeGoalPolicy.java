// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import java.util.Locale;
import java.util.Map;

/**
 * Loader-local projection of common goal policy fields.
 *
 * <p>{@code maxDescentBlocks} is deliberately not derived from any request field the way every
 * other member here is: {@link #from} always sets it to {@link #DEFAULT_MAX_DESCENT_BLOCKS} (1),
 * matching {@code NeoForgeSafePathPlanner}'s original hardcoded one-block-descent rule bit-for-bit
 * for every actor that builds its policy directly from {@link #from}. A goal that has specifically
 * verified it needs and can safely use a deeper drop (see {@code NeoForgeSpawnGauntletGoal}'s own
 * policy construction) derives its own widened copy via {@link #withMaxDescentBlocks}, so relaxing
 * it for one actor can never silently change another actor's edge set.
 */
record NeoForgeGoalPolicy(Intelligence intelligence, Safety safety, String observation,
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
                !Boolean.FALSE.equals(input.get("allowBlockPlacing")),
                Boolean.TRUE.equals(input.get("allowCommands")),
                DEFAULT_MAX_DESCENT_BLOCKS);
    }

    /**
     * Derives a copy of this policy with a different descent cap - see this record's own doc for
     * why this exists instead of a request-driven field. Every other field is copied unchanged.
     */
    NeoForgeGoalPolicy withMaxDescentBlocks(int value) {
        return new NeoForgeGoalPolicy(intelligence, safety, observation, combatPolicy,
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

    // Deliberately safety-only, matching hazardAvoidanceEnabled()/threatPreemptionEnabled() right
    // above and common/goal-engine's own GoalSafety.fallProtectionEnabled() ("Safety policies are
    // explicit sub-policies, not hidden intelligence side effects"). This previously read
    // `highSafety() || intelligence == Intelligence.ADAPTIVE_V1` - a since-superseded, one-off
    // intelligence-tier coupling from when this method was first added (commit 3cc715f), predating
    // GoalSafety's own fallProtectionEnabled() (commit 19d7cb1, later the same day) ever being
    // written. That left every non-ADAPTIVE_V1 actor at BALANCED safety - guarded-v1+balanced,
    // what every current benchmark actually runs at - with zero fall-damage braking during ordinary
    // exploration, live-caught as a death regression (B2, seed 123456789012345, died:fall 223 ticks
    // into SEARCH_TREES). LOW safety still gets none, matching its "fast"/no-safety-net design.
    boolean fallProtectionEnabled() {
        return safety != Safety.LOW;
    }

    String mode() {
        return intelligence.id() + "+" + safety.id();
    }

    private static Intelligence parseIntelligence(Object value) {
        var normalized = value == null || String.valueOf(value).isBlank()
                ? "medium" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "raw", "lowest", "raw-v1" -> Intelligence.RAW_V1;
            case "guarded", "medium", "guarded-v1" -> Intelligence.GUARDED_V1;
            case "high", "adaptive", "highest", "adaptive-v1" -> Intelligence.ADAPTIVE_V1;
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
                ? "medium" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "fast" -> Safety.LOW;
            case "medium", "balanced", "normal" -> Safety.BALANCED;
            case "high", "strict", "safe" -> Safety.HIGH;
            default -> throw new IllegalArgumentException("unknown goal safety: " + value);
        };
    }
}
