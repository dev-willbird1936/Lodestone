"""DRAFT SKETCH - Mode protocol. See goal-orchestrator-design-notes.md section 3.2 (table row for
modes/base.py|realtime.py|script.py) and section 3.4 (why script-mode-only changes should never
need to touch this file or realtime.py).
"""

from __future__ import annotations

from typing import Protocol

from ..decision_protocol import Decision


class ModeValidationError(ValueError):
    """Raised when a parsed Decision violates the active mode's own batching contract."""


class Mode(Protocol):
    name: str
    max_batch_size_hint: int  # advisory, embedded into the prompt text; loop.py's own hard cap
    # (DEFAULT_MAX_BATCH_SIZE) is the actual enforcement point, not this hint.

    def system_prompt(self, goal: str, tool_catalog_text: str, safety_addendum: str) -> str: ...

    def validate(self, decision: Decision) -> None:
        """Raise ModeValidationError for a decision that violates this mode's own contract (e.g.
        RealtimeMode receiving len(actions) > 1, or ScriptMode receiving zero actions without
        done=true). loop.py calls this right after parsing and before dispatch; a violation should
        be treated the same way the real run_loop_cli() treats any other malformed decision today -
        logged explicitly and retried next turn, not a hard crash of the whole run."""
        ...
