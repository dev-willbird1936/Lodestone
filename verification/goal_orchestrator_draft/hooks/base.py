"""DRAFT SKETCH - the extension point that keeps future orchestrator features from colliding in
one file. See goal-orchestrator-design-notes.md section 3.3.

loop.py drives a small ordered list of Hook instances at four points per turn/action and has NO
knowledge of what any installed hook does - safety tiers, quarantine recovery, and any future
feature (a stuck-loop detector, a cost governor, a notifier) are all just "a new file implementing
Hook, registered in the composition root". This file only defines the protocol itself; it should
change rarely, and only when the loop genuinely needs a new extension point every hook-implementer
would benefit from.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any, Protocol

from ..decision_protocol import ActionRequest


class HookSignal(enum.Enum):
    """Returned by after_action() to tell loop.py's fail-fast batch executor what to do next."""

    CONTINUE = "continue"
    ABORT_BATCH = "abort_batch"  # stop the rest of this batch (quarantine recovery, a future
    # emergency-stop hook, etc.) - the model sees exactly what ran/failed/never-ran next turn.
    FORCE_REOBSERVE = "force_reobserve"  # ask the safety layer to run its reactive re-check before
    # the next turn, even if its periodic cadence isn't due yet (design notes section 2.4).


@dataclass
class TurnContext:
    """Everything a hook needs, without giving it direct control of the loop's own state machine.

    `mcp_client` is the real LodestoneMcpClient (or any object exposing the same
    `invoke_capability(capability, input_payload, ...)` shape) so hooks can issue their OWN
    out-of-band calls - e.g. ForcedObservationHook in safety.py calling
    minecraft.entity.nearby.read directly, bypassing the model entirely, exactly per the "layered
    on top, harness-issued, not model-requested" framing in the design notes.

    `history` is the same mutable list of human-readable turn summaries the real run_loop_cli()
    already builds and replays every turn (see render_history_entry(), build_cli_prompt() in the
    real file) - hooks append their own synthetic entries the same way, so the model sees
    forced-check results and abort explanations on its very next turn without any special-casing
    in the prompt-building code.
    """

    turn: int
    mode_name: str
    safety_tier: str
    goal: str
    mcp_client: Any
    trace: Any
    history: list[str]
    turns_since_last_entity_check: int = 0
    last_known_health: float | None = None
    extra: dict[str, Any] = field(default_factory=dict)


class Hook(Protocol):
    def before_turn(self, ctx: TurnContext) -> None: ...

    def before_action(self, ctx: TurnContext, action: ActionRequest) -> ActionRequest:
        """May return a modified ActionRequest (e.g. safety.apply_safety_floor() rewriting
        arguments) - must never change `action.tool`, only `action.arguments`, and must never
        silently drop the model's own intent (see design notes section 2.3: floors must be
        reported back to the model, not hidden)."""
        ...

    def after_action(self, ctx: TurnContext, action: ActionRequest, result: dict[str, Any]) -> HookSignal: ...

    def after_turn(self, ctx: TurnContext) -> None: ...


class BaseHook:
    """Convenience no-op base class - concrete hooks only override the points they care about."""

    def before_turn(self, ctx: TurnContext) -> None:
        return None

    def before_action(self, ctx: TurnContext, action: ActionRequest) -> ActionRequest:
        return action

    def after_action(self, ctx: TurnContext, action: ActionRequest, result: dict[str, Any]) -> HookSignal:
        return HookSignal.CONTINUE

    def after_turn(self, ctx: TurnContext) -> None:
        return None
