"""DRAFT SKETCH - extracted, generalized version of the CAPABILITY_QUARANTINED auto-reconcile
logic that lives inline in the real orchestrator's run_loop_cli() today (see
goal-orchestrator-milestone1.py, lines 785-812, and the module docstring's "CAPABILITY_QUARANTINED
recovery" section). Behavior is unchanged - it is now an always-installed Hook instead of a special
case inside the loop function, so loop.py itself does not need to know this recovery exists at all.
See goal-orchestrator-design-notes.md sections 3.3/3.4.
"""

from __future__ import annotations

import json
from typing import Any

from ..decision_protocol import ActionRequest
from .base import BaseHook, HookSignal, TurnContext


class QuarantineRecoveryHook(BaseHook):
    """Always installed regardless of mode or safety tier - this is harness-owned, deterministic
    recovery, not a safety-tier feature and not something the model should have to notice and pick
    out of its own tool catalog (mirroring the real file's own comment on why
    minecraft.session.reconcile is deliberately NOT a model-callable tool)."""

    def after_action(self, ctx: TurnContext, action: ActionRequest, result: dict[str, Any]) -> HookSignal:
        error = result.get("error") if isinstance(result, dict) else None
        if not (isinstance(error, dict) and error.get("code") == "CAPABILITY_QUARANTINED"):
            return HookSignal.CONTINUE

        reconcile_result = ctx.mcp_client.invoke_capability("minecraft.session.reconcile", {})
        ctx.trace.record_event(
            "auto_reconcile", turn=ctx.turn, triggeredByTool=action.tool, result=reconcile_result
        )
        ctx.history.append(
            f"Turn {ctx.turn}: the harness automatically called minecraft.session.reconcile after "
            f"your last tool hit CAPABILITY_QUARANTINED. Result: "
            f"{json.dumps(reconcile_result, separators=(',', ':'), default=str)}. If cleared is "
            "true, mutating tools (move/interact/navigate/etc) should work again - try your last "
            "action again or re-observe first if you're unsure of the current state. If cleared is "
            "false, the adapter could not confirm it was safe to clear; re-observe state and "
            "consider a different approach."
        )
        # The rest of the batch was planned blind relative to a state the model hasn't seen the
        # reconcile outcome for yet - same fail-fast reasoning loop.py already applies to any other
        # failed action (design notes section 1.5, points 2/3), just triggered from a hook instead
        # of inline in the loop.
        return HookSignal.ABORT_BATCH
