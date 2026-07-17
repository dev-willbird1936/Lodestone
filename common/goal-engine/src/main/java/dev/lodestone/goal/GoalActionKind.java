// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

/**
 * Closed vocabulary for actions that can cross the goal-engine boundary.
 *
 * <p>Natural-language planners may choose among these categories, but they do not get to emit
 * executable code, arbitrary key streams, or an unclassified survival side effect.</p>
 */
public enum GoalActionKind {
    OBSERVATION,
    NAVIGATION,
    INTERACTION,
    COMBAT,
    INVENTORY,
    UI,
    NATIVE_GOAL,
    INPUT_RELEASE,
    RAW_INPUT,
    COMMAND,
    WORLD_MUTATION,
    UNKNOWN
}
