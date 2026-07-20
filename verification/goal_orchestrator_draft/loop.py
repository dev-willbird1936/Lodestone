"""DRAFT SKETCH - generalized loop core. Combines the control flow currently duplicated between
run_loop() (api backend, lines 574-659) and run_loop_cli() (cli backend, lines 662-813) in
goal-orchestrator-milestone1.py into one function parameterized by (backend, mode, hooks) instead of
two near-duplicate implementations. See goal-orchestrator-design-notes.md sections 1.5 and 3.2/3.3.

NOT INTEGRATED: this module has zero dependency on the live orchestrator file (it does not import
LodestoneMcpClient/TraceWriter/build_tool_catalog), so it cannot affect that file just by existing.
A real integration would import those from wherever mcp_client.py/catalog.py/trace.py land per the
module-split table in the design notes, and pass real instances into run() below.
"""

from __future__ import annotations

from typing import Any

from .decision_protocol import Decision
from .hooks.base import Hook, HookSignal, TurnContext
from .modes.base import Mode, ModeValidationError

DEFAULT_MAX_BATCH_SIZE = 12


def run(
    mcp_client: Any,
    backend: Any,  # backends.base.ModelBackend
    mode: Mode,
    hooks: list[Hook],
    tools: list[dict[str, Any]],
    tool_catalog_text: str,
    dispatch: dict[str, dict[str, Any]],
    goal: str,
    max_turns: int,
    trace: Any,
    safety_tier_name: str = "low",
    safety_addendum: str = "",
    max_batch_size: int = DEFAULT_MAX_BATCH_SIZE,
) -> dict[str, Any]:
    """One loop, both modes, both backends. Mirrors run_loop_cli()'s real control flow (parse
    decision -> validate -> dispatch -> record -> repeat) but generalizes "dispatch" from "exactly
    one tool call" to "a fail-fast-executed batch of one or more", and factors the
    CAPABILITY_QUARANTINED special case (and anything a safety tier needs) out into hooks instead of
    inline branches.
    """
    history: list[str] = []
    ctx = TurnContext(
        turn=0, mode_name=mode.name, safety_tier=safety_tier_name, goal=goal,
        mcp_client=mcp_client, trace=trace, history=history,
    )

    for turn in range(1, max_turns + 1):
        ctx.turn = turn
        for hook in hooks:
            hook.before_turn(ctx)

        system_prompt = mode.system_prompt(goal, tool_catalog_text, safety_addendum)
        try:
            decision, envelope = backend.decide(system_prompt, history)
        except Exception as exc:  # noqa: BLE001 - mirrors the real file's own deliberately broad
            # catch around a model call (auth/timeout/parse failures are real, reportable blockers,
            # not bugs to swallow) - see run_loop_cli()'s MAX_CONSECUTIVE_CALL_FAILURES handling,
            # which a real integration should port here rather than aborting on the first failure.
            trace.record_event("model_call_failed", turn=turn, exceptionType=type(exc).__name__, exceptionMessage=str(exc))
            return {"stopReason": "model_call_failed", "turns": turn - 1, "exceptionType": type(exc).__name__, "exceptionMessage": str(exc)}

        try:
            mode.validate(decision)
        except ModeValidationError as exc:
            trace.record_event("turn_mode_violation", turn=turn, reason=str(exc))
            history.append(f"Turn {turn}: your response violated {mode.name} mode's contract - {exc}. Try again.")
            continue

        trace.record_event(
            "loop_decision", turn=turn, actionCount=len(decision.actions),
            boundaryReason=decision.boundary_reason, done=decision.done,
        )

        if decision.done:
            return {"stopReason": "model_declared_done", "turns": turn, "finalRationale": decision.rationale}

        actions = decision.actions
        if len(actions) > max_batch_size:
            trace.record_event("batch_truncated", turn=turn, requested=len(actions), cap=max_batch_size)
            history.append(
                f"Turn {turn}: you requested {len(actions)} actions but the harness cap is "
                f"{max_batch_size}; only the first {max_batch_size} ran. Re-observe and continue "
                "with a fresh batch."
            )
            actions = actions[:max_batch_size]

        executed = 0
        stopped_early_reason: str | None = None

        for action in actions:
            for hook in hooks:
                action = hook.before_action(ctx, action)

            mapping = dispatch.get(action.tool)
            if mapping is None:
                result: Any = {
                    "status": "error",
                    "error": {"code": "UNKNOWN_TOOL", "message": f"no dispatch mapping for tool '{action.tool}'"},
                    "output": None,
                }
            else:
                try:
                    result = mcp_client.invoke_capability(
                        mapping["capability"], action.arguments, mapping.get("capabilityVersion")
                    )
                except Exception as exc:  # noqa: BLE001 - an unexpected transport/parse failure is a
                    # real, reportable batch-stopping failure, not a bug to swallow or let crash the
                    # whole run() - mirrors the fail-fast contract below, just for the raise case.
                    result = {
                        "status": "error",
                        "error": {"code": "INVOKE_RAISED", "message": f"{type(exc).__name__}: {exc}"},
                        "output": None,
                    }
            trace.record_tool_call(turn, action.tool, action.arguments, result)
            executed += 1

            signal = HookSignal.CONTINUE
            for hook in hooks:
                signal = hook.after_action(ctx, action, result)
                if signal != HookSignal.CONTINUE:
                    break

            # Fail-fast: a batch is only ever executed on the assumption that everything up to the
            # current step succeeded (design notes section 1.5, point 2) - the model's own boundary
            # judgment is explicitly told it doesn't need to guard against this itself. Success must
            # be a positively-confirmed {"status": "ok", ...} dict, matching the same
            # isinstance(x, dict) and x.get("status") == "ok" pattern used by safety.py's
            # _perform_forced_check - any other shape (None, a malformed response, a non-dict) is
            # treated as a failure, not silently as an implicit success.
            succeeded = isinstance(result, dict) and result.get("status") == "ok"
            if not succeeded:
                error_detail = result.get("error") if isinstance(result, dict) else result
                stopped_early_reason = f"action {executed}/{len(actions)} ('{action.tool}') failed: {error_detail!r}"
                break
            if signal == HookSignal.ABORT_BATCH:
                stopped_early_reason = f"a hook requested abort after action {executed}/{len(actions)} ('{action.tool}')"
                break

        trace.record_script_batch(
            turn=turn, requested=len(decision.actions), executed=executed,
            boundaryReason=decision.boundary_reason, stoppedEarly=stopped_early_reason,
        )
        history.append(
            f"Turn {turn}: requested {len(decision.actions)} action(s), executed {executed}"
            + (f", stopped early - {stopped_early_reason}" if stopped_early_reason else "")
            + f". boundaryReason={decision.boundary_reason!r} rationale={decision.rationale!r}"
        )

        for hook in hooks:
            hook.after_turn(ctx)

    return {"stopReason": "max_turns_reached", "turns": max_turns}
