"""Live verification of the L1 competency layer against a running Lodestone client.

Usage: python verification/l1-live-check.py [--port 37891] [--stage verbs|acceptance|all]

Prerequisites: an isolated client started via agent-goal-attempt-start.ps1 (SkipRecording is
fine), a fresh or loaded Survival world with the player in-world. The script exercises each
competency tool with assertions and prints a PASS/FAIL table; the acceptance stage runs the
compound survival goal (logs -> axe -> dirt house -> demolish holding axe -> mob kill)
end-to-end through L1 verbs and verifies terminal world-state evidence.

Every check is tolerant of a missing tool (reports SKIP) so the harness can run mid-rollout.
"""

import argparse
import json
import subprocess
import sys
import time

REPO = r"C:\SyncedProjects\Game Development\Minecraft\Mods\Lodestone"
RESULTS = []


def rpc(port, tool, args=None, timeout=180):
    cmd = [
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", "verification/agent-goal-rpc.ps1", "-Port", str(port), "-ToolName", tool,
    ]
    if args:
        cmd += ["-ArgsJson", json.dumps(args)]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=REPO)
    try:
        return json.loads(p.stdout)
    except Exception:
        return {"parse_error": p.stdout[:300], "stderr": p.stderr[:200]}


def cap(port, cid, inp=None, timeout=180):
    return rpc(port, "lodestone_capability_invoke", {"capability": cid, "input": inp or {}}, timeout)


def out(resp):
    return resp.get("result", {}).get("output", {})


def ok(resp):
    return resp.get("result", {}).get("status") == "ok"


def record(name, passed, evidence):
    RESULTS.append((name, passed, str(evidence)[:220]))
    print(("PASS " if passed else "FAIL ") + name + " :: " + str(evidence)[:220], flush=True)


def skip(name, why):
    RESULTS.append((name, None, why))
    print("SKIP " + name + " :: " + why, flush=True)


def inventory(port):
    o = out(rpc(port, "get_player_position"))
    return o.get("worldObservation", {}).get("inventory", {}).get("items", {}), o


def tool_names(port):
    resp = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
         "verification/agent-goal-rpc.ps1", "-Port", str(port), "-ListTools"],
        capture_output=True, text=True, timeout=120, cwd=REPO)
    try:
        return {t["name"] for t in json.loads(resp.stdout)["tools"]}
    except Exception:
        return set()


NEW_TOOLS = [
    "goto_position", "collect_drops", "chop_tree",           # chunk A
    "craft_item", "attack_entity", "survive_night", "respawn_recover",  # chunk B
]


def stage_verbs(port):
    names = tool_names(port)
    for t in NEW_TOOLS:
        record(f"tool-published:{t}", t in names, "in tools/list" if t in names else "missing")

    # alert events: subscribe must accept the prefix and alerts must not be redacted
    sub = rpc(port, "lodestone_events_subscribe", {"eventPrefix": "minecraft.player.alert", "bufferLimit": 64})
    record("alerts:subscribe", ok(sub), out(sub))

    # goto: walk to a surface point ~12 blocks away
    if "goto_position" in names:
        o = out(rpc(port, "get_player_position"))
        p = o.get("position", {})
        if p:
            tx, tz = int(p["x"]) + 12, int(p["z"])
            hm = out(rpc(port, "get_heightmap", {"x1": tx, "z1": tz, "x2": tx, "z2": tz}))
            cols = hm.get("columns", [])
            ty = int(cols[0]["height"]) + 1 if cols else int(p["y"])
            g = rpc(port, "goto_position", {"targetX": tx, "targetY": ty, "targetZ": tz}, timeout=300)
            go = out(g)
            record("goto:arrives", ok(g) and go.get("arrived") is True and go.get("distanceRemaining", 99) <= 2.0, go)
        else:
            skip("goto:arrives", "no player position")
    else:
        skip("goto:arrives", "tool missing")

    # chop_tree: at least one log mined and collected
    if "chop_tree" in names:
        before, _ = inventory(port)
        c = rpc(port, "chop_tree", {"collectDrops": True}, timeout=420)
        co = out(c)
        after, _ = inventory(port)
        gained = sum(v for k, v in after.items() if k.endswith("_log")) - sum(
            v for k, v in before.items() if k.endswith("_log"))
        record("chop_tree:logs", ok(c) and co.get("logsMined", 0) >= 1 and gained >= 1,
               {"out": co, "invGain": gained})
    else:
        skip("chop_tree:logs", "tool missing")

    # collect_drops: mine two ground blocks bare-handed, then collect
    if "collect_drops" in names:
        before, o = inventory(port)
        p = o.get("position", {})
        mined = 0
        if p:
            import math
            fx, fy, fz = int(math.floor(p["x"])), int(p["y"]), int(math.floor(p["z"]))
            for dx, dz in ((1, 0), (0, 1)):
                cap(port, "minecraft.player.block.look-at", {"x": fx + dx, "y": fy - 1, "z": fz + dz})
                if ok(rpc(port, "mine_block")):
                    mined += 1
                time.sleep(0.6)
        c = rpc(port, "collect_drops", {"radius": 8}, timeout=180)
        after, _ = inventory(port)
        gained = sum(after.values()) - sum(before.values())
        record("collect_drops:gain", ok(c) and mined >= 1 and gained >= 1,
               {"mined": mined, "gained": gained, "out": out(c)})
    else:
        skip("collect_drops:gain", "tool missing")

    # craft: planks then a crafting table then a wooden axe (needs logs from chop stage)
    if "craft_item" in names:
        c1 = rpc(port, "craft_item", {"item": "minecraft:oak_planks", "count": 8}, timeout=300)
        c2 = rpc(port, "craft_item", {"item": "minecraft:stick", "count": 4}, timeout=300)
        c3 = rpc(port, "craft_item", {"item": "minecraft:crafting_table", "count": 1}, timeout=300)
        c4 = rpc(port, "craft_item", {"item": "minecraft:wooden_axe", "count": 1}, timeout=600)
        inv, _ = inventory(port)
        record("craft:wooden_axe", inv.get("minecraft:wooden_axe", 0) >= 1,
               {"axeOut": out(c4), "inv": inv})
    else:
        skip("craft:wooden_axe", "tool missing")

    # attack_entity: nearest non-item, non-creeper entity
    if "attack_entity" in names:
        ents = out(rpc(port, "get_nearby_entities", {"radius": 24, "limit": 48})).get("entities", [])
        target = next((e for e in ents
                       if str(e.get("type")) not in ("minecraft:item", "minecraft:creeper", "minecraft:bat")
                       and "player" not in str(e.get("type"))), None)
        if target:
            eid = target.get("id") or target.get("entityId")
            a = rpc(port, "attack_entity", {"entityId": int(eid)}, timeout=420)
            ents2 = out(rpc(port, "get_nearby_entities", {"radius": 24, "limit": 48})).get("entities", [])
            gone = all((e.get("id") or e.get("entityId")) != eid for e in ents2)
            record("attack_entity:kill", ok(a) and gone, {"target": target.get("type"), "out": out(a), "gone": gone})
        else:
            skip("attack_entity:kill", "no suitable entity nearby")
    else:
        skip("attack_entity:kill", "tool missing")

    # poll alerts at the end: at least subscribe/poll round-trips cleanly
    sid = out(sub).get("subscriptionId")
    if sid is not None:
        pl = rpc(port, "lodestone_events_poll", {"subscriptionId": sid, "maxEvents": 32})
        record("alerts:poll", ok(pl), out(pl))


def stage_acceptance(port):
    """Compound goal through L1 verbs. Requires stage_verbs to have produced a wooden axe."""
    inv, o = inventory(port)
    if inv.get("minecraft:wooden_axe", 0) < 1:
        skip("acceptance", "no wooden_axe in inventory - run verbs stage first")
        return
    import math
    p = o.get("position", {})
    fx, fy, fz = int(math.floor(p["x"])), int(p["y"]), int(math.floor(p["z"]))

    # 1. gather 24 dirt
    for _ in range(30):
        inv, o = inventory(port)
        if inv.get("minecraft:dirt", 0) >= 24:
            break
        p = o.get("position", {})
        gx, gy, gz = int(math.floor(p["x"])), int(p["y"]) - 1, int(math.floor(p["z"]))
        for dx, dz in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)):
            cap(port, "minecraft.player.block.look-at", {"x": gx + dx, "y": gy, "z": gz + dz})
            rpc(port, "mine_block")
            time.sleep(0.5)
        rpc(port, "collect_drops", {"radius": 6}, timeout=120)
    inv, _ = inventory(port)
    record("acceptance:dirt>=24", inv.get("minecraft:dirt", 0) >= 24, inv.get("minecraft:dirt", 0))

    # 2. build 3x3 x2 walls + roof at a site 6 blocks away, record placements
    bx, bz = fx + 6, fz
    rpc(port, "goto_position", {"targetX": bx, "targetY": fy, "targetZ": bz}, timeout=300)
    _, o = inventory(port)
    base = int(o.get("position", {}).get("y", fy))
    placed = []
    perimeter = [(dx, dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1) if not (dx == 0 and dz == 0)]
    door = (1, 0)
    for level in (0, 1):
        for dx, dz in perimeter:
            if (dx, dz) == door:
                continue
            cell = (bx + dx, base + level, bz + dz)
            r = rpc(port, "place_target_block",
                    {"x": cell[0], "y": cell[1], "z": cell[2], "item": "minecraft:dirt"})
            if ok(r):
                placed.append(cell)
            time.sleep(0.4)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            cell = (bx + dx, base + 2, bz + dz)
            r = rpc(port, "place_target_block",
                    {"x": cell[0], "y": cell[1], "z": cell[2], "item": "minecraft:dirt"})
            if ok(r):
                placed.append(cell)
            time.sleep(0.4)
    record("acceptance:house-built", len(placed) >= 18, {"placed": len(placed)})

    # 3. demolish holding the axe
    rpc(port, "select_item", {"item": "minecraft:wooden_axe"})
    broken = 0
    for cell in placed:
        cap(port, "minecraft.player.block.look-at", {"x": cell[0], "y": cell[1], "z": cell[2]})
        r = rpc(port, "mine_block")
        if ok(r):
            broken += 1
        time.sleep(0.4)
    record("acceptance:house-demolished", broken >= len(placed) - 2, {"broken": broken, "of": len(placed)})
    rpc(port, "collect_drops", {"radius": 8}, timeout=180)

    # 4. kill a mob
    ents = out(rpc(port, "get_nearby_entities", {"radius": 32, "limit": 48})).get("entities", [])
    target = next((e for e in ents
                   if str(e.get("type")) not in ("minecraft:item", "minecraft:creeper", "minecraft:bat")
                   and "player" not in str(e.get("type"))), None)
    if target:
        eid = target.get("id") or target.get("entityId")
        a = rpc(port, "attack_entity", {"entityId": int(eid)}, timeout=420)
        ents2 = out(rpc(port, "get_nearby_entities", {"radius": 32, "limit": 48})).get("entities", [])
        gone = all((e.get("id") or e.get("entityId")) != eid for e in ents2)
        record("acceptance:mob-killed", ok(a) and gone, {"type": target.get("type"), "gone": gone})
    else:
        skip("acceptance:mob-killed", "no suitable mob within 32")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=37891)
    ap.add_argument("--stage", choices=["verbs", "acceptance", "all"], default="all")
    args = ap.parse_args()
    if args.stage in ("verbs", "all"):
        stage_verbs(args.port)
    if args.stage in ("acceptance", "all"):
        stage_acceptance(args.port)
    print("\n===== SUMMARY =====")
    fails = 0
    for name, passed, ev in RESULTS:
        state = "SKIP" if passed is None else ("PASS" if passed else "FAIL")
        if passed is False:
            fails += 1
        print(f"{state:5} {name}")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
