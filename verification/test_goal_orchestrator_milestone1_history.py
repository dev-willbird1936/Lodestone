"""Unit tests for task #16's bounded-replayed-history fix in goal-orchestrator-milestone1.py:
reencode_volumetric_result(), digest_result(), HistoryEntry, render_history(), and build_cli_prompt().

Root cause under test: a single minecraft.world.blocks.read call over a 16x16x16 volume returns
4096 per-cell entries and used to be embedded into `history` verbatim, forever - see trace-
abdc6a99d3e5.jsonl, which hit a hard 1,000,000-token model context limit at turn 17 from exactly
this. These tests synthesize that same 4096-entry shape and drive the fix end to end without a live
NeoForge client (matching this repo's existing verification/test_gpt54_mini_goal_model_proxy.py
pattern: importlib-load the hyphenated script, stdlib unittest, no live process/network needed).

Run with: python verification/test_goal_orchestrator_milestone1_history.py
"""

import importlib.util
import json
import os
import sys
import unittest

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
# Must be registered in sys.modules BEFORE exec_module(): goal-orchestrator-milestone1.py uses
# `from __future__ import annotations` (stringified annotations) together with @dataclass
# (HistoryEntry below has union-typed fields like `dict[str, Any] | None`), and dataclasses'
# annotation resolution looks the defining module up via sys.modules[cls.__module__] while the
# class body is still executing - without this line that lookup returns None and dataclass
# decoration crashes with "'NoneType' object has no attribute '__dict__'" (confirmed live).
sys.modules[_SPEC.name] = orchestrator
_SPEC.loader.exec_module(orchestrator)


def synthetic_blocks_read_result(size: int = 16, block_types: tuple[str, ...] = ("minecraft:stone", "minecraft:dirt")):
    """Builds a raw minecraft.world.blocks.read-shaped {status, output:{...,"blocks":[...]}} result
    with size**3 entries (4096 for the default size=16, matching the real 16x16x16 scan that
    triggered the original bug) - mostly air (as a real terrain scan usually is, since most of a
    queried volume above ground is empty), a handful of non-air block types, and a few explicitly
    not-yet-loaded cells, so every reencode_volumetric_result() code path is exercised by one
    fixture. Returns (result, non_air_count, air_count, unloaded_count) so tests can assert against
    exact expected counts rather than re-deriving them.
    """
    blocks = []
    non_air_count = 0
    air_count = 0
    unloaded_count = 0
    index = 0
    for x in range(size):
        for y in range(size):
            for z in range(size):
                position = {"x": x, "y": y, "z": z}
                if index % 97 == 0:
                    # A sparse handful of not-yet-generated/loaded cells.
                    blocks.append({"air": False, "block": "minecraft:air", "loaded": False, "position": position})
                    unloaded_count += 1
                elif index % 3 == 0:
                    block_id = block_types[index % len(block_types)]
                    blocks.append({"air": False, "block": block_id, "loaded": True, "position": position})
                    non_air_count += 1
                else:
                    blocks.append({"air": True, "block": "minecraft:air", "loaded": True, "position": position})
                    air_count += 1
                index += 1
    result = {
        "status": "ok",
        "error": None,
        "output": {
            "dimension": "minecraft:overworld",
            "origin": {"x": 0, "y": 60, "z": 0},
            "size": {"x": size, "y": size, "z": size},
            "count": size ** 3,
            "blocks": blocks,
        },
    }
    return result, non_air_count, air_count, unloaded_count


class ReencodeVolumetricResultTest(unittest.TestCase):
    def test_shrinks_a_4096_cell_scan_dramatically(self):
        raw, _non_air, _air, _unloaded = synthetic_blocks_read_result()
        raw_size = len(json.dumps(raw))
        reencoded = orchestrator.reencode_volumetric_result(raw)
        reencoded_size = len(json.dumps(reencoded))

        self.assertEqual(4096, len(raw["output"]["blocks"]))
        # The coordinator's own estimate: ~365KB down to roughly 10-30KB. Assert a real, large
        # reduction rather than pinning an exact byte count that would be brittle to tweak.
        self.assertLess(reencoded_size, raw_size * 0.25)

    def test_every_non_air_loaded_block_survives_with_exact_coordinate_and_type(self):
        raw, non_air_count, _air, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)
        block_types = reencoded["output"]["blockTypes"]

        total_coords = sum(len(coords) for coords in block_types.values())
        self.assertEqual(non_air_count, total_coords)
        for entry in raw["output"]["blocks"]:
            if entry["loaded"] and not entry["air"]:
                pos = entry["position"]
                coordinate = [pos["x"], pos["y"], pos["z"]]
                self.assertIn(coordinate, block_types[entry["block"]])

    def test_air_cells_are_elided_to_a_count_not_enumerated(self):
        raw, _non_air, air_count, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)

        self.assertEqual(air_count, reencoded["output"]["airCellsElided"])
        self.assertNotIn("minecraft:air", reencoded["output"]["blockTypes"])

    def test_unloaded_cells_are_tracked_separately_not_counted_as_air_or_a_block_type(self):
        raw, _non_air, _air, unloaded_count = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)

        self.assertEqual(unloaded_count, len(reencoded["output"]["unloadedCells"]))
        self.assertGreater(unloaded_count, 0)

    def test_non_volumetric_result_passes_through_unchanged(self):
        result = {"status": "ok", "error": None, "output": {"health": 20.0, "food": 20, "name": "Dev"}}
        self.assertEqual(result, orchestrator.reencode_volumetric_result(result))

    def test_region_scan_shaped_result_passes_through_unchanged(self):
        # minecraft.world.region.scan's output is already a compact blockCounts histogram with no
        # per-cell "blocks" array - there is nothing for this function to re-encode.
        result = {
            "status": "ok", "error": None,
            "output": {"dimension": "minecraft:overworld", "totalCells": 4096, "loadedCells": 4096,
                       "unloadedCells": 0, "blockCounts": {"minecraft:stone": 3000, "minecraft:air": 1096}},
        }
        self.assertEqual(result, orchestrator.reencode_volumetric_result(result))

    def test_error_result_with_no_output_passes_through_unchanged(self):
        result = {"status": "error", "error": {"code": "ADAPTER_FAILURE", "message": "x"}, "output": None}
        self.assertEqual(result, orchestrator.reencode_volumetric_result(result))


class DigestResultTest(unittest.TestCase):
    def test_digest_of_a_large_reencoded_scan_stays_small(self):
        raw, _non_air, _air, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)

        digest = orchestrator.digest_result(reencoded)

        self.assertLess(len(digest), orchestrator.DIGEST_THRESHOLD_BYTES * 2)

    def test_digest_caps_total_example_coordinates(self):
        raw, _non_air, _air, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)

        digest = json.loads(orchestrator.digest_result(reencoded))

        total_examples = sum(len(coords) for coords in digest["exampleCoordinates"].values())
        self.assertLessEqual(total_examples, orchestrator.DIGEST_MAX_COORDS)

    def test_digest_preserves_the_full_per_type_histogram_even_though_examples_are_capped(self):
        raw, non_air_count, _air, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)

        digest = json.loads(orchestrator.digest_result(reencoded))

        self.assertEqual(non_air_count, sum(digest["blockTypeCounts"].values()))

    def test_digest_includes_status_and_error_when_present(self):
        result = {"status": "error", "error": {"code": "ADAPTER_FAILURE", "message": "boom"}, "output": None}
        digest = json.loads(orchestrator.digest_result(result))
        self.assertEqual("error", digest["status"])
        self.assertEqual("ADAPTER_FAILURE", digest["error"]["code"])

    def test_digest_falls_back_to_output_keys_for_a_large_non_volumetric_output(self):
        result = {"status": "ok", "error": None, "output": {"weird": "x" * 10_000, "otherField": 1}}
        digest = json.loads(orchestrator.digest_result(result))
        self.assertEqual(["otherField", "weird"], digest["outputKeys"])
        self.assertIn("stale", digest["note"].lower())


class RenderHistoryTest(unittest.TestCase):
    def _large_entry(self, turn: int) -> "orchestrator.HistoryEntry":
        raw, _non_air, _air, _unloaded = synthetic_blocks_read_result()
        reencoded = orchestrator.reencode_volumetric_result(raw)
        decision = {"tool": "minecraft_world_blocks_read", "arguments": {"x": 0, "y": 60, "z": 0}}
        return orchestrator.HistoryEntry(turn=turn, decision=decision, result=reencoded)

    def test_recent_large_entry_still_renders_in_full(self):
        history = [self._large_entry(1)]
        rendered = orchestrator.render_history(history)
        self.assertIn("blockTypes", rendered[0])
        self.assertNotIn("digest", rendered[0])

    def test_old_large_entry_renders_as_a_compact_digest(self):
        # Turn 1's large entry, then enough later small turns that turn 1 falls outside the
        # RECENT_FULL_RENDER_TURNS window.
        history = [self._large_entry(1)]
        for turn in range(2, 2 + orchestrator.RECENT_FULL_RENDER_TURNS + 2):
            history.append(orchestrator.HistoryEntry(
                turn=turn, decision={"tool": "minecraft_player_context_read", "arguments": {}},
                result={"status": "ok", "error": None, "output": {"facing": "north"}},
            ))

        rendered = orchestrator.render_history(history)

        self.assertIn("digest", rendered[0])
        self.assertNotIn("blockTypes", rendered[0])
        # The digest is still present and bounded, not just a bare marker.
        self.assertIn("blockTypeCounts", rendered[0])

    def test_old_small_entry_still_renders_in_full_regardless_of_age(self):
        history = [orchestrator.HistoryEntry(
            turn=1, decision={"tool": "minecraft_player_look", "arguments": {"yaw": 0, "pitch": 0}},
            result={"status": "ok", "error": None, "output": {"yaw": 0, "pitch": 0}},
        )]
        for turn in range(2, 2 + orchestrator.RECENT_FULL_RENDER_TURNS + 2):
            history.append(orchestrator.HistoryEntry(
                turn=turn, decision={"tool": "minecraft_player_context_read", "arguments": {}},
                result={"status": "ok", "error": None, "output": {"facing": "north"}},
            ))

        rendered = orchestrator.render_history(history)

        self.assertIn('"yaw":0', rendered[0])
        self.assertNotIn("digest", rendered[0])

    def test_notes_render_verbatim(self):
        note_text = "Turn 5: SAFETY WARNING - health went from 20.0 to 12.0."
        history = [orchestrator.HistoryEntry(turn=5, note=note_text)]
        self.assertEqual([note_text], orchestrator.render_history(history))

    def test_hard_cap_truncates_any_oversized_rendered_entry(self):
        oversized_note = "x" * (orchestrator.HISTORY_ENTRY_HARD_CAP_CHARS + 5000)
        history = [orchestrator.HistoryEntry(turn=1, note=oversized_note)]

        rendered = orchestrator.render_history(history)

        self.assertLess(len(rendered[0]), len(oversized_note))
        self.assertIn("TRUNCATED", rendered[0])

    def test_recency_is_based_on_distinct_turns_not_raw_list_position(self):
        # Turn 1 alone produces 3 entries (action + two notes), so a naive "last 2 list entries"
        # slice would wrongly treat only two of turn 1's own entries as recent instead of judging
        # by turn number. A later turn 2 (single entry) must still correctly leave turn 1 fully
        # inside the RECENT_FULL_RENDER_TURNS=2 window.
        self.assertEqual(2, orchestrator.RECENT_FULL_RENDER_TURNS)
        large = self._large_entry(1)
        history = [
            large,
            orchestrator.HistoryEntry(turn=1, note="Turn 1: note A"),
            orchestrator.HistoryEntry(turn=1, note="Turn 1: note B"),
            orchestrator.HistoryEntry(
                turn=2, decision={"tool": "minecraft_player_look", "arguments": {}},
                result={"status": "ok", "error": None, "output": {}},
            ),
        ]

        rendered = orchestrator.render_history(history)

        self.assertIn("blockTypes", rendered[0])
        self.assertNotIn("digest", rendered[0])


class BuildCliPromptBoundedGrowthTest(unittest.TestCase):
    def test_prompt_stays_bounded_across_many_large_volumetric_scans(self):
        """The core end-to-end regression check for task #16: this is what actually happened in
        trace-abdc6a99d3e5.jsonl (several large minecraft.world.blocks.read calls while searching
        for a tree) synthesized without a live client, asserting the fix actually holds under the
        real failure conditions rather than only in isolation.
        """
        history: list[orchestrator.HistoryEntry] = []
        for turn in range(1, 15):
            raw, _non_air, _air, _unloaded = synthetic_blocks_read_result()
            reencoded = orchestrator.reencode_volumetric_result(raw)
            decision = {"tool": "minecraft_world_blocks_read", "arguments": {"x": turn, "y": 60, "z": 0}}
            history.append(orchestrator.HistoryEntry(turn=turn, decision=decision, result=reencoded))

        prompt = orchestrator.build_cli_prompt("mine one log from the nearest tree", "tool catalog text", history)

        # The real run hit a ~1,000,000 token (roughly 4,000,000+ char) hard limit by turn 17 with
        # only unreencoded history. 14 full-size scans bounded by this fix must land nowhere close.
        self.assertLess(len(prompt), 200_000)
        # And it must still be useful, not just small: the two most recent scans are fully present.
        self.assertIn("blockTypes", prompt)

    def test_empty_history_still_builds_a_valid_prompt(self):
        prompt = orchestrator.build_cli_prompt("goal", "tools", [])
        self.assertIn("goal", prompt)
        self.assertNotIn("Actions taken so far", prompt)


if __name__ == "__main__":
    unittest.main()
