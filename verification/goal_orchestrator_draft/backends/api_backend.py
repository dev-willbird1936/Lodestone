"""DRAFT SKETCH - adapter over the real Anthropic Messages API call (run_loop() in
goal-orchestrator-milestone1.py, lines 574-659). Not runnable without the `anthropic` package and a
real API key - both confirmed absent on this machine per the real file's own module docstring.
Included for shape-completeness only: it demonstrates that the api backend's native `tools=` param
and multi-tool_use-block response ARE the batch mechanism script mode needs (design notes section
1.1) - no new orchestrator-side batching logic is required for this backend, only a different system
prompt (supplied by the active Mode) and this adapter converting response content blocks into the
same Decision shape CliBackend produces.
"""

from __future__ import annotations

from typing import Any

from ..decision_protocol import ActionRequest, Decision


class ApiBackend:
    """NOTE: unlike CliBackend, the api backend is naturally stateful across turns (the real
    run_loop() accumulates a native `messages=[...]` list of assistant/tool_result blocks, not the
    human-readable `history: list[str]` the cli backend replays from scratch each turn - see the
    real file's own comment on why the cli backend needs full-prompt-rebuild in the first place).
    This adapter keeps that native message list as its own internal state and accepts `history`
    only to satisfy the shared ModelBackend shape loop.py uses for both backends; a real
    integration should decide whether loop.py needs a second, backend-owned-state hook or whether
    this adapter alone is sufffient encapsulation.
    """

    def __init__(self, model_client: Any, model: str, effort: str, tools: list[dict[str, Any]]) -> None:
        self._client = model_client
        self._model = model
        self._effort = effort
        self._tools = tools
        self._messages: list[dict[str, Any]] = []

    def decide(self, system_prompt: str, history: list[str]) -> tuple[Decision, dict[str, Any]]:
        response = self._client.messages.create(
            model=self._model,
            max_tokens=1024,
            system=system_prompt,
            tools=self._tools,
            messages=self._messages,
            output_config={"effort": self._effort},
        )
        self._messages.append({"role": "assistant", "content": [b.model_dump() for b in response.content]})

        actions = [
            ActionRequest(tool=block.name, arguments=block.input)
            for block in response.content
            if block.type == "tool_use"
        ]
        done = not actions
        rationale = "".join(block.text for block in response.content if block.type == "text")
        return Decision(actions=actions, done=done, rationale=rationale), {"response": response}

    def record_tool_results(self, results: list[tuple[ActionRequest, dict[str, Any]]]) -> None:
        """Must be called by loop.py after dispatching a Decision's actions, so the next decide()
        call's native `messages=[...]` includes the matching tool_result blocks - the api backend's
        equivalent of CliBackend's history-replay. This is exactly why ModelBackend is a narrow
        `decide()`-only Protocol rather than loop.py reaching into backend-specific state directly:
        each backend manages its own turn-continuity mechanism behind that one shared method plus
        this backend-specific follow-up call.
        """
        raise NotImplementedError("sketch only - a real integration wires this to response tool_use_id mapping")
