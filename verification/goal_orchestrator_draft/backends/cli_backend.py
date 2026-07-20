"""DRAFT SKETCH - adapter over the real call_claude_cli()/build_cli_prompt() pattern from
goal-orchestrator-milestone1.py (lines 452-486). A real integration would move call_claude_cli into
this module and call it directly; this sketch instead takes it as an injected callable so the sketch
has zero dependency on subprocess/the `claude` binary and can never accidentally shell out to
anything just by being imported.
"""

from __future__ import annotations

from typing import Any, Callable

from ..decision_protocol import Decision, DecisionParseError, parse_decision


class CliBackend:
    """Adapter: turns the existing claude-CLI-subprocess call into the ModelBackend shape loop.py
    expects.

    `call_fn(prompt) -> {"decision": <parsed JSON object>, "envelope": <cli response envelope>}`
    matches the real call_claude_cli()'s return shape exactly (see the real file's own
    `call_result["decision"]` / `call_result["envelope"]` usage in run_loop_cli(), lines 705, 737).

    `build_prompt_fn(system_prompt, history) -> str` matches the real build_cli_prompt()'s role -
    kept as a separate injected function (rather than this class rendering history itself) because
    the history-replay format is independent of which backend/mode produced it.
    """

    def __init__(
        self,
        call_fn: Callable[[str], dict[str, Any]],
        build_prompt_fn: Callable[[str, list[str]], str],
    ) -> None:
        self._call_fn = call_fn
        self._build_prompt_fn = build_prompt_fn

    def decide(self, system_prompt: str, history: list[str]) -> tuple[Decision, dict[str, Any]]:
        prompt = self._build_prompt_fn(system_prompt, history)
        call_result = self._call_fn(prompt)
        try:
            decision = parse_decision(call_result["decision"])
        except DecisionParseError:
            # Real integration: same "log explicitly, give the model another turn" handling the
            # real run_loop_cli() already applies to a non-dict/malformed decision (see its
            # "turn_no_action" trace event) - re-raising here so loop.py's turn handler can do that
            # logging with full turn context, rather than this adapter guessing at trace fields.
            raise
        return decision, call_result.get("envelope", {})
