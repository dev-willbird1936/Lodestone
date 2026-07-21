"""One-off pre-flight check for B5 (combat.attack-nearest): make sure the live client's CURRENT
world actually has a reachable hostile mob nearby before spending a turn budget on a benchmark run
that needs one - mirrors adhoc-ensure-treed-world.py's role for B2/B3 (which need trees).

A freshly created world always starts at day, and vanilla hostile mobs mostly need darkness to
spawn, so a brand-new world's immediate surroundings are very unlikely to already have one. This
script sets time to night via minecraft.command.execute (harness-side world prep, never the
model's own action - the model still has to find and fight whatever spawns, same as GoalTaskCatalog's
combat.attack-nearest expects), waits in real time for natural spawns to populate nearby, then scans
minecraft.entity.nearby.read for a hostile that is both close AND out in the open (its Y is within
MAX_SURFACE_GAP of the terrain surface at its own (x,z) column, per minecraft.world.heightmap.read -
not buried in a cave). If nothing qualifying shows up after waiting, it quits to the title screen
(same proven "Save and Quit to Title" pause-menu click flow as adhoc-ensure-treed-world.py) and
tries a fresh world, up to MAX_ATTEMPTS times.

The surface-reachability filter exists because accepting the first hostile found regardless of
depth can hand the model a cave-dwelling target: live-evidenced (adhoc-benchmark-b5-run3) a run
where the nearest hostile was 39 blocks away down in a cave, safe-waypoint kept reporting no safe
route down to it, and 52 of that run's 55 turns went to approach rather than combat - the model got
exactly one real attack attempt in before the turn budget ran out. MAX_ACCEPT_DISTANCE (20 blocks)
exists for the same reason on the distance axis: attempt 4's 32-block SURFACE target still ate the
entire 40-turn budget on approach (and an unrelated ambush) before a single attack landed.

Run with: python verification/adhoc-ensure-hostile-nearby.py --port 37821 --token <token>
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import sys
import time
from pathlib import Path

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = orchestrator  # see test_goal_orchestrator_milestone1_history.py for why
_SPEC.loader.exec_module(orchestrator)

MAX_ATTEMPTS = 4
WAIT_ROUNDS_PER_ATTEMPT = 4
WAIT_SECONDS_PER_ROUND = 20.0
SCAN_RADIUS = 64
MAX_SURFACE_GAP = 6  # hostile's Y must be within this many blocks of the terrain surface at its
                     # own (x,z) column to count as "out in the open", not buried in a cave
MAX_ACCEPT_DISTANCE = 20  # tightened from "closest surface hostile, any distance" - attempt 3's
                          # 39 blocks and attempt 4's 32 blocks both let the approach phase eat
                          # most or all of the run's turn budget before combat ever started


def quit_to_title(client: "orchestrator.LodestoneMcpClient") -> dict:
    """Same proven flow as adhoc-ensure-treed-world.py's quit_to_title() - duplicated rather than
    imported so this script stays a standalone, self-contained ad hoc tool like its sibling.
    """
    state = client.call_tool("ui_state")
    out = state.get("output") or {}
    if state.get("status") == "ok" and out.get("inWorld") and not out.get("screenClass"):
        client.invoke_capability("minecraft.ui.key", {"key": 256, "scanCode": 0, "modifiers": 0}, "1.0")
        time.sleep(0.5)
        state = client.call_tool("ui_state")
        out = state.get("output") or {}
    screen_class = str(out.get("screenClass", ""))
    if "PauseScreen" in screen_class:
        quit_widget = None
        for widget in out.get("widgets") or []:
            label = str(widget.get("label", ""))
            if "click" in (widget.get("actions") or []) and "Quit" in label and "Title" in label:
                quit_widget = widget
                break
        if quit_widget is not None:
            client.invoke_capability("minecraft.ui.click", {
                "screenToken": out.get("screenToken"),
                "snapshotRevision": out.get("snapshotRevision"),
                "nodeId": quit_widget.get("nodeId"),
            }, "2.0")
            time.sleep(2.0)
    for _ in range(40):
        state = client.call_tool("ui_state")
        out = state.get("output") or {}
        if state.get("status") == "ok" and not out.get("inWorld"):
            return out
        time.sleep(0.25)
    return out


def find_hostiles(client: "orchestrator.LodestoneMcpClient") -> list[dict]:
    result = client.invoke_capability("minecraft.entity.nearby.read", {"radius": SCAN_RADIUS, "limit": 64}, "1.0")
    if result.get("status") != "ok":
        return []
    entities = (result.get("output") or {}).get("entities") or []
    return [e for e in entities if str(e.get("type", "")) in orchestrator.HOSTILE_MOB_TYPES]


def surface_height_at(client: "orchestrator.LodestoneMcpClient", x: float, z: float) -> float | None:
    result = client.invoke_capability(
        "minecraft.world.heightmap.read", {"x": int(x), "z": int(z), "sizeX": 1, "sizeZ": 1}, "1.0"
    )
    if result.get("status") != "ok":
        return None
    columns = (result.get("output") or {}).get("columns") or []
    if not columns or not columns[0].get("loaded"):
        return None
    return columns[0].get("height")


def find_surface_reachable_hostile(client: "orchestrator.LodestoneMcpClient") -> tuple[dict | None, list[dict]]:
    """Returns (chosen, all_hostiles) - chosen is the CLOSEST hostile within MAX_ACCEPT_DISTANCE
    whose Y is within MAX_SURFACE_GAP of the terrain surface at its own column, or None if no
    hostile currently found qualifies on both distance and surface-reachability (still worth
    reporting all_hostiles for visibility even on a miss).
    """
    hostiles = sorted(find_hostiles(client), key=lambda h: h.get("distance", 1e9))
    for hostile in hostiles:
        if hostile.get("distance", 1e9) > MAX_ACCEPT_DISTANCE:
            continue
        position = hostile.get("position") or {}
        surface = surface_height_at(client, position.get("x", 0), position.get("z", 0))
        if surface is None:
            continue
        gap = surface - position.get("y", 0)
        hostile["_surfaceGap"] = gap
        if gap <= MAX_SURFACE_GAP:
            return hostile, hostiles
    return None, hostiles


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", type=str, required=True)
    args = parser.parse_args()

    client = orchestrator.LodestoneMcpClient(args.port, args.token)
    client.initialize()
    trace = orchestrator.TraceWriter(
        Path(__file__).resolve().parent / "evidence" / "adhoc-ensure-hostile-nearby" / "trace.jsonl"
    )

    for attempt in range(1, MAX_ATTEMPTS + 1):
        print(f"=== attempt {attempt}/{MAX_ATTEMPTS} ===", flush=True)
        if attempt > 1:
            print("no surface-reachable hostile found - quitting to title to regenerate a fresh world...", flush=True)
            quit_to_title(client)

        world_state = orchestrator.ensure_fresh_world(client, trace)
        print(f"world ready: inWorld={world_state.get('inWorld')}", flush=True)

        time_set = client.invoke_capability("minecraft.command.execute", {"command": "time set night"}, "1.0")
        print(f"set time to night: {time_set.get('status')}", flush=True)

        for round_num in range(1, WAIT_ROUNDS_PER_ATTEMPT + 1):
            time.sleep(WAIT_SECONDS_PER_ROUND)
            chosen, all_hostiles = find_surface_reachable_hostile(client)
            print(
                f"  wait round {round_num}/{WAIT_ROUNDS_PER_ATTEMPT}: {len(all_hostiles)} hostile(s) found, "
                f"surface-reachable pick: {chosen}",
                flush=True,
            )
            if chosen:
                # Set time back to day IMMEDIATELY on finding a target, before returning control -
                # confirmed live (adhoc-benchmark-b5-run1) that leaving time at night for the whole
                # pre-flight+run duration turns the world-spawn point into a respawn-camping trap:
                # the player died to a zombie before turn 1 even started, then died again to a
                # (freshly spawned) zombie within 15-60s of every single one of 9 respawns across
                # the entire 40-turn run, never once reaching a live attack attempt. Night is only
                # needed long enough to spawn ONE reachable hostile; day stops further spawns while
                # the one already found stays in the world.
                client.invoke_capability("minecraft.command.execute", {"command": "time set day"}, "1.0")
                print("set time back to day now that a target exists", flush=True)
                health_state = client.invoke_capability(orchestrator.HEALTH_READ_CAPABILITY, {})
                health = (health_state.get("output") or {}).get("health")
                print(f"player health at handoff: {health}", flush=True)
                print(f"WORLD_OK: chosen surface-reachable hostile: {chosen}", flush=True)
                return 0

    print(f"WORLD_STILL_NO_SURFACE_REACHABLE_HOSTILE_AFTER_{MAX_ATTEMPTS}_ATTEMPTS", flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
