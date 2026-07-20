"""DRAFT SKETCH tests - safety.py's safe-waypoint safety-param flooring (design notes section 2.3):
a tier may only RAISE the effective `safety` param toward its own floor, never lower it below what
the capability call itself requested, and every change is reported back to the model. Run with:

    python -m unittest verification.goal_orchestrator_draft.tests.test_safety_floor
"""

from __future__ import annotations

import os
import sys
import unittest

_VERIFICATION_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _VERIFICATION_DIR not in sys.path:
    sys.path.insert(0, _VERIFICATION_DIR)

from goal_orchestrator_draft import safety  # noqa: E402
from goal_orchestrator_draft.decision_protocol import ActionRequest  # noqa: E402
from goal_orchestrator_draft.hooks.base import TurnContext  # noqa: E402
from goal_orchestrator_draft.tests.fakes import FakeMcpClient, FakeTrace  # noqa: E402

_WAYPOINT_TOOL = "minecraft_goal_navigation_safe-waypoint"


class ApplySafetyFloorTest(unittest.TestCase):
    def test_low_tier_is_a_complete_noop(self):
        arguments = {"safety": "low", "combatPolicy": "none"}
        new_arguments, note = safety.apply_safety_floor(_WAYPOINT_TOOL, arguments, safety.SafetyTier.LOW)
        self.assertEqual(arguments, new_arguments)
        self.assertIsNone(note)

    def test_non_waypoint_tool_is_never_touched_at_any_tier(self):
        arguments = {"safety": "low"}
        new_arguments, note = safety.apply_safety_floor(
            "minecraft_world_blocks_interact", arguments, safety.SafetyTier.HIGH
        )
        self.assertEqual(arguments, new_arguments)
        self.assertIsNone(note)

    def test_high_tier_fills_in_omitted_safety_with_the_floor(self):
        new_arguments, note = safety.apply_safety_floor(_WAYPOINT_TOOL, {}, safety.SafetyTier.HIGH)
        self.assertEqual("high", new_arguments["safety"])
        self.assertIn("unset", note)
        self.assertIn("high", note)

    def test_high_tier_raises_an_explicit_request_below_the_floor(self):
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "low"}, safety.SafetyTier.HIGH
        )
        self.assertEqual("high", new_arguments["safety"])
        self.assertIn("raised", note)
        self.assertIn("low", note)
        self.assertIn("high", note)

    def test_the_floor_never_lowers_an_explicit_request_already_above_it(self):
        # This is the core "floor never override downward" invariant: the model already asked for
        # "high" while the session tier is only "balanced" (floor "balanced") - the model's own,
        # more-cautious-than-required choice must pass through completely unchanged.
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "high"}, safety.SafetyTier.BALANCED
        )
        self.assertEqual("high", new_arguments["safety"])
        self.assertIsNone(note)

    def test_a_request_exactly_at_the_floor_is_a_noop(self):
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "balanced"}, safety.SafetyTier.BALANCED
        )
        self.assertEqual("balanced", new_arguments["safety"])
        self.assertIsNone(note)

    def test_high_tier_defaults_omitted_combat_policy_to_avoid(self):
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "high"}, safety.SafetyTier.HIGH
        )
        self.assertEqual("avoid", new_arguments["combatPolicy"])
        self.assertIn("combatPolicy", note)

    def test_high_tier_never_overrides_an_explicit_combat_policy_choice(self):
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "high", "combatPolicy": "defensive"}, safety.SafetyTier.HIGH
        )
        self.assertEqual("defensive", new_arguments["combatPolicy"])

    def test_balanced_tier_never_touches_combat_policy_at_all(self):
        # Only the design doc's `high` row mentions combatPolicy - balanced must leave it alone,
        # whether present or absent.
        new_arguments, note = safety.apply_safety_floor(
            _WAYPOINT_TOOL, {"safety": "balanced"}, safety.SafetyTier.BALANCED
        )
        self.assertNotIn("combatPolicy", new_arguments)

    def test_original_arguments_dict_is_never_mutated_in_place(self):
        arguments = {"safety": "low"}
        safety.apply_safety_floor(_WAYPOINT_TOOL, arguments, safety.SafetyTier.HIGH)
        self.assertEqual({"safety": "low"}, arguments)


class SafetyFloorHookTest(unittest.TestCase):
    def _ctx(self) -> TurnContext:
        return TurnContext(
            turn=1, mode_name="script", safety_tier="high", goal="g",
            mcp_client=FakeMcpClient(), trace=FakeTrace(), history=[],
        )

    def test_hook_raises_a_below_floor_request_and_reports_it_in_history_and_trace(self):
        ctx = self._ctx()
        hook = safety.SafetyFloorHook(safety.SafetyTier.HIGH)
        action = ActionRequest(tool=_WAYPOINT_TOOL, arguments={"safety": "low"})

        new_action = hook.before_action(ctx, action)

        self.assertEqual("high", new_action.arguments["safety"])
        self.assertTrue(any("raised" in entry for entry in ctx.history))
        self.assertTrue(any(event["type"] == "safety_floor_applied" for event in ctx.trace.events))
        # The action's tool name must never change - only arguments may be modified (hooks/base.py's
        # own Hook.before_action contract).
        self.assertEqual(action.tool, new_action.tool)

    def test_hook_never_lowers_a_request_already_above_a_lesser_tiers_floor(self):
        ctx = self._ctx()
        hook = safety.SafetyFloorHook(safety.SafetyTier.BALANCED)
        action = ActionRequest(tool=_WAYPOINT_TOOL, arguments={"safety": "high"})

        new_action = hook.before_action(ctx, action)

        self.assertEqual("high", new_action.arguments["safety"])
        self.assertEqual([], ctx.history)  # nothing needed flooring - silent no-op, no false report

    def test_hook_is_completely_silent_at_low_tier(self):
        ctx = self._ctx()
        hook = safety.SafetyFloorHook(safety.SafetyTier.LOW)
        action = ActionRequest(tool=_WAYPOINT_TOOL, arguments={"safety": "low"})

        new_action = hook.before_action(ctx, action)

        self.assertEqual(action.arguments, new_action.arguments)
        self.assertEqual([], ctx.history)
        self.assertEqual([], ctx.trace.events)


if __name__ == "__main__":
    unittest.main()
