// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.Locale;

/** Versioned goal reasoning contract. Safety remains an independent policy axis. */
public enum GoalIntelligence {
    RAW_V1("raw-v1", false, false, 0),
    GUARDED_V1("guarded-v1", true, false, 1),
    ADAPTIVE_V1("adaptive-v1", true, true, 3),
    DELIBERATE_V1("deliberate-v1", true, true, 4);

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

    public boolean toolPrerequisitePlanningEnabled() { return planningDepth >= 1; }

    public boolean safeNavigationPlanningEnabled() { return guardrailsEnabled(); }

    public boolean obstructionRecoveryEnabled() { return guardrailsEnabled(); }

    /** Only the highest profile may turn a visible obstruction into a mining action. */
    public boolean obstructionMiningEnabled() { return planningDepth >= 3; }

    /** Guarded and higher profiles acquire required tools before dependent work when the actor supports it. */
    public boolean prerequisitePlanningEnabled() { return toolPrerequisitePlanningEnabled(); }

    /** Adaptive profiles may replan from fresh observations after each action segment. */
    public boolean actionSegmentReplanningEnabled() { return planningDepth >= 3; }

    /** Guarded and adaptive scripts pass a fresh observation across each action boundary. */
    public boolean scriptSegmentObservationEnabled() { return guardrailsEnabled(); }

    /**
     * Adaptive and deliberate profiles may synthesize a bounded declarative plan when no built-in
     * task matches the requested goal. Gated by planning depth (not enum identity) so a future
     * tier above adaptive-v1 inherits this without another call-site change.
     */
    public boolean modelPlanSynthesisEnabled() { return planningDepth >= 3; }

    /**
     * Adaptive and deliberate profiles get the native phased/checkpointed workflow decomposition
     * (e.g. reach-nether's gather-starter-tools -> craft-portal-tools -> enter-nether, each a
     * separate capability invocation chained by continuationToken) instead of one monolithic
     * single-invocation step. Gated by planning depth so a future tier above adaptive-v1 keeps this
     * without another call-site change; the long, multi-phase native workflows genuinely need the
     * checkpoint boundaries to avoid timing out a single capability call under the actor's own tick
     * and maxDurationMs budgets.
     */
    public boolean checkpointedWorkflowEnabled() { return planningDepth >= 3; }

    /**
     * Only the top deliberate profile also consults the model's bounded lookahead planner at
     * realtime segment boundaries even when the native/declared task is already supported, giving
     * it a strategy beyond a purely greedy per-step choice.
     */
    public boolean realtimePlanConsultationEnabled() { return planningDepth >= 4; }

    /** Only realtime adaptive/deliberate execution requires the pinned low-latency model provider. */
    public boolean requiresModel(GoalMode mode) {
        return modelReplanning && mode == GoalMode.REALTIME;
    }

    public static GoalIntelligence parse(String value) {
        if (value == null || value.isBlank()) return GUARDED_V1;
        var normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "raw", "lowest", "raw-v1" -> RAW_V1;
            case "guarded", "medium", "guarded-v1" -> GUARDED_V1;
            // "highest" is a legacy alias frozen at adaptive-v1 for existing MCP callers; use
            // deliberate/deliberate-v1/perfect/xhigh to opt into the newer top tier below.
            case "adaptive", "highest", "adaptive-v1" -> ADAPTIVE_V1;
            case "deliberate", "deliberate-v1", "perfect", "xhigh" -> DELIBERATE_V1;
            default -> throw new IllegalArgumentException(
                    "intelligence must be raw-v1, guarded-v1, adaptive-v1, or deliberate-v1");
        };
    }
}
