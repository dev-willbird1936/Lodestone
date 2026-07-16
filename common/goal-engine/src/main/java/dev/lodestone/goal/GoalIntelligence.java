// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

/** Versioned goal reasoning contract. Safety remains an independent policy axis. */
public enum GoalIntelligence {
    RAW_V1("raw-v1", false, false, 0),
    GUARDED_V1("guarded-v1", true, false, 1),
    ADAPTIVE_V1("adaptive-v1", true, true, 3);

    private final String id;
    private final boolean guardrails;
    private final boolean modelReplanning;
    private final int planningDepth;

    GoalIntelligence(String id, boolean guardrails, boolean modelReplanning, int planningDepth) {
        this.id = id;
        this.guardrails = guardrails;
        this.modelReplanning = modelReplanning;
        this.planningDepth = planningDepth;
    }

    public String id() { return id; }

    public boolean guardrailsEnabled() { return guardrails; }

    public boolean modelReplanningEnabled() { return modelReplanning; }

    /** Number of prerequisite/action layers the native executor must preserve. */
    public int planningDepth() { return planningDepth; }

    /** Highest profiles must acquire required tools before attempting dependent work. */
    public boolean prerequisitePlanningEnabled() { return planningDepth >= 1; }

    /** Adaptive profiles may replan from fresh observations after each action segment. */
    public boolean actionSegmentReplanningEnabled() { return planningDepth >= 3; }

    /** Script mode uses deterministic segment replanning; realtime mode may additionally use the model. */
    public boolean scriptSegmentObservationEnabled() { return planningDepth >= 3; }

    /** Only realtime adaptive execution requires the pinned low-latency model provider. */
    public boolean requiresModel(GoalMode mode) {
        return modelReplanning && mode == GoalMode.REALTIME;
    }

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
