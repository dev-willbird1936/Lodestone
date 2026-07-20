"""DRAFT SKETCH - polling-based inventory-change hook, fitted to this package's turn-based Hook
protocol (hooks/base.py). See goal-orchestrator-design-notes.md and the standalone, related-lane
draft at ../../goal-orchestrator-hooks-draft.py, whose InventoryDeltaHook class first worked out this
same idea against a different (poll-loop-based start/poll/stop) Hook hierarchy - this module reuses
that file's grounding, re-implemented against THIS package's before_turn/before_action/after_action/
after_turn protocol instead of duplicating its own separate Hook/HookManager class tree.

Lane B finding (goal-orchestrator-hooks-draft.py's own module docstring, confirmed live by grepping
every `publish("minecraft.` / `publish("lodestone.` call site across every adapter in this repo -
NeoForge, every Fabric version, every Forge version): there is no minecraft.inventory.* event
anywhere in this codebase, on any adapter - only lifecycle/player/chat/ui events are ever published.
So the owner's own literal example ("having x material pop into inventory") cannot be built on
minecraft.event.subscribe/poll at all, with or without jar changes to the *event* system. It CAN be
built with zero jar changes a different way: minecraft.inventory.read is a real, already-implemented
QUERY capability - this hook polls it periodically (harness-issued, bypassing the model, exactly like
../safety.py's ForcedObservationHook already does for minecraft.entity.nearby.read /
minecraft.player.state.read) and diffs slot contents client-side. This is push-emulated-by-poll, not
a true server-pushed event: latency is bounded by the poll cadence, not one game tick.
"""

from __future__ import annotations

from typing import Any, Callable

from .base import BaseHook, TurnContext


def _extract_slot_item_and_count(slot: dict[str, Any]) -> tuple[str, int]:
    """protocol/catalog/core-capabilities.json's outputSchema for minecraft.inventory.read declares
    `"slots":{"type":"array"}` with no items schema - the catalog does not pin per-slot field names.
    Mirrors the same defensive field-name guessing goal-orchestrator-hooks-draft.py's
    InventoryDeltaHook already uses for exactly this reason (and goal-orchestrator-milestone1.py's
    own verify_log_in_inventory(), which reads slot.get("item", "") for the same reason), trying a
    short list of plausible key names for both the item id and the stack count rather than assuming
    one; update this once a live minecraft.inventory.read response is captured.
    """
    if not isinstance(slot, dict):
        return "", 0
    item_id = str(slot.get("item") or slot.get("id") or slot.get("itemId") or "")
    count = slot.get("count")
    if count is None:
        count = slot.get("quantity", 0)
    try:
        return item_id, int(count)
    except (TypeError, ValueError):
        return item_id, 0


def _read_inventory_totals(ctx: TurnContext, player: str | None) -> dict[str, int] | None:
    """One minecraft.inventory.read call, reduced to {item_id: summed_count_across_all_slots}.
    Returns None (rather than an empty dict) on a failed/malformed read, so a transient failure
    never gets treated as "everything went to zero" by the caller's diff logic.
    """
    input_payload = {"player": player} if player else {}
    result = ctx.mcp_client.invoke_capability("minecraft.inventory.read", input_payload)
    if not isinstance(result, dict) or result.get("status") != "ok" or not isinstance(result.get("output"), dict):
        ctx.trace.record_event("inventory_watch_poll_failed", turn=ctx.turn, result=result)
        return None

    slots = result["output"].get("slots") or result["output"].get("items") or []
    totals: dict[str, int] = {}
    for slot in slots:
        item_id, count = _extract_slot_item_and_count(slot)
        if item_id:
            totals[item_id] = totals.get(item_id, 0) + count
    return totals


class InventoryWatchHook(BaseHook):
    """Periodic, harness-issued minecraft.inventory.read poll that diffs slot contents against the
    previous poll and appends a synthetic, labeled history entry for every item whose total count
    increased - the concrete answer to the owner's own worked example ("having x material pop into
    inventory"). Purely observational, exactly like safety.py's ForcedObservationHook: it never
    blocks the model, never acts on its behalf, and never removes information - it only ensures the
    model sees "material X just appeared" on its very next turn even if it never thought to
    re-check inventory itself.

    `item_match`, if given, restricts firing/reporting to item ids matching it (a case-insensitive
    substring, or an arbitrary `item_id -> bool` predicate) - e.g. `item_match="flint"` for the
    owner's own "mine gravel until you get flint" example. Leave it None (the default) to report
    every item whose count increased - the fully generic "what changed" case, useful when the goal
    doesn't already know which specific material to look for.

    This package's Hook protocol has no separate start()/stop() lifecycle (unlike the standalone
    goal-orchestrator-hooks-draft.py's own Hook base class) - only before_turn/before_action/
    after_action/after_turn. So "establish a baseline, then diff on every later poll" is folded into
    before_turn itself: the very first poll only records a baseline (never fires, since there is no
    "previous" reading to compare against yet); every poll after that reports increases relative to
    the immediately preceding poll.
    """

    def __init__(
        self,
        every_n_turns: int = 1,
        item_match: str | Callable[[str], bool] | None = None,
        player: str | None = None,
        min_increase: int = 1,
    ) -> None:
        self.every_n_turns = max(1, every_n_turns)
        self.player = player
        self.min_increase = min_increase
        self._match = self._build_matcher(item_match)
        self._item_match_repr = (
            item_match if (item_match is None or isinstance(item_match, str))
            else getattr(item_match, "__name__", "<callable>")
        )
        self._baseline: dict[str, int] | None = None
        self._turns_since_last_poll = 0

    @staticmethod
    def _build_matcher(item_match: str | Callable[[str], bool] | None) -> Callable[[str], bool]:
        if item_match is None:
            return lambda _item_id: True
        if isinstance(item_match, str):
            needle = item_match.lower()
            return lambda item_id: needle in item_id.lower()
        return item_match

    def before_turn(self, ctx: TurnContext) -> None:
        due = self._baseline is None or self._turns_since_last_poll >= self.every_n_turns - 1
        if not due:
            self._turns_since_last_poll += 1
            return
        self._turns_since_last_poll = 0
        self._poll_and_report(ctx)

    def _poll_and_report(self, ctx: TurnContext) -> None:
        current = _read_inventory_totals(ctx, self.player)
        if current is None:
            return  # failed poll - leave any existing baseline untouched, try again next cadence

        if self._baseline is None:
            self._baseline = current
            ctx.trace.record_event("inventory_watch_baseline", turn=ctx.turn, totals=current)
            return

        increases: dict[str, dict[str, int]] = {}
        for item_id, count in current.items():
            if not self._match(item_id):
                continue
            previous = self._baseline.get(item_id, 0)
            delta = count - previous
            if delta >= self.min_increase:
                increases[item_id] = {"previous": previous, "current": count, "delta": delta}

        # Always resync the baseline after a successful poll, matched or not, so this is a
        # debounced crossing-detector (fires once per increase, not on every later poll while the
        # item is still present) rather than a "every poll while above threshold" spammer.
        self._baseline = current

        if not increases:
            return

        ctx.trace.record_event("inventory_watch_fired", turn=ctx.turn, increases=increases)
        for item_id, info in increases.items():
            ctx.history.append(
                "[inventory-watch, harness-issued, not requested by you] minecraft_inventory_read "
                f"detected {item_id} increased from {info['previous']} to {info['current']} "
                f"(+{info['delta']}) since the last check."
            )
