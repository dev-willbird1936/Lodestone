"""DRAFT SKETCH - safety-tier layer: forced observation cadence, prompt addenda, and safety-param
flooring. See goal-orchestrator-design-notes.md section 2 for the full rationale.

Every mechanism here is ADDITIVE - it never removes or overrides a model-chosen action, and never
replaces an existing native safety guard (e.g. minecraft.goal.navigation.safe-waypoint's own
`safety`/`intelligence`/`combatPolicy` params, confirmed live in
protocol/catalog/core-capabilities.json). It only: (a) floors an existing safety-style parameter,
never below what the model itself requested, and always reports the floor back to the model; and
(b) injects extra read-only observation calls the model gets to see on its next turn, never acting
on the model's behalf.
"""

from __future__ import annotations

import enum
import json
from typing import Any

from .decision_protocol import ActionRequest
from .hooks.base import BaseHook, HookSignal, TurnContext


class SafetyTier(enum.Enum):
    LOW = "low"
    BALANCED = "balanced"
    HIGH = "high"


# Real hostile-mob type ids checked against minecraft.entity.nearby.read's `entities[].type`
# (confirmed field name/shape live in the capability catalog). Deliberately short and conservative:
# a false negative (an unlisted hostile) just means the model's own judgement - still the primary
# safety mechanism - has to catch it; a false positive here would train distrust of the forced-check
# signal. Extend this list as real runs surface gaps, don't try to enumerate every mob up front.
HOSTILE_ENTITY_TYPES = {
    "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
    "minecraft:cave_spider", "minecraft:drowned", "minecraft:husk", "minecraft:witch",
    "minecraft:enderman", "minecraft:phantom", "minecraft:pillager",
}

# Floors applied to minecraft.goal.navigation.safe-waypoint's own `safety` enum param
# ("low"|"balanced"|"high" - confirmed live in its real inputSchema). Never applied if the model's
# own requested value already meets or exceeds the floor - see apply_safety_floor() below.
_SAFETY_ORDER = ["low", "balanced", "high"]
_TIER_TO_WAYPOINT_FLOOR: dict[SafetyTier, str | None] = {
    SafetyTier.LOW: None,  # no floor - model's own choice passes through unmodified
    SafetyTier.BALANCED: "balanced",
    SafetyTier.HIGH: "high",
}

# Design notes section 2.1's tier table, `high` row: "also default combatPolicy to 'avoid' if the
# model omits it". Unlike `safety`, combatPolicy ("defensive"|"avoid"|"none") has no defined
# ordering in the real inputSchema, so this is a DEFAULT-WHEN-MISSING only, never a raise-if-lower
# override - an explicit model choice always wins, at every tier. Only `high` gets an entry here;
# the design doc's `balanced` row does not mention combatPolicy at all.
_TIER_TO_COMBAT_POLICY_DEFAULT: dict[SafetyTier, str | None] = {
    SafetyTier.LOW: None,
    SafetyTier.BALANCED: None,
    SafetyTier.HIGH: "avoid",
}

PROMPT_ADDENDA: dict[SafetyTier, str] = {
    SafetyTier.LOW: "",
    SafetyTier.BALANCED: (
        "\n\nSAFETY (balanced tier - layered on top of your own judgement, does not replace it): "
        "before any batch or turn that moves you into an area you have not recently checked, look "
        "at your most recent minecraft_entity_nearby_read result. Treat any non-player entity "
        "within your risk radius whose type is a known hostile (zombie, skeleton, creeper, spider, "
        "drowned, etc.) as a reason to shorten your next batch to a single defensive or "
        "repositioning action rather than continuing your original plan. Prefer "
        "minecraft_goal_navigation_safe-waypoint with safety:\"balanced\" or higher for any travel "
        "- if you omit safety or request lower, the harness will raise it to this session's floor "
        "and tell you so in the result. Check your health/food (minecraft_player_state_read) "
        "before starting anything risky (mining near lava/water, working at height)."
    ),
    SafetyTier.HIGH: (
        "\n\nSAFETY (high tier - layered on top of your own judgement, does not replace it): "
        "everything from balanced tier applies, plus: treat light level as a hazard signal even "
        "with no hostile currently visible - minecraft_world_light_analyze's mobSpawnRisk field "
        "(none/low/medium/high) and its darkSpots[].risk entries tell you where mobs could spawn "
        "nearby even in a currently-empty area. Use safety:\"high\" and combatPolicy:\"avoid\" for "
        "all travel. Keep every batch to the smallest set of subactions that completes one clearly "
        "bounded sub-step. If you detect any hostile within your risk radius, your very next action "
        "must be retreat, reposition, or a defensive action - not a continuation of the original "
        "plan - even if that delays the goal."
    ),
}


def _apply_enum_floor(
    arguments: dict[str, Any], field: str, floor: str, order: list[str] | None
) -> tuple[dict[str, Any], str | None]:
    """Shared floor-or-default logic for a single enum-style argument field, factored out so
    `safety` (an ordered enum: low < balanced < high) and `combatPolicy` (not linearly ordered in
    the real inputSchema) can share one implementation instead of two near-duplicate bodies, per
    apply_safety_floor()'s own earlier follow-up note.

    - Missing field: filled in with `floor` (a default, not an override) - reported.
    - Present field, `order` given, and the requested value ranks below `floor` in `order`: raised
      to `floor` - reported. This is the "never a silent downgrade" floor behavior (design notes
      section 2.3).
    - Present field and `order` is None (no defined ranking - e.g. combatPolicy): the model's
      explicit choice always wins unconditionally; this branch never fires for that case, so an
      unordered field can only ever be *defaulted* when absent, never overridden when present.
    - Present field already at or above `floor`: no-op, not reported - the model's own,
      possibly-more-cautious-than-required choice always passes through unchanged.
    """
    requested = arguments.get(field)
    if requested is None:
        return {**arguments, field: floor}, f"{field} was unset; defaulted to this session's floor '{floor}'"

    if order is not None and str(requested) in order and order.index(str(requested)) < order.index(floor):
        return (
            {**arguments, field: floor},
            f"requested {field}='{requested}' was raised to this session's floor '{floor}'",
        )

    return arguments, None


def apply_safety_floor(
    tool_name: str, arguments: dict[str, Any], tier: SafetyTier
) -> tuple[dict[str, Any], str | None]:
    """Floor (never downgrade) minecraft.goal.navigation.safe-waypoint's own `safety` param per
    tier, and default (never override) its `combatPolicy` param to "avoid" at the high tier when
    the model omits it. Returns (possibly-modified arguments, an optional human-readable note - if
    both fields needed a note, they are joined with "; "). The note MUST be surfaced back to the
    model in the tool result content when non-None - design notes section 2.3 is explicit that a
    floor is never silent. No-ops for every other tool: this function only ever adjusts a parameter
    a capability already exposes, it never invents new behavior for a capability that doesn't have
    its own safety knob.
    """
    if tool_name != "minecraft_goal_navigation_safe-waypoint":
        return arguments, None

    notes: list[str] = []

    safety_floor = _TIER_TO_WAYPOINT_FLOOR.get(tier)
    if safety_floor is not None:
        arguments, note = _apply_enum_floor(arguments, "safety", safety_floor, order=_SAFETY_ORDER)
        if note:
            notes.append(note)

    combat_policy_default = _TIER_TO_COMBAT_POLICY_DEFAULT.get(tier)
    if combat_policy_default is not None:
        arguments, note = _apply_enum_floor(arguments, "combatPolicy", combat_policy_default, order=None)
        if note:
            notes.append(note)

    return arguments, "; ".join(notes) if notes else None


def _perform_forced_check(ctx: TurnContext) -> bool:
    """Issue the harness-side (not model-requested) entity/health check pair and append a labeled
    synthetic history entry, exactly like a normal tool result the model itself had requested - see
    TurnContext's own docstring in hooks/base.py. Returns True if a known-hostile entity type was
    found within the checked radius (used by ReactiveRecheckHook's own trace, not as a control
    signal - the model decides what to do with this, the harness never acts on its behalf).
    """
    entities = ctx.mcp_client.invoke_capability("minecraft.entity.nearby.read", {"radius": 16})
    state = ctx.mcp_client.invoke_capability("minecraft.player.state.read", {})
    ctx.trace.record_event(
        "safety_forced_observation", turn=ctx.turn, safetyTier=ctx.safety_tier, entities=entities, playerState=state
    )

    hostile_nearby = False
    if isinstance(entities, dict) and entities.get("status") == "ok":
        for entity in (entities.get("output") or {}).get("entities", []):
            if entity.get("type") in HOSTILE_ENTITY_TYPES:
                hostile_nearby = True
                break

    ctx.history.append(
        "[safety-check, harness-issued, not requested by you] "
        f"minecraft_entity_nearby_read -> {json.dumps(entities, separators=(',', ':'), default=str)} | "
        f"minecraft_player_state_read -> {json.dumps(state, separators=(',', ':'), default=str)}"
        + (" | NOTE: a known-hostile entity type is within the checked radius." if hostile_nearby else "")
    )

    if isinstance(state, dict) and state.get("status") == "ok":
        ctx.last_known_health = (state.get("output") or {}).get("health", ctx.last_known_health)

    return hostile_nearby


class ForcedObservationHook(BaseHook):
    """Periodic, harness-issued entity/health check - fires every `every_n_turns` turns at
    `balanced`, every turn at `high` (design notes section 2.1's cadence table). Never blocks the
    model or tells it what to do; only ensures fresher data than the model might have bothered to
    ask for is always in context.
    """

    def __init__(self, tier: SafetyTier, every_n_turns: int) -> None:
        self.tier = tier
        self.every_n_turns = every_n_turns

    def before_turn(self, ctx: TurnContext) -> None:
        if self.tier == SafetyTier.LOW:
            return
        due = self.tier == SafetyTier.HIGH or ctx.turns_since_last_entity_check >= self.every_n_turns
        if not due:
            ctx.turns_since_last_entity_check += 1
            return
        _perform_forced_check(ctx)
        ctx.turns_since_last_entity_check = 0


class ReactiveRecheckHook(BaseHook):
    """Fires the same forced check immediately after any action result shows a concerning delta,
    instead of waiting for the periodic cadence - design notes section 2.4. Balanced/high tiers
    only. Kept as its own hook (rather than folded into ForcedObservationHook) so a future change to
    one trigger doesn't risk the other."""

    def __init__(self, tier: SafetyTier) -> None:
        self.tier = tier

    def after_action(self, ctx: TurnContext, action: ActionRequest, result: dict[str, Any]) -> HookSignal:
        if self.tier == SafetyTier.LOW:
            return HookSignal.CONTINUE
        output = result.get("output") if isinstance(result, dict) else None
        concerning = isinstance(output, dict) and (
            output.get("directFallback") is True
            or bool(output.get("safetyInterventions") or [])
            or (output.get("replans") or 0) > 0
        )
        if concerning:
            _perform_forced_check(ctx)
        return HookSignal.CONTINUE


class SafetyFloorHook(BaseHook):
    """Applies apply_safety_floor() to every dispatched action before it runs - the before_action
    hook point exists specifically for this (design notes section 3.3)."""

    def __init__(self, tier: SafetyTier) -> None:
        self.tier = tier

    def before_action(self, ctx: TurnContext, action: ActionRequest) -> ActionRequest:
        new_arguments, note = apply_safety_floor(action.tool, action.arguments, self.tier)
        if note:
            ctx.trace.record_event("safety_floor_applied", turn=ctx.turn, tool=action.tool, note=note)
            ctx.history.append(f"[safety] {action.tool}: {note}")
        return ActionRequest(tool=action.tool, arguments=new_arguments)


def build_safety_hooks(tier: SafetyTier) -> list[BaseHook]:
    """Composition entry point the real cli.py would call at startup - design notes section 3.3/3.4.
    Returns an empty list at `low`, matching today's behavior exactly (no forced calls, no floor, no
    prompt addendum)."""
    if tier == SafetyTier.LOW:
        return []
    every_n = 5 if tier == SafetyTier.BALANCED else 1
    return [
        SafetyFloorHook(tier),
        ForcedObservationHook(tier, every_n_turns=every_n),
        ReactiveRecheckHook(tier),
    ]
