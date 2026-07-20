"""DRAFT SKETCH tests - hooks/inventory_watch.py's InventoryWatchHook: the polling-based
"did material X just appear in inventory" hook, standing in for the real inventory-change event
that (per Lane B's finding) does not exist on any adapter. Run with:

    python -m unittest verification.goal_orchestrator_draft.tests.test_inventory_watch_hook
"""

from __future__ import annotations

import os
import sys
import unittest

_VERIFICATION_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _VERIFICATION_DIR not in sys.path:
    sys.path.insert(0, _VERIFICATION_DIR)

from goal_orchestrator_draft.hooks.base import TurnContext  # noqa: E402
from goal_orchestrator_draft.hooks.inventory_watch import InventoryWatchHook  # noqa: E402
from goal_orchestrator_draft.tests.fakes import FakeMcpClient, FakeTrace  # noqa: E402


def _slots_result(*item_counts: tuple[str, int]) -> dict:
    return {
        "status": "ok",
        "error": None,
        "output": {"slots": [{"item": item, "count": count} for item, count in item_counts]},
    }


class InventoryWatchHookTest(unittest.TestCase):
    def _ctx(self, mcp_client: FakeMcpClient) -> TurnContext:
        return TurnContext(
            turn=1, mode_name="script", safety_tier="low", goal="g",
            mcp_client=mcp_client, trace=FakeTrace(), history=[],
        )

    def test_first_poll_only_establishes_a_baseline_and_never_fires(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": _slots_result(("minecraft:oak_log", 2)),
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)

        self.assertEqual([], ctx.history)
        self.assertEqual(1, len(mcp_client.calls))
        self.assertTrue(any(event["type"] == "inventory_watch_baseline" for event in ctx.trace.events))

    def test_a_new_item_appearing_between_polls_fires(self):
        """The task's required coverage: the hook firing when a stubbed inventory-read shows a new
        item appearing between polls."""
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:oak_log", 2)),
                _slots_result(("minecraft:oak_log", 2), ("minecraft:flint", 1)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)  # baseline poll - no flint yet
        ctx.turn = 2
        hook.before_turn(ctx)  # second poll - flint just appeared

        fired = [entry for entry in ctx.history if "minecraft:flint" in entry]
        self.assertEqual(1, len(fired))
        self.assertIn("harness-issued", fired[0])
        self.assertIn("+1", fired[0])

        fired_events = [event for event in ctx.trace.events if event["type"] == "inventory_watch_fired"]
        self.assertEqual(1, len(fired_events))
        self.assertIn("minecraft:flint", fired_events[0]["increases"])
        self.assertEqual(
            {"previous": 0, "current": 1, "delta": 1},
            fired_events[0]["increases"]["minecraft:flint"],
        )

    def test_an_item_present_since_before_the_hook_started_never_fires(self):
        # fire_if_already_present is deliberately not offered by this hook's MVP - an item already
        # held before the hook's first poll is the baseline, not a "just appeared" event.
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:flint", 3)),
                _slots_result(("minecraft:flint", 3)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)
        ctx.turn = 2
        hook.before_turn(ctx)

        self.assertEqual([], ctx.history)

    def test_a_further_increase_of_an_already_held_item_still_fires(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:flint", 1)),
                _slots_result(("minecraft:flint", 3)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)
        ctx.turn = 2
        hook.before_turn(ctx)

        fired = [entry for entry in ctx.history if "minecraft:flint" in entry]
        self.assertEqual(1, len(fired))
        self.assertIn("from 1 to 3", fired[0])
        self.assertIn("+2", fired[0])

    def test_a_decrease_never_fires(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:flint", 3)),
                _slots_result(("minecraft:flint", 1)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)
        ctx.turn = 2
        hook.before_turn(ctx)

        self.assertEqual([], ctx.history)

    def test_it_only_fires_once_per_crossing_not_on_every_later_poll(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:flint", 0)),
                _slots_result(("minecraft:flint", 1)),
                _slots_result(("minecraft:flint", 1)),  # unchanged since the last poll
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)
        ctx.turn = 2
        hook.before_turn(ctx)
        ctx.turn = 3
        hook.before_turn(ctx)

        fired = [entry for entry in ctx.history if "minecraft:flint" in entry]
        self.assertEqual(1, len(fired))

    def test_item_match_restricts_reporting_to_matching_items_only(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:oak_log", 0)),
                _slots_result(("minecraft:oak_log", 3), ("minecraft:flint", 1)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1, item_match="flint")

        hook.before_turn(ctx)
        ctx.turn = 2
        hook.before_turn(ctx)

        # oak_log also increased, but item_match="flint" means only flint is ever reported.
        self.assertEqual(1, len(ctx.history))
        self.assertIn("minecraft:flint", ctx.history[0])

    def test_cadence_skips_polls_until_every_n_turns_have_elapsed(self):
        mcp_client = FakeMcpClient({"minecraft.inventory.read": _slots_result()})
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=3)

        hook.before_turn(ctx)  # baseline - always polls
        hook.before_turn(ctx)  # not due yet
        hook.before_turn(ctx)  # not due yet
        self.assertEqual(1, len(mcp_client.calls))

        hook.before_turn(ctx)  # 3rd turn since the baseline poll - due again
        self.assertEqual(2, len(mcp_client.calls))

    def test_a_failed_poll_leaves_the_baseline_untouched_and_never_fires(self):
        mcp_client = FakeMcpClient({
            "minecraft.inventory.read": [
                _slots_result(("minecraft:flint", 0)),
                {"status": "error", "error": {"code": "MCP_TRANSPORT_ERROR"}, "output": None},
                _slots_result(("minecraft:flint", 1)),
            ],
        })
        ctx = self._ctx(mcp_client)
        hook = InventoryWatchHook(every_n_turns=1)

        hook.before_turn(ctx)  # baseline: flint=0
        ctx.turn = 2
        hook.before_turn(ctx)  # transport error - baseline must survive unchanged
        ctx.turn = 3
        hook.before_turn(ctx)  # flint=1 - delta is still measured against the turn-1 baseline

        fired = [entry for entry in ctx.history if "minecraft:flint" in entry]
        self.assertEqual(1, len(fired))
        self.assertIn("from 0 to 1", fired[0])


if __name__ == "__main__":
    unittest.main()
