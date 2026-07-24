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
import sys
import time
import urllib.request

REPO = r"C:\SyncedProjects\Game Development\Minecraft\Mods\Lodestone"
RESULTS = []

# One persistent MCP session for the whole run: burst-creating one-shot sessions per call
# exhausts the gateway session table (live-observed -32001 storms), and events/artifacts are
# session-scoped anyway.
class McpClient:
    def __init__(self, port):
        self.uri = f"http://127.0.0.1:{port}/mcp"
        self.sid = None
        self.rid = 0
        self._post("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                  "clientInfo": {"name": "l1-live-check", "version": "1.0"}})
        self._post("notifications/initialized", {})

    def _post(self, method, params, timeout=300):
        self.rid += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.rid, "method": method, "params": params}).encode()
        req = urllib.request.Request(self.uri, data=body)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        if self.sid:
            req.add_header("Mcp-Session-Id", self.sid)
            req.add_header("MCP-Protocol-Version", "2025-11-25")
        resp = urllib.request.urlopen(req, timeout=timeout)
        if resp.headers.get("Mcp-Session-Id"):
            self.sid = resp.headers.get("Mcp-Session-Id")
        raw = resp.read().decode("utf-8", "replace")
        if not raw.strip():
            return {}
        if raw.lstrip().startswith("{"):
            return json.loads(raw)
        datas = [l[5:].strip() for l in raw.splitlines() if l.startswith("data:")]
        return json.loads(datas[-1]) if datas else {"raw": raw[:300]}

    def tool(self, name, args=None, timeout=300):
        r = self._post("tools/call", {"name": name, "arguments": args or {}}, timeout)
        res = r.get("result", {})
        outp = {"http": 200}
        if r.get("error"):
            outp["rpcError"] = r["error"]
        if res.get("isError"):
            outp["isError"] = True
        outp["result"] = res.get("structuredContent") if res.get("structuredContent") is not None \
            else res.get("content")
        return outp


_CLIENT = None


def client(port):
    global _CLIENT
    if _CLIENT is None:
        _CLIENT = McpClient(port)
    return _CLIENT


def rpc(port, tool, args=None, timeout=300):
    try:
        return client(port).tool(tool, args, timeout)
    except Exception as e:
        # one reconnect attempt (client restart / session loss)
        global _CLIENT
        _CLIENT = None
        try:
            return client(port).tool(tool, args, timeout)
        except Exception as e2:
            return {"transport_error": f"{e} / {e2}"}


def cap(port, cid, inp=None, timeout=300):
    return rpc(port, "lodestone_capability_invoke", {"capability": cid, "input": inp or {}}, timeout)


def out(resp):
    r = resp.get("result")
    if isinstance(r, dict):
        return r.get("output", r if "status" not in r else {})
    return {}


def ok(resp):
    r = resp.get("result")
    return isinstance(r, dict) and r.get("status") == "ok"


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
    try:
        r = client(port)._post("tools/list", {})
        return {t["name"] for t in r.get("result", {}).get("tools", [])}
    except Exception:
        return set()


NEW_TOOLS = [
    "goto_position", "collect_drops", "chop_tree",           # chunk A
    "craft_item", "attack_entity", "survive_night", "respawn_recover",  # chunk B
]


def surface_columns(port, cx, cz, r):
    hm = out(rpc(port, "get_heightmap", {"x1": cx - r, "z1": cz - r, "x2": cx + r, "z2": cz + r}))
    return [c for c in hm.get("columns", []) if c.get("loaded")]


def walkable_target(port, py, min_dist=8, max_dy=4):
    """Nearest non-canopy surface column at least min_dist away, close to player height."""
    o = out(rpc(port, "get_player_position"))
    p = o.get("position", {})
    if not p:
        return None, None
    cols = surface_columns(port, int(p["x"]), int(p["z"]), 16)
    good = [c for c in cols
            if "leaves" not in str(c.get("surfaceBlock", "")) and "log" not in str(c.get("surfaceBlock", ""))
            and abs(c["height"] + 1 - py) <= max_dy]
    good.sort(key=lambda c: abs(c["x"] - p["x"]) + abs(c["z"] - p["z"]))
    tgt = next((c for c in good if abs(c["x"] - p["x"]) + abs(c["z"] - p["z"]) >= min_dist), None)
    return tgt, p


def tree_cluster_center(port, p):
    """Center of the densest 16x16 cell of tree columns within the loaded area."""
    cols = surface_columns(port, int(p["x"]), int(p["z"]), 64)
    trees = [c for c in cols if "leaves" in str(c.get("surfaceBlock", "")) or "_log" in str(c.get("surfaceBlock", ""))]
    if not trees:
        return None
    from collections import defaultdict
    cl = defaultdict(list)
    for c in trees:
        cl[(int(c["x"]) // 16, int(c["z"]) // 16)].append(c)
    pts = max(cl.values(), key=len)
    mx = sum(c["x"] for c in pts) / len(pts)
    mz = sum(c["z"] for c in pts) / len(pts)
    return {"x": mx, "z": mz, "n": len(pts)}


def stage_verbs(port):
    names = tool_names(port)
    for t in NEW_TOOLS:
        record(f"tool-published:{t}", t in names, "in tools/list" if t in names else "missing")

    # alert events: subscribe returns a flat {id, sessionId, ...} envelope. Poll is NOT checked
    # here: subscriptions are session-owned and the one-shot rpc helper closes its session on
    # exit, so cross-invocation polling is impossible by design over this bridge.
    sub = rpc(port, "lodestone_events_subscribe", {"eventPrefix": "minecraft.player.alert", "bufferLimit": 64})
    sub_id = sub.get("result", {}).get("id")
    record("alerts:subscribe", bool(sub_id), sub.get("result", {}))

    # goto: walk to a real, non-canopy surface point >=8 blocks away near player height
    if "goto_position" in names:
        o = out(rpc(port, "get_player_position"))
        py = o.get("position", {}).get("y", 0)
        tgt, p = walkable_target(port, py)
        if tgt:
            g = rpc(port, "goto_position",
                    {"targetX": int(tgt["x"]), "targetY": int(tgt["height"]) + 1, "targetZ": int(tgt["z"]),
                     "arriveRadius": 2}, timeout=300)
            go = out(g)
            record("goto:arrives", ok(g) and go.get("arrived") is True and go.get("distanceRemaining", 99) <= 2.5,
                   {"target": tgt, "out": go})
        else:
            skip("goto:arrives", "no walkable target column found")
    else:
        skip("goto:arrives", "tool missing")

    # chop_tree: reposition toward the densest tree cluster first, then chop
    if "chop_tree" in names:
        o = out(rpc(port, "get_player_position"))
        p = o.get("position", {})
        cluster = tree_cluster_center(port, p) if p else None
        if cluster and "goto_position" in names:
            cols = surface_columns(port, int(cluster["x"]), int(cluster["z"]), 6)
            ground = next((c for c in sorted(cols, key=lambda c: abs(c["x"] - cluster["x"]) + abs(c["z"] - cluster["z"]))
                           if "leaves" not in str(c.get("surfaceBlock", "")) and "log" not in str(c.get("surfaceBlock", ""))),
                          None)
            if ground:
                rpc(port, "goto_position", {"targetX": int(ground["x"]), "targetY": int(ground["height"]) + 1,
                                            "targetZ": int(ground["z"]), "arriveRadius": 3}, timeout=300)
        before, _ = inventory(port)
        c = rpc(port, "chop_tree", {"collectDrops": True}, timeout=420)
        co = out(c)
        after, _ = inventory(port)
        gained = sum(v for k, v in after.items() if k.endswith("_log")) - sum(
            v for k, v in before.items() if k.endswith("_log"))
        record("chop_tree:logs", ok(c) and co.get("logsMined", 0) >= 1 and gained >= 1,
               {"out": co, "invGain": gained, "cluster": cluster})
    else:
        skip("chop_tree:logs", "tool missing")

    # collect_drops: mine verified-reachable ground blocks bare-handed, then collect
    if "collect_drops" in names:
        before, o = inventory(port)
        p = o.get("position", {})
        mined = 0
        if p:
            import math
            fx, fy, fz = int(math.floor(p["x"])), int(p["y"]), int(math.floor(p["z"]))
            # only blocks that actually drop an item when mined bare-handed
            DROPPABLE = ("minecraft:grass_block", "minecraft:dirt", "minecraft:sand", "minecraft:gravel")
            for dx, dz in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                la = cap(port, "minecraft.player.block.look-at", {"x": fx + dx, "y": fy - 1, "z": fz + dz})
                t = out(la).get("target", {})
                hp = t.get("blockPosition", {})
                if (hp.get("x"), hp.get("y"), hp.get("z")) != (fx + dx, fy - 1, fz + dz):
                    continue
                if t.get("distance", 99) > 4.2 or t.get("block") not in DROPPABLE:
                    continue
                if ok(rpc(port, "mine_block")):
                    mined += 1
                time.sleep(0.6)
                if mined >= 2:
                    break
        time.sleep(1.5)
        c = rpc(port, "collect_drops", {"radius": 10}, timeout=180)
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

    # attack_entity: nearest surface-accessible entity (cave mobs excluded via dy filter)
    if "attack_entity" in names:
        o = out(rpc(port, "get_player_position"))
        py = o.get("position", {}).get("y", 0)
        px = o.get("position", {}).get("x", 0)
        pz = o.get("position", {}).get("z", 0)
        ents = out(rpc(port, "get_nearby_entities", {"radius": 24, "limit": 48})).get("entities", [])

        def attackable(e):
            t = str(e.get("type"))
            ep = e.get("position", {})
            if t in ("minecraft:item", "minecraft:creeper", "minecraft:bat") or "player" in t:
                return False
            horiz = ((ep.get("x", 0) - px) ** 2 + (ep.get("z", 0) - pz) ** 2) ** 0.5
            return abs(ep.get("y", 0) - py) <= 3 and horiz <= 20

        target = next((e for e in ents if attackable(e)), None)
        if target:
            eid = target.get("id") or target.get("entityId")
            a = rpc(port, "attack_entity", {"entityId": int(eid)}, timeout=420)
            ents2 = out(rpc(port, "get_nearby_entities", {"radius": 24, "limit": 48})).get("entities", [])
            gone = all((e.get("id") or e.get("entityId")) != eid for e in ents2)
            record("attack_entity:kill", ok(a) and gone, {"target": target.get("type"), "out": out(a), "gone": gone})
        else:
            skip("attack_entity:kill", "no surface-accessible entity nearby")
    else:
        skip("attack_entity:kill", "tool missing")


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


def stage_recover(port):
    """Self-healing pre-stage: clear quarantine, respawn if dead, shelter through night."""
    for round_i in range(3):
        rpc(port, "reconcile_session")
        o = out(rpc(port, "get_player_position"))
        alive = bool(o.get("position")) and (o.get("health") or 0) > 0
        if not alive:
            r = rpc(port, "respawn_recover", {"recoverItems": False}, timeout=300)
            print("recover: respawn ->", str(out(r) or r.get("result", {}))[:160], flush=True)
            time.sleep(2)
            rpc(port, "reconcile_session")
            o = out(rpc(port, "get_player_position"))
            alive = bool(o.get("position")) and (o.get("health") or 0) > 0
            if not alive:
                continue
        day = out(rpc(port, "get_server_info")).get("dayTime", 0) % 24000
        if 12000 <= day < 23200:
            print(f"recover: night (dayTime {day}), sheltering via survive_night", flush=True)
            s = rpc(port, "survive_night", {"timeoutTicks": 15000}, timeout=900)
            print("recover: survive_night ->", str(out(s) or s.get("result", {}))[:200], flush=True)
            rpc(port, "reconcile_session")
        o = out(rpc(port, "get_player_position"))
        day = out(rpc(port, "get_server_info")).get("dayTime", 0) % 24000
        if o.get("position") and (o.get("health") or 0) > 0 and not (12000 <= day < 23200):
            print(f"recover: ready (hp {o.get('health')}, dayTime {day})", flush=True)
            return True
    print("recover: FAILED to reach a live daytime player", flush=True)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=37891)
    ap.add_argument("--stage", choices=["verbs", "acceptance", "all"], default="all")
    args = ap.parse_args()
    if not stage_recover(args.port):
        print("aborting: recovery pre-stage failed")
        sys.exit(2)
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
