"""Unit tests for task #20's benchmark framework in goal-orchestrator-milestone1.py: the five
GoalTaskCatalog-derived benchmarks (b1..b5), their harness-side baseline-capture functions, their
independent verifiers, and the pure helpers (_cluster_trees, _scan_log_cells) those verifiers are
built from.

Every test fakes LodestoneMcpClient.invoke_capability with scripted per-capability responses - no
live NeoForge client, matching this repo's existing verification/test_goal_orchestrator_milestone1_*
conventions (importlib-load the hyphenated script, stdlib unittest).

Run with: python verification/test_goal_orchestrator_milestone1_benchmarks.py
"""

import importlib.util
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = orchestrator  # see test_goal_orchestrator_milestone1_history.py for why
_SPEC.loader.exec_module(orchestrator)


class ScriptedMcpClient:
    """Duck-types LodestoneMcpClient.invoke_capability: responses[capability] may be a fixed dict
    (returned every time), a list (popped in call order), or a callable(input_payload) -> dict, so
    each test only wires up the capabilities its verifier/baseline-capture actually calls.
    """

    def __init__(self, responses: dict[str, Any]):
        self.responses = responses
        self.calls: list[tuple[str, dict]] = []

    def invoke_capability(self, capability, input_payload, capability_version=None, dry_run=False):
        self.calls.append((capability, input_payload))
        response = self.responses.get(capability)
        if callable(response):
            return response(input_payload)
        if isinstance(response, list):
            return response.pop(0)
        if response is not None:
            return response
        return {"status": "error", "error": {"code": "NOT_STUBBED", "message": capability}, "output": None}


def _temp_trace() -> "orchestrator.TraceWriter":
    tmp_dir = tempfile.mkdtemp()
    return orchestrator.TraceWriter(Path(tmp_dir) / "trace.jsonl")


def player_state(position, health=20.0, dimension="minecraft:overworld"):
    return {
        "status": "ok", "error": None,
        "output": {
            "uuid": "u", "name": "p", "position": position, "rotation": {"pitch": 0, "yaw": 0},
            "dimension": dimension, "health": health, "food": 20,
        },
    }


def inventory(items: list[str]):
    slots = [{"item": item, "empty": False, "count": 1, "slot": i} for i, item in enumerate(items)]
    return {"status": "ok", "error": None, "output": {"player": {}, "selectedSlot": 0, "slots": slots}}


def block_cell(x, y, z, block, air=False, loaded=True):
    return {"position": {"x": x, "y": y, "z": z}, "block": block, "air": air, "loaded": loaded}


class ClusterTreesTest(unittest.TestCase):
    def test_separates_distant_columns_into_different_trees(self):
        cells = [block_cell(0, 70, 0, "minecraft:oak_log"), block_cell(50, 70, 50, "minecraft:oak_log")]
        groups = orchestrator._cluster_trees(cells)
        self.assertEqual(2, len(groups))

    def test_merges_adjacent_2x2_trunk_columns_into_one_tree(self):
        cells = [
            block_cell(0, 70, 0, "minecraft:dark_oak_log"),
            block_cell(1, 70, 0, "minecraft:dark_oak_log"),
            block_cell(0, 70, 1, "minecraft:dark_oak_log"),
            block_cell(1, 70, 1, "minecraft:dark_oak_log"),
        ]
        groups = orchestrator._cluster_trees(cells)
        self.assertEqual(1, len(groups))
        self.assertEqual(4, len(groups[0]))

    def test_same_column_multiple_y_levels_stays_one_tree(self):
        cells = [block_cell(5, y, 5, "minecraft:spruce_log") for y in range(70, 76)]
        groups = orchestrator._cluster_trees(cells)
        self.assertEqual(1, len(groups))
        self.assertEqual(6, len(groups[0]))


class ScanLogCellsTest(unittest.TestCase):
    def test_filters_to_loaded_non_air_log_blocks_only(self):
        blocks = [
            block_cell(0, 70, 0, "minecraft:spruce_log"),
            block_cell(1, 70, 0, "minecraft:air", air=True),
            block_cell(2, 70, 0, "minecraft:stone"),
            block_cell(3, 70, 0, "minecraft:oak_log", loaded=False),
        ]
        client = ScriptedMcpClient({
            "minecraft.world.blocks.read": {"status": "ok", "output": {"blocks": blocks}},
        })
        result = orchestrator._scan_log_cells(client, 0, 70, 0)
        self.assertEqual(1, len(result))
        self.assertEqual("minecraft:spruce_log", result[0]["block"])


class CaptureB1BaselineTest(unittest.TestCase):
    def test_records_spawn_position_and_a_start_time(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 10.0, "y": 64.0, "z": -5.0}),
        })
        before = time.monotonic()
        baseline = orchestrator.capture_b1_baseline(client, _temp_trace())
        after = time.monotonic()
        self.assertEqual({"x": 10.0, "y": 64.0, "z": -5.0}, baseline["spawnPosition"])
        self.assertTrue(before <= baseline["startedAtMonotonic"] <= after)


class CaptureB5BaselineTest(unittest.TestCase):
    def test_filters_entities_to_hostile_types_only(self):
        entities = [
            {"uuid": "u-zombie", "type": "minecraft:zombie", "entityId": 1, "distance": 5.0, "position": {}, "name": "", "player": False},
            {"uuid": "u-cow", "type": "minecraft:cow", "entityId": 2, "distance": 3.0, "position": {}, "name": "", "player": False},
            {"uuid": "u-player", "type": "minecraft:player", "entityId": 3, "distance": 1.0, "position": {}, "name": "Other", "player": True},
        ]
        client = ScriptedMcpClient({
            "minecraft.entity.nearby.read": {"status": "ok", "output": {"entities": entities}},
        })
        baseline = orchestrator.capture_b5_baseline(client, _temp_trace())
        self.assertEqual(["u-zombie"], [h["uuid"] for h in baseline["hostiles"]])


class VerifyB1Test(unittest.TestCase):
    def _baseline(self, elapsed_s: float):
        return {"spawnPosition": {"x": 0.0, "y": 64.0, "z": 0.0}, "startedAtMonotonic": time.monotonic() - elapsed_s}

    def test_passes_when_alive_survived_and_far_enough(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 40.0, "y": 64.0, "z": 0.0}, health=20.0),
        })
        result = orchestrator.verify_b1_spawn_gauntlet(client, _temp_trace(), self._baseline(100.0))
        self.assertTrue(result["passed"])

    def test_fails_when_not_enough_time_has_elapsed(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 40.0, "y": 64.0, "z": 0.0}, health=20.0),
        })
        result = orchestrator.verify_b1_spawn_gauntlet(client, _temp_trace(), self._baseline(10.0))
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["survived90s"])

    def test_fails_when_too_close_to_spawn(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 5.0, "y": 64.0, "z": 0.0}, health=20.0),
        })
        result = orchestrator.verify_b1_spawn_gauntlet(client, _temp_trace(), self._baseline(100.0))
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["reachedWaypoint"])

    def test_fails_when_dead(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 40.0, "y": 64.0, "z": 0.0}, health=0.0),
        })
        result = orchestrator.verify_b1_spawn_gauntlet(client, _temp_trace(), self._baseline(100.0))
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["alive"])


class VerifyB2Test(unittest.TestCase):
    def _two_tree_baseline(self):
        tree_a = [block_cell(0, 70, 0, "minecraft:spruce_log"), block_cell(0, 71, 0, "minecraft:spruce_log"),
                  block_cell(0, 72, 0, "minecraft:spruce_log")]
        tree_b = [block_cell(50, 70, 50, "minecraft:spruce_log"), block_cell(50, 71, 50, "minecraft:spruce_log")]
        return {"treeGroups": [tree_a, tree_b]}, tree_a, tree_b

    def test_passes_when_axe_present_and_a_full_tree_cleared_and_3plus_mined(self):
        baseline, tree_a, tree_b = self._two_tree_baseline()
        air_positions = {(c["position"]["x"], c["position"]["y"], c["position"]["z"]) for c in tree_a}

        def block_read(payload):
            key = (payload["x"], payload["y"], payload["z"])
            block = "minecraft:air" if key in air_positions else "minecraft:spruce_log"
            return {"status": "ok", "output": {"block": block}}

        client = ScriptedMcpClient({
            "minecraft.inventory.read": inventory(["minecraft:wooden_axe"]),
            "minecraft.world.block.read": block_read,
        })
        result = orchestrator.verify_b2_wooden_axe_mine_tree(client, _temp_trace(), baseline)
        self.assertTrue(result["passed"])
        self.assertTrue(result["checks"]["aTreeWasFullyCleared"])
        self.assertGreaterEqual(result["checks"]["logsHandMined"], 3)

    def test_fails_without_wooden_axe(self):
        baseline, tree_a, _tree_b = self._two_tree_baseline()
        client = ScriptedMcpClient({
            "minecraft.inventory.read": inventory(["minecraft:dirt"]),
            "minecraft.world.block.read": lambda payload: {"status": "ok", "output": {"block": "minecraft:air"}},
        })
        result = orchestrator.verify_b2_wooden_axe_mine_tree(client, _temp_trace(), baseline)
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["hasWoodenAxe"])

    def test_fails_when_no_single_tree_is_fully_cleared(self):
        # Partial mining spread across both trees can still hit >=3 logs without ever fully
        # clearing one - the goal specifically requires the SECOND tree be entirely removed.
        baseline, tree_a, tree_b = self._two_tree_baseline()

        def block_read(payload):
            # Only the first cell of each tree is air - neither tree ends up fully cleared.
            first_a = tree_a[0]["position"]
            first_b = tree_b[0]["position"]
            key = (payload["x"], payload["y"], payload["z"])
            if key in ((first_a["x"], first_a["y"], first_a["z"]), (first_b["x"], first_b["y"], first_b["z"])):
                return {"status": "ok", "output": {"block": "minecraft:air"}}
            return {"status": "ok", "output": {"block": "minecraft:spruce_log"}}

        client = ScriptedMcpClient({
            "minecraft.inventory.read": inventory(["minecraft:wooden_axe"]),
            "minecraft.world.block.read": block_read,
        })
        result = orchestrator.verify_b2_wooden_axe_mine_tree(client, _temp_trace(), baseline)
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["aTreeWasFullyCleared"])


class VerifyB3Test(unittest.TestCase):
    ALL_STONE_TOOLS = list(orchestrator.STONE_TOOLS)

    def _client(self, items, py, surface_height, furnace_present):
        blocks = [block_cell(5, py, 5, "minecraft:furnace")] if furnace_present else []
        return ScriptedMcpClient({
            "minecraft.inventory.read": inventory(items),
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 5.0, "y": float(py), "z": 5.0}),
            "minecraft.world.heightmap.read": {
                "status": "ok", "output": {"columns": [{"x": 5, "z": 5, "height": surface_height, "loaded": True}]},
            },
            "minecraft.world.blocks.read": {"status": "ok", "output": {"blocks": blocks}},
        })

    def test_passes_when_all_conditions_met(self):
        client = self._client(self.ALL_STONE_TOOLS, py=70, surface_height=69, furnace_present=True)
        result = orchestrator.verify_b3_stone_toolset(client, _temp_trace(), {})
        self.assertTrue(result["passed"])

    def test_fails_when_missing_one_stone_tool(self):
        client = self._client(self.ALL_STONE_TOOLS[:-1], py=70, surface_height=69, furnace_present=True)
        result = orchestrator.verify_b3_stone_toolset(client, _temp_trace(), {})
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["hasAllFourStoneTools"])

    def test_fails_when_no_furnace_found_nearby(self):
        client = self._client(self.ALL_STONE_TOOLS, py=70, surface_height=69, furnace_present=False)
        result = orchestrator.verify_b3_stone_toolset(client, _temp_trace(), {})
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["furnacePlaced"])

    def test_fails_when_player_left_underground(self):
        # Surface is at y=80 but the player is still down at y=40, deep in a mineshaft.
        client = self._client(self.ALL_STONE_TOOLS, py=40, surface_height=80, furnace_present=True)
        result = orchestrator.verify_b3_stone_toolset(client, _temp_trace(), {})
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["aboveGround"])


class VerifyB4Test(unittest.TestCase):
    def test_passes_when_dimension_is_the_nether(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, dimension="minecraft:the_nether"),
        })
        result = orchestrator.verify_b4_reach_nether(client, _temp_trace(), {})
        self.assertTrue(result["passed"])

    def test_fails_when_still_in_the_overworld(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, dimension="minecraft:overworld"),
        })
        result = orchestrator.verify_b4_reach_nether(client, _temp_trace(), {})
        self.assertFalse(result["passed"])


class VerifyB5Test(unittest.TestCase):
    def _baseline(self):
        return {"hostiles": [{"uuid": "u-zombie", "type": "minecraft:zombie"}]}

    def _trace_with_entity_attack(self) -> "orchestrator.TraceWriter":
        trace = _temp_trace()
        trace.record_tool_call(1, "minecraft_player_interact", {"action": "attack"}, {
            "status": "ok", "error": None,
            "output": {"action": "attack", "queued": True, "held": True, "targetKind": "entity"},
        })
        return trace

    def test_passes_when_baseline_hostile_is_gone_player_alive_and_an_attack_landed(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, health=18.0),
            "minecraft.entity.nearby.read": {"status": "ok", "output": {"entities": []}},
        })
        result = orchestrator.verify_b5_attack_nearest(client, self._trace_with_entity_attack(), self._baseline())
        self.assertTrue(result["passed"])
        self.assertEqual(1, len(result["checks"]["hostileNoLongerPresent"]))
        self.assertTrue(result["checks"]["attackedAnEntity"])

    def test_fails_when_hostile_gone_but_no_entity_attack_ever_landed(self):
        # The exact false positive live-evidenced in adhoc-benchmark-b5-run2: the model never
        # called player.interact at all, yet most baseline hostiles had vanished anyway (daytime
        # sun burning zombies/skeletons, or mobs simply wandering out of scan range over a
        # multi-minute run) - "hostile gone" alone must NOT be accepted as a real kill.
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, health=20.0),
            "minecraft.entity.nearby.read": {"status": "ok", "output": {"entities": []}},
        })
        result = orchestrator.verify_b5_attack_nearest(client, _temp_trace(), self._baseline())
        self.assertFalse(result["passed"])
        self.assertTrue(result["checks"]["observedHostileDeath"])  # the hostile really is gone...
        self.assertFalse(result["checks"]["attackedAnEntity"])  # ...but nothing ever attacked it

    def test_fails_when_all_baseline_hostiles_still_present(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, health=18.0),
            "minecraft.entity.nearby.read": {"status": "ok", "output": {"entities": [{"uuid": "u-zombie"}]}},
        })
        result = orchestrator.verify_b5_attack_nearest(client, self._trace_with_entity_attack(), self._baseline())
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["observedHostileDeath"])

    def test_fails_when_player_died(self):
        client = ScriptedMcpClient({
            orchestrator.HEALTH_READ_CAPABILITY: player_state({"x": 0, "y": 64, "z": 0}, health=0.0),
            "minecraft.entity.nearby.read": {"status": "ok", "output": {"entities": []}},
        })
        result = orchestrator.verify_b5_attack_nearest(client, self._trace_with_entity_attack(), self._baseline())
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["alive"])


class BenchmarkRegistryTest(unittest.TestCase):
    def test_all_five_benchmarks_registered_with_matching_goal_task_catalog_ids(self):
        expected_task_ids = {
            "b1": "survival.spawn-gauntlet",
            "b2": "survival.wooden-axe-mine-tree",
            "b3": "survival.stone-toolset",
            "b4": "survival.reach-nether",
            "b5": "combat.attack-nearest",
        }
        self.assertEqual(set(expected_task_ids), set(orchestrator.BENCHMARKS))
        for key, task_id in expected_task_ids.items():
            self.assertEqual(task_id, orchestrator.BENCHMARKS[key].task_id)
            self.assertGreater(orchestrator.BENCHMARKS[key].max_turns, 0)
            self.assertTrue(orchestrator.BENCHMARKS[key].goal)


if __name__ == "__main__":
    unittest.main()
