"""DRAFT SKETCH - ModelBackend protocol. See goal-orchestrator-design-notes.md section 3.2."""

from __future__ import annotations

from typing import Any, Protocol

from ..decision_protocol import Decision


class ModelBackend(Protocol):
    def decide(self, system_prompt: str, history: list[str]) -> tuple[Decision, dict[str, Any]]:
        """Return (parsed Decision, raw envelope for tracing). loop.py treats every backend
        uniformly through this one method - it never branches on which backend is active, unlike
        the real file's current main() dispatching to run_loop() vs run_loop_cli() by name."""
        ...
