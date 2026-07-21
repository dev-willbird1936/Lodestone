"""Regression tests for active script batching, safety polling, and backend selection."""

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1_modes", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = orchestrator
_SPEC.loader.exec_module(orchestrator)


class FakeMcpClient:
    def __init__(self):
        self.calls = []

    def invoke_capability(self, capability, arguments, version=None):
        self.calls.append((capability, arguments, version))
        return {"status": "ok", "output": {"health": 20}}


class ActiveGoalModesTest(unittest.TestCase):
    def test_script_batch_executes_in_order_and_can_finish(self):
        client = FakeMcpClient()
        decision = {
            "actions": [
                {"tool": "observe", "arguments": {"a": 1}},
                {"tool": "move", "arguments": {"b": 2}},
            ],
            "done": True,
            "boundaryReason": "goal confirmed",
            "rationale": "complete",
        }
        with tempfile.TemporaryDirectory() as directory:
            trace = orchestrator.TraceWriter(Path(directory) / "trace.jsonl")
            with mock.patch.object(orchestrator, "call_claude_cli", return_value={
                "decision": decision, "envelope": {"result": "ok"}
            }):
                result = orchestrator.run_loop_cli(
                    client,
                    [],
                    {
                        "observe": {"capability": "minecraft.player.state.read", "capabilityVersion": "1.0"},
                        "move": {"capability": "minecraft.player.move", "capabilityVersion": "1.0"},
                    },
                    "test goal",
                    None,
                    "low",
                    2,
                    trace,
                    mode="script",
                    intelligence="low",
                    safety="low",
                )
            trace.close()
        self.assertEqual("model_declared_done", result["stopReason"])
        self.assertEqual(
            ["minecraft.player.state.read", "minecraft.player.move"],
            [call[0] for call in client.calls],
        )

    def test_high_safety_observes_player_and_entities_before_decision(self):
        client = FakeMcpClient()
        with tempfile.TemporaryDirectory() as directory:
            trace = orchestrator.TraceWriter(Path(directory) / "trace.jsonl")
            with mock.patch.object(orchestrator, "call_claude_cli", return_value={
                "decision": {"tool": None, "arguments": {}, "done": True, "rationale": "verified"},
                "envelope": {"result": "ok"},
            }):
                orchestrator.run_loop_cli(
                    client, [], {}, "test goal", None, "high", 1, trace,
                    safety="high",
                )
            trace.close()
        self.assertEqual(
            ["minecraft.player.state.read", "minecraft.entity.nearby.read"],
            [call[0] for call in client.calls],
        )

    def test_auto_backend_uses_lowest_configured_p95(self):
        with mock.patch.object(orchestrator, "CODEX_EXE", "codex"), \
                mock.patch.object(orchestrator, "CLAUDE_EXE", "claude"), \
                mock.patch.object(orchestrator.shutil, "which", side_effect=lambda name: name), \
                mock.patch.dict(os.environ, {
                    "LODESTONE_CODEX_P95_MS": "900",
                    "LODESTONE_CLAUDE_P95_MS": "500",
                }, clear=False):
            self.assertEqual("claude-cli", orchestrator.select_cli_backend("auto"))

    def test_invalid_latency_hint_does_not_crash_auto_selection(self):
        with mock.patch.object(orchestrator, "CODEX_EXE", "codex"), \
                mock.patch.object(orchestrator, "CLAUDE_EXE", "claude"), \
                mock.patch.object(orchestrator.shutil, "which", side_effect=lambda name: name), \
                mock.patch.dict(os.environ, {
                    "LODESTONE_CODEX_P95_MS": "not-a-number",
                    "LODESTONE_CLAUDE_P95_MS": "400",
                }, clear=False):
            self.assertEqual("claude-cli", orchestrator.select_cli_backend("auto"))

    def test_codex_cli_does_not_pin_a_model_by_default(self):
        captured = {}

        def fake_run(command, **_kwargs):
            captured["command"] = command
            output = Path(command[command.index("--output-last-message") + 1])
            output.write_text('{"done":true,"tool":null}', encoding="utf-8")
            return SimpleNamespace(returncode=0, stdout="")

        with mock.patch.object(orchestrator.subprocess, "run", side_effect=fake_run):
            result = orchestrator.call_codex_cli("prompt", None, "low", 1)
        self.assertNotIn("--model", captured["command"])
        self.assertTrue(result["decision"]["done"])

    def test_claude_cli_does_not_pin_a_model_by_default(self):
        completed = SimpleNamespace(
            returncode=0,
            stdout='{"is_error":false,"result":"{\\"done\\":true,\\"tool\\":null}"}',
        )
        with mock.patch.object(orchestrator.subprocess, "run", return_value=completed) as called:
            result = orchestrator.call_claude_cli("prompt", None, "low", 1)
        self.assertNotIn("--model", called.call_args.args[0])
        self.assertTrue(result["decision"]["done"])

    def test_high_policy_cannot_be_weakened_by_typed_action(self):
        result = orchestrator.apply_policy_floor(
            {"targetX": 1, "intelligence": "low", "safety": "medium"},
            {"properties": {"targetX": {"type": "integer"}, "intelligence": {}, "safety": {}}},
            "high",
            "high",
        )
        self.assertEqual("high", result["intelligence"])
        self.assertEqual("high", result["safety"])

    def test_generic_completion_fails_closed_without_independent_verifier(self):
        client = FakeMcpClient()
        with tempfile.TemporaryDirectory() as directory:
            trace = orchestrator.TraceWriter(Path(directory) / "trace.jsonl")
            result = orchestrator.verify_generic_goal_state(
                client, trace, {"stopReason": "model_declared_done"}
            )
            trace.close()
        self.assertFalse(result["passed"])
        self.assertTrue(result["freshStateReadable"])

    def test_generic_completion_accepts_positive_independent_verifier(self):
        client = FakeMcpClient()
        with tempfile.TemporaryDirectory() as directory:
            trace = orchestrator.TraceWriter(Path(directory) / "trace.jsonl")
            result = orchestrator.verify_generic_goal_state(
                client,
                trace,
                {"stopReason": "model_declared_done"},
                {"verified": True, "rationale": "fresh inventory proves it"},
            )
            trace.close()
        self.assertTrue(result["passed"])


if __name__ == "__main__":
    unittest.main()
