"""Unit tests for run_loop_cli's turn_done_contradiction guard: a decision with "done": true AND a
non-null "tool" is exactly the failure mode CLI_SYSTEM_PROMPT_TEMPLATE's completion-discipline hard
rule targets, live-evidenced TWICE - trace-f0679eb8edb3.jsonl turn 39 (before the hard rule existed),
and trace-bb1f682f27cb.jsonl turn 22 (AFTER the hard rule was added to the prompt, with the model's
own rationale saying "...so recheck instead" while still setting "done": true in the same decision).
The prompt-level rule alone was demonstrated live to not reliably stop the model from emitting this
contradiction, so run_loop_cli itself now refuses to honor "done" on a turn where a tool was also
named: it runs the requested tool call, appends a corrective note to history, and forces another turn
instead of stopping outright.

Run with: python verification/test_goal_orchestrator_milestone1_done_contradiction.py
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = orchestrator  # see test_goal_orchestrator_milestone1_history.py for why
_SPEC.loader.exec_module(orchestrator)


class FakeMcpClient:
    """Duck-types LodestoneMcpClient.invoke_capability just enough for run_loop_cli: a health read
    that always reports full health (so the reactive safety guard never fires and adds noise to the
    trace), and a canned "ok" result for whatever tool the decision names.
    """

    def __init__(self):
        self.calls = []

    def invoke_capability(self, capability, input_payload, capability_version=None, dry_run=False):
        self.calls.append((capability, input_payload))
        if capability == orchestrator.HEALTH_READ_CAPABILITY:
            return {"status": "ok", "error": None, "output": {"health": 20.0}}
        return {"status": "ok", "error": None, "output": {"player": {}, "slots": [], "selectedSlot": 0}}


class RunLoopCliDoneContradictionTest(unittest.TestCase):
    def _run(self, decisions):
        """decisions: one dict per turn, returned in order by a faked call_claude_cli."""
        state = {"n": 0}

        def fake_call_claude_cli(prompt, model, effort, timeout_s):
            index = state["n"]
            state["n"] += 1
            return {"decision": decisions[index], "envelope": {}}

        original = orchestrator.call_claude_cli
        orchestrator.call_claude_cli = fake_call_claude_cli
        try:
            with tempfile.TemporaryDirectory() as tmp:
                trace = orchestrator.TraceWriter(Path(tmp) / "trace.jsonl")
                mcp_client = FakeMcpClient()
                tools, dispatch = orchestrator.build_tool_catalog([{
                    "id": "minecraft.inventory.read",
                    "availability": "available",
                    "version": "1.0",
                    "documentation": "Read inventory.",
                    "inputSchema": {"type": "object", "properties": {}},
                }])
                result = orchestrator.run_loop_cli(
                    mcp_client, tools, dispatch, "mine one log", "sonnet", "low",
                    max_turns=len(decisions), trace=trace,
                )
                trace._fh.close()
                trace_lines = (Path(tmp) / "trace.jsonl").read_text(encoding="utf-8").splitlines()
                return result, [json.loads(line) for line in trace_lines]
        finally:
            orchestrator.call_claude_cli = original

    def test_contradictory_done_does_not_stop_the_loop(self):
        decisions = [
            {"tool": "minecraft_inventory_read", "arguments": {}, "done": True,
             "rationale": "log wasn't in inventory yet, so recheck instead"},
            {"tool": None, "arguments": {}, "done": True, "rationale": "confirmed on recheck"},
        ]
        result, _trace_events = self._run(decisions)
        self.assertEqual("model_declared_done", result["stopReason"])
        self.assertEqual(2, result["turns"])  # did NOT stop at turn 1's contradictory decision

    def test_contradiction_is_logged_and_the_tool_still_runs(self):
        decisions = [
            {"tool": "minecraft_inventory_read", "arguments": {}, "done": True, "rationale": "recheck instead"},
            {"tool": None, "arguments": {}, "done": True, "rationale": "done for real"},
        ]
        _result, trace_events = self._run(decisions)
        contradiction_events = [e for e in trace_events if e.get("event") == "turn_done_contradiction"]
        self.assertEqual(1, len(contradiction_events))
        self.assertEqual(1, contradiction_events[0]["turn"])
        tool_calls = [e for e in trace_events if e.get("event") == "tool_call"]
        self.assertEqual(1, len(tool_calls))
        self.assertEqual("minecraft_inventory_read", tool_calls[0]["toolName"])

    def test_clean_done_with_no_tool_still_stops_immediately(self):
        # Regression guard: an ordinary, non-contradictory done: true (tool: null) must still stop
        # the loop on turn 1 exactly as before - the new guard must not change that path.
        decisions = [{"tool": None, "arguments": {}, "done": True, "rationale": "genuinely done"}]
        result, trace_events = self._run(decisions)
        self.assertEqual("model_declared_done", result["stopReason"])
        self.assertEqual(1, result["turns"])
        self.assertEqual([], [e for e in trace_events if e.get("event") == "turn_done_contradiction"])


if __name__ == "__main__":
    unittest.main()
