"""DRAFT SKETCH test doubles shared across this package's unit tests - a fake MCP client, a fake
model backend, and a fake trace sink. None of these touch a network socket, a subprocess, or a live
Minecraft client; every test built on them is safe to run anywhere python is available.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..decision_protocol import Decision


class FakeMcpClient:
    """Records every invoke_capability() call and returns pre-scripted results, matching the real
    LodestoneMcpClient.invoke_capability(capability, input_payload, capability_version=None) shape
    (see decision_protocol.py / loop.py's own docstrings for that contract).

    `responses` maps a capability id to either:
      - a single result dict, reused for every call to that capability, or
      - a list of result dicts, popped off in call order - one per successive call to that
        capability - used to script a sequence of poll results (e.g. an inventory-watch hook's
        baseline poll, then a later poll showing a new item).
    Any capability with no scripted response returns a generic {"status": "ok", "output": {}} so a
    test only has to script the capabilities it actually cares about.
    """

    def __init__(self, responses: dict[str, Any] | None = None) -> None:
        self.responses: dict[str, Any] = dict(responses or {})
        self.calls: list[tuple[str, dict[str, Any], str | None]] = []

    def invoke_capability(
        self, capability: str, input_payload: dict[str, Any], capability_version: str | None = None
    ) -> dict[str, Any]:
        self.calls.append((capability, dict(input_payload), capability_version))
        scripted = self.responses.get(capability)
        if isinstance(scripted, list):
            if not scripted:
                return {"status": "ok", "error": None, "output": {}}
            return scripted.pop(0)
        if scripted is not None:
            return scripted
        return {"status": "ok", "error": None, "output": {}}


class FakeBackend:
    """A backends.base.ModelBackend that returns pre-scripted Decisions in order, one per decide()
    call - lets a test script an exact multi-turn conversation without a real model, subprocess, or
    API key. Raises loudly (rather than looping or returning a bogus default) if decide() is called
    more times than a test scripted for, since that always means the test's own turn-count
    assumption was wrong.
    """

    def __init__(self, decisions: list[Decision]) -> None:
        self._decisions = list(decisions)
        self.prompts_seen: list[tuple[str, list[str]]] = []

    def decide(self, system_prompt: str, history: list[str]) -> tuple[Decision, dict[str, Any]]:
        self.prompts_seen.append((system_prompt, list(history)))
        if not self._decisions:
            raise AssertionError("FakeBackend.decide() called more times than decisions were scripted")
        return self._decisions.pop(0), {"fake": True}


@dataclass
class FakeTrace:
    """Collects every trace call into plain in-memory lists instead of writing NDJSON to disk -
    tests assert directly against these lists rather than parsing a trace file, matching the
    "black box for native goals" lesson noted about the real evidence traces (see
    lodestone-benchmark-evidence-staleness-trap in project memory) - test assertions here read the
    orchestrator's own in-process state, not stale re-parsed files.
    """

    events: list[dict[str, Any]] = field(default_factory=list)
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    script_batches: list[dict[str, Any]] = field(default_factory=list)

    def record_event(self, event_type: str, **fields: Any) -> None:
        self.events.append({"type": event_type, **fields})

    def record_tool_call(self, turn: int, tool: str, arguments: dict[str, Any], result: dict[str, Any]) -> None:
        self.tool_calls.append({"turn": turn, "tool": tool, "arguments": arguments, "result": result})

    def record_script_batch(self, **fields: Any) -> None:
        self.script_batches.append(fields)
