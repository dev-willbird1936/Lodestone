"""One-off pre-flight check: make sure the live client's CURRENT world actually has trees within
normal walking distance before spending a full 60-turn "mine one log" orchestrator run on it.

Root cause this exists: verification/evidence/adhoc-goal11-mine-tree-run10/trace-2f2009195d02.jsonl
burned its entire 60-turn budget because the fresh world ensure_fresh_world() generated that round
happened to spawn the player in a desert/ocean region with no logs found across ~8 wide-radius scans
(confirmed: every minecraft.world.region.scan result in that run was dominated by
minecraft:sand/sandstone/water, zero "*_log" block types anywhere explored). ensure_fresh_world()
itself was already running correctly every round this session (confirmed via trace inspection:
bootstrap_ui_state catches "Title Screen" with inWorld:false BEFORE CurseForge's quickplay
auto-resume fires, then bootstrap_world_ready confirms a genuinely freshly-generated world with
inWorld:true) - so run10's failure was fresh-world-seed bad luck, not carried-over/polluted state
from earlier rounds' navigation and deaths.

This script: calls ensure_fresh_world() (reusing the real one from goal-orchestrator-milestone1.py,
not a reimplementation), then does a handful of small minecraft.world.region.scan calls in a plus
pattern around the player's spawn position. If no "*_log" block type turns up anywhere in that
scan, it quits to the title screen (mirroring run-neoforge-goal-benchmark.ps1's own proven
"Save and Quit to Title" pause-menu click flow) and tries again, up to MAX_ATTEMPTS times, before
giving up. On success it leaves the client sitting in a confirmed-treed fresh world, ready for
goal-orchestrator-milestone1.py to be run with --skip-bootstrap so it does not generate yet another
world on top of this one.

Run with: python verification/adhoc-ensure-treed-world.py --port 37821 --token <token>
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

MAX_ATTEMPTS = 3
# A small plus-pattern of 16x16x16 scans (4096 cells each, the adapter's hard cap) centered on the
# player's spawn column - a fast, bounded proxy for "is there very likely a tree within normal
# walking distance", not an exhaustive search like the model's own multi-turn one.
SCAN_OFFSETS = [(0, 0), (16, 0), (-16, 0), (0, 16), (0, -16)]


def quit_to_title(client: "orchestrator.LodestoneMcpClient") -> dict:
    """Mirrors run-neoforge-goal-benchmark.ps1's own proven shutdown flow: Escape to open the pause
    menu, find the "Save and Quit to Title" button by label, click it via a revision-guarded click.
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


def nearby_logs(client: "orchestrator.LodestoneMcpClient", cx: int, cy: int, cz: int) -> list[dict]:
    found = []
    for dx, dz in SCAN_OFFSETS:
        result = client.invoke_capability("minecraft.world.region.scan", {
            "x": cx + dx - 8, "y": max(cy - 8, -60), "z": cz + dz - 8,
            "sizeX": 16, "sizeY": 16, "sizeZ": 16,
        }, "1.0")
        if result.get("status") != "ok":
            continue
        block_counts = (result.get("output") or {}).get("blockCounts") or {}
        logs = {k: v for k, v in block_counts.items() if "_log" in k}
        if logs:
            found.append({"offset": [dx, dz], "logs": logs})
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", type=str, required=True)
    args = parser.parse_args()

    client = orchestrator.LodestoneMcpClient(args.port, args.token)
    client.initialize()
    trace = orchestrator.TraceWriter(
        Path(__file__).resolve().parent / "evidence" / "adhoc-ensure-treed-world" / "trace.jsonl"
    )

    for attempt in range(1, MAX_ATTEMPTS + 1):
        print(f"=== attempt {attempt}/{MAX_ATTEMPTS} ===", flush=True)
        if attempt > 1:
            print("no logs found - quitting to title to regenerate a fresh world...", flush=True)
            quit_to_title(client)
        world_state = orchestrator.ensure_fresh_world(client, trace)
        print(f"world ready: inWorld={world_state.get('inWorld')}", flush=True)

        ctx = client.invoke_capability("minecraft.player.context.read", {"reach": 5}, "1.0")
        position = (ctx.get("output") or {}).get("position") or {}
        if not position:
            print(f"could not read player position: {ctx}", flush=True)
            continue
        cx, cy, cz = int(position["x"]), int(position["y"]), int(position["z"])
        print(f"player spawn position: ({cx}, {cy}, {cz})", flush=True)

        logs = nearby_logs(client, cx, cy, cz)
        if logs:
            print(f"WORLD_OK: found logs near spawn: {logs}", flush=True)
            return 0
        print("no logs found in any of the 5 nearby scans", flush=True)

    print(f"WORLD_STILL_TREELESS_AFTER_{MAX_ATTEMPTS}_ATTEMPTS", flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
