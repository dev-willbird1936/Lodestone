"""DRAFT SKETCH - not integrated into the live orchestrator.

Unified model-decision schema shared by both modes (realtime, script) and (eventually) both
backends (cli, api). See goal-orchestrator-design-notes.md section 1.4 for the rationale: this
parser accepts EITHER the legacy singular shape realtime mode already uses in production
(`goal-orchestrator-milestone1.py`'s `CLI_SYSTEM_PROMPT_TEMPLATE`, lines 420-423) or the new batch
shape script mode introduces, so realtime's already-proven prompt/schema never has to change to
ship script mode.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ActionRequest:
    """One subaction: a generated tool name (already '.'->'_' converted, matching
    capability_id_to_tool_name() in the real file) plus its arguments."""

    tool: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass
class Decision:
    """Normalized form of a model turn's reply, regardless of which raw JSON shape produced it."""

    actions: list[ActionRequest]
    done: bool
    rationale: str = ""
    boundary_reason: str = ""

    @property
    def is_batch(self) -> bool:
        return len(self.actions) > 1


class DecisionParseError(ValueError):
    """Raised when a model's JSON reply cannot be normalized into a Decision. Callers should treat
    this the same way the real run_loop_cli() treats a non-dict/malformed decision today: log it
    explicitly (trace.record_event("turn_no_action", ...)) and give the model another turn to
    correct itself, rather than crashing the run - see the real file's own comment on why a silent
    `continue` on a bad turn was flagged as a bug (an untraceable trace).
    """


def parse_decision(raw: dict[str, Any]) -> Decision:
    """Normalize either shape into one Decision:

    Legacy singular (today's realtime cli-backend protocol, unchanged):
        {"tool": "<name-or-null>", "arguments": {...}, "done": bool, "rationale": "..."}

    New batch (script mode; also a strict superset a realtime-shaped reply would never produce):
        {"actions": [{"tool": "<name>", "arguments": {...}}, ...],
         "boundaryReason": "...", "done": bool, "rationale": "..."}
    """
    if not isinstance(raw, dict):
        raise DecisionParseError(f"decision must be a JSON object, got {type(raw).__name__}")

    done = bool(raw.get("done"))
    rationale = str(raw.get("rationale", ""))
    boundary_reason = str(raw.get("boundaryReason", ""))

    if "actions" in raw:
        raw_actions = raw.get("actions")
        if not isinstance(raw_actions, list):
            raise DecisionParseError(f"'actions' must be a list, got {type(raw_actions).__name__}")
        actions: list[ActionRequest] = []
        for entry in raw_actions:
            if not isinstance(entry, dict) or not entry.get("tool"):
                raise DecisionParseError(f"each action must be an object with a non-empty 'tool' field: {entry!r}")
            actions.append(ActionRequest(tool=str(entry["tool"]), arguments=dict(entry.get("arguments") or {})))
        return Decision(actions=actions, done=done, rationale=rationale, boundary_reason=boundary_reason)

    # Legacy singular shape - realtime mode's existing, live-verified protocol, byte-for-byte
    # unchanged behavior: no tool name (and not done) means "no action this turn", exactly like
    # the real run_loop_cli()'s `if not tool_name:` branch (line 758) treats it today.
    tool_name = raw.get("tool")
    if not tool_name:
        return Decision(actions=[], done=done, rationale=rationale, boundary_reason=boundary_reason)
    arguments = dict(raw.get("arguments") or {})
    return Decision(
        actions=[ActionRequest(tool=str(tool_name), arguments=arguments)],
        done=done, rationale=rationale, boundary_reason=boundary_reason,
    )
