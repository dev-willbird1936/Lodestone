"""DRAFT SKETCH tests - script mode's batch decision protocol and loop.py's fail-fast batch
execution (design notes section 1.5). Run with:

    python -m unittest verification.goal_orchestrator_draft.tests.test_script_mode_batch

(from the repo root, or via `python -m unittest discover -s verification/goal_orchestrator_draft/tests
-t verification`).
"""

from __future__ import annotations

import os
import sys
import unittest

_VERIFICATION_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _VERIFICATION_DIR not in sys.path:
    sys.path.insert(0, _VERIFICATION_DIR)

from goal_orchestrator_draft import loop  # noqa: E402
from goal_orchestrator_draft.decision_protocol import ActionRequest, Decision, parse_decision  # noqa: E402
from goal_orchestrator_draft.modes.base import ModeValidationError  # noqa: E402
from goal_orchestrator_draft.modes.script import ScriptMode  # noqa: E402
from goal_orchestrator_draft.tests.fakes import FakeBackend, FakeMcpClient, FakeTrace  # noqa: E402

_DISPATCH = {
    "minecraft_goal_navigation_safe-waypoint": {"capability": "minecraft.goal.navigation.safe-waypoint"},
    "minecraft_world_blocks_interact": {"capability": "minecraft.world.blocks.interact"},
    "minecraft_inventory_read": {"capability": "minecraft.inventory.read"},
}


class ScriptModeValidateTest(unittest.TestCase):
    def test_a_batch_with_at_least_one_action_is_valid(self):
        ScriptMode().validate(Decision(actions=[ActionRequest(tool="x", arguments={})], done=False))

    def test_done_with_no_actions_is_valid(self):
        ScriptMode().validate(Decision(actions=[], done=True, rationale="goal complete"))

    def test_zero_actions_and_not_done_is_rejected(self):
        with self.assertRaises(ModeValidationError):
            ScriptMode().validate(Decision(actions=[], done=False))


class ScriptModePromptTest(unittest.TestCase):
    def test_prompt_includes_the_owners_two_worked_boundary_examples(self):
        # The design doc's script-mode section is explicit that the worked examples are "most of
        # what makes the boundary guidance usable" - regression-guard that they actually ship in
        # the real prompt text, not just in the design doc.
        prompt = ScriptMode().system_prompt(goal="mine one log and craft an axe", tool_catalog_text="", safety_addendum="")
        self.assertIn('"mine one log and craft an axe"', prompt)
        self.assertIn("mine gravel until you get flint", prompt)
        self.assertIn("boundaryReason", prompt)


class DecisionProtocolBatchShapeTest(unittest.TestCase):
    def test_batch_shape_parses_multiple_actions_in_order(self):
        decision = parse_decision({
            "actions": [
                {"tool": "a", "arguments": {"x": 1}},
                {"tool": "b", "arguments": {}},
            ],
            "boundaryReason": "batch complete",
            "done": False,
            "rationale": "two steps",
        })
        self.assertEqual(["a", "b"], [action.tool for action in decision.actions])
        self.assertEqual("batch complete", decision.boundary_reason)
        self.assertTrue(decision.is_batch)

    def test_legacy_singular_shape_still_normalizes_to_one_action(self):
        decision = parse_decision({"tool": "a", "arguments": {"x": 1}, "done": False, "rationale": "r"})
        self.assertEqual(1, len(decision.actions))
        self.assertFalse(decision.is_batch)


class ScriptModeBatchFailFastTest(unittest.TestCase):
    """The task's required coverage: a script-mode batch that fails fast mid-batch."""

    def test_batch_stops_at_first_failed_action_and_never_runs_the_rest(self):
        decision = Decision(
            actions=[
                ActionRequest(tool="minecraft_goal_navigation_safe-waypoint", arguments={"x": 1, "y": 2, "z": 3}),
                ActionRequest(tool="minecraft_world_blocks_interact", arguments={"action": "mine"}),
                ActionRequest(tool="minecraft_inventory_read", arguments={}),
            ],
            done=False,
            rationale="mine one log",
            boundary_reason="batching navigate+mine, will re-observe inventory before crafting",
        )
        backend = FakeBackend([decision])
        mcp_client = FakeMcpClient({
            "minecraft.goal.navigation.safe-waypoint": {"status": "ok", "output": {"reachedTarget": True}},
            "minecraft.world.blocks.interact": {
                "status": "error",
                "error": {"code": "BLOCK_UNREACHABLE", "message": "target no longer exists"},
                "output": None,
            },
            # minecraft.inventory.read is deliberately left unscripted - fail-fast must mean it is
            # NEVER called, not "called and its scripted-default result ignored".
        })
        trace = FakeTrace()

        result = loop.run(
            mcp_client=mcp_client,
            backend=backend,
            mode=ScriptMode(),
            hooks=[],
            tools=[],
            tool_catalog_text="",
            dispatch=_DISPATCH,
            goal="mine one log and craft an axe",
            max_turns=1,
            trace=trace,
        )

        self.assertEqual("max_turns_reached", result["stopReason"])

        # Exactly two capabilities were ever invoked - the third (inventory read) never ran.
        invoked_capabilities = [call[0] for call in mcp_client.calls]
        self.assertEqual(
            ["minecraft.goal.navigation.safe-waypoint", "minecraft.world.blocks.interact"],
            invoked_capabilities,
        )

        self.assertEqual(2, len(trace.tool_calls))

        self.assertEqual(1, len(trace.script_batches))
        batch = trace.script_batches[0]
        self.assertEqual(3, batch["requested"])
        self.assertEqual(2, batch["executed"])
        self.assertIsNotNone(batch["stoppedEarly"])
        self.assertIn("minecraft_world_blocks_interact", batch["stoppedEarly"])
        self.assertIn("action 2/3", batch["stoppedEarly"])

        # The batch's own boundaryReason/rationale (the model's stated reasoning for the batch it
        # planned) is preserved in the trace event regardless of the fail-fast stop, so a real
        # implementer can tell "did the model plan sensibly, then hit real bad luck" apart from "did
        # the model plan badly in the first place".
        self.assertEqual(decision.boundary_reason, batch["boundaryReason"])

    def test_batch_stops_fast_when_invoke_capability_returns_a_non_dict_result(self):
        # Regression test for a confirmed bug: a non-dict result (e.g. None, or any malformed
        # duck-typed response) from mcp_client.invoke_capability() must NOT be silently treated as
        # an implicit success. A naive `isinstance(result, dict) and result.get("status") != "ok"`
        # failure check is False for a non-dict result and lets the batch continue on an unconfirmed
        # action - exactly the "silently continue on a stale plan" behavior fail-fast forbids.
        decision = Decision(
            actions=[
                ActionRequest(tool="minecraft_goal_navigation_safe-waypoint", arguments={"x": 1, "y": 2, "z": 3}),
                ActionRequest(tool="minecraft_world_blocks_interact", arguments={"action": "mine"}),
                ActionRequest(tool="minecraft_inventory_read", arguments={}),
            ],
            done=False,
            rationale="mine one log",
            boundary_reason="batching navigate+mine, will re-observe inventory before crafting",
        )
        backend = FakeBackend([decision])
        mcp_client = FakeMcpClient({
            "minecraft.goal.navigation.safe-waypoint": {"status": "ok", "output": {"reachedTarget": True}},
            # A malformed/non-dict response - e.g. a transport layer returning None on a decode
            # failure - must be treated as a failure, not skipped over.
            "minecraft.world.blocks.interact": [None],
        })
        trace = FakeTrace()

        result = loop.run(
            mcp_client=mcp_client, backend=backend, mode=ScriptMode(), hooks=[], tools=[],
            tool_catalog_text="", dispatch=_DISPATCH, goal="mine one log and craft an axe",
            max_turns=1, trace=trace,
        )

        self.assertEqual("max_turns_reached", result["stopReason"])
        invoked_capabilities = [call[0] for call in mcp_client.calls]
        self.assertEqual(
            ["minecraft.goal.navigation.safe-waypoint", "minecraft.world.blocks.interact"],
            invoked_capabilities,
        )
        self.assertEqual(1, len(trace.script_batches))
        batch = trace.script_batches[0]
        self.assertEqual(2, batch["executed"])
        self.assertIsNotNone(batch["stoppedEarly"])

    def test_batch_stops_fast_when_invoke_capability_raises(self):
        # Regression test: an unexpected exception from invoke_capability() (transport/parse
        # failure) must produce a fail-fast batch stop, not crash the whole run().
        decision = Decision(
            actions=[ActionRequest(tool="minecraft_inventory_read", arguments={})],
            done=False, rationale="check inventory", boundary_reason="single observation",
        )

        class RaisingMcpClient:
            calls: list[tuple[str, dict, str | None]] = []

            def invoke_capability(self, capability, input_payload, capability_version=None):
                self.calls.append((capability, input_payload, capability_version))
                raise ConnectionError("loopback socket reset")

        backend = FakeBackend([decision])
        trace = FakeTrace()

        result = loop.run(
            mcp_client=RaisingMcpClient(), backend=backend, mode=ScriptMode(), hooks=[], tools=[],
            tool_catalog_text="", dispatch=_DISPATCH, goal="check inventory", max_turns=1, trace=trace,
        )

        self.assertEqual("max_turns_reached", result["stopReason"])
        self.assertEqual(1, len(trace.script_batches))
        self.assertIsNotNone(trace.script_batches[0]["stoppedEarly"])
        self.assertIn("INVOKE_RAISED", trace.script_batches[0]["stoppedEarly"])

    def test_a_fully_successful_batch_executes_every_action(self):
        decision = Decision(
            actions=[
                ActionRequest(tool="minecraft_inventory_read", arguments={}),
                ActionRequest(tool="minecraft_goal_navigation_safe-waypoint", arguments={"x": 0, "y": 0, "z": 0}),
            ],
            done=False,
            rationale="observe then move",
            boundary_reason="both steps are safe to plan together",
        )
        backend = FakeBackend([decision])
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": {"status": "ok", "output": {"slots": []}},
            "minecraft.goal.navigation.safe-waypoint": {"status": "ok", "output": {"reachedTarget": True}},
        })
        trace = FakeTrace()

        loop.run(
            mcp_client=mcp_client, backend=backend, mode=ScriptMode(), hooks=[], tools=[],
            tool_catalog_text="", dispatch=_DISPATCH, goal="observe then move", max_turns=1, trace=trace,
        )

        self.assertEqual(2, len(mcp_client.calls))
        self.assertEqual(1, len(trace.script_batches))
        self.assertIsNone(trace.script_batches[0]["stoppedEarly"])
        self.assertEqual(2, trace.script_batches[0]["executed"])


if __name__ == "__main__":
    unittest.main()
