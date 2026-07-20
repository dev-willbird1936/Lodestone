"""Sustained-load soak test for gpt54-mini-goal-model-proxy.py: the realtime goal-model bridge
chosen as the canonical scoring backend for ADAPTIVE_V1 realtime benchmark passes.

This is pure offline infrastructure verification - no Minecraft client, no NeoForge, no live game
session anywhere in this file. It exercises the proxy exactly as HttpJsonGoalModelProvider would:
started as a real subprocess (`python gpt54-mini-goal-model-proxy.py <port>`) and driven purely
over real HTTP with GoalDecisionRequest/GoalPlanRequest-shaped bodies (see
common/goal-engine/src/main/java/dev/lodestone/goal/{GoalDecisionRequest,GoalPlanRequest,
HttpJsonGoalModelProvider}.java for the exact wire format this mirrors). It never imports the
proxy's internals - see test_gpt54_mini_goal_model_proxy.py for that (unittest, monkeypatched
subprocess).

Two backends feed the proxy's CODEX_EXE, matching how the proxy always shells out per call rather
than holding a long-lived interactive subprocess (see that module's own docstring):

  - A FAKE codex backend (this same script, invoked via --fake-codex-worker through a tiny OS
    shim set as CODEX_EXE) used for the bulk of the sustained load. It is fast, deterministic, and
    fully controllable via a "__SOAK_CONTROL__ {...}" JSON blob this harness embeds in each
    request's prompt content - the fake worker echoes back which RUNTOKEN-<runId>#<turn> markers
    are visible in the prompt it received (in a "soakVisible" field the real proxy does not
    persist into session history, since it only ever stores candidateIndex/rationale - see
    record_session_turn). That gives this harness a precise, black-box read on exactly what
    history the *proxy* actually replayed for a given call, without importing its internals -
    which is what makes the MAX_SESSION_TURNS-trim and cross-run-isolation checks below rigorous
    rather than just "it didn't crash".
  - The REAL `codex exec --model gpt-5.4-mini` backend, used for a small, deliberately limited
    number of genuine episodes to measure real end-to-end latency and confirm the real backend's
    output actually round-trips through this proxy correctly. Kept modest by design (see
    --real-episodes/--real-decisions) since these are real, billed API calls.

Run with: python verification/gpt54-mini-goal-model-proxy-soak-test.py [options]
Use --skip-real to run only the free/fast fake-backend phases (e.g. for a quick CI-style check).
Use --help for the full option list.
"""

import argparse
import http.client
import json
import os
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

try:
    import psutil
except ImportError:  # pragma: no cover - memory sampling degrades gracefully without it
    psutil = None

PROXY_PATH = Path(__file__).resolve().with_name("gpt54-mini-goal-model-proxy.py")
SELF_PATH = Path(__file__).resolve()
RUNTOKEN_RE = re.compile(r"RUNTOKEN-([A-Za-z0-9._-]+)#(\d+)")
CONTROL_RE = re.compile(r"__SOAK_CONTROL__\s*(\{.*\})", re.DOTALL)


# =====================================================================================
# Fake codex worker: stands in for the real `codex` CLI when invoked via the shim below.
# =====================================================================================

def fake_codex_worker(argv) -> int:
    """Mimic exactly the slice of `codex exec`'s contract gpt54-mini-goal-model-proxy.py depends
    on: read the prompt from stdin, write the JSON answer to the path following
    --output-last-message, exit. Behavior for a given call is controlled by a __SOAK_CONTROL__
    JSON blob the harness embeds in the prompt content; a call with no control block behaves like
    a fast, generic decision."""
    output_path = None
    for index, arg in enumerate(argv):
        if arg == "--output-last-message" and index + 1 < len(argv):
            output_path = argv[index + 1]

    prompt = sys.stdin.read()
    control = {}
    match = CONTROL_RE.search(prompt)
    if match:
        try:
            control = json.loads(match.group(1))
        except json.JSONDecodeError:
            control = {}

    sleep_s = control.get("sleepS")
    if sleep_s:
        time.sleep(float(sleep_s))

    if output_path is None:
        return 1

    if control.get("fail"):
        Path(output_path).write_text("ERROR simulated model failure, not JSON", encoding="utf-8")
        return 0

    # Every RUNTOKEN-<runId>#<turn> marker visible anywhere in the received prompt - both this
    # call's own fresh content (which always carries its own about-to-be-recorded token) and any
    # replayed "Your own prior decisions" history block the proxy prepended - reported back
    # verbatim so the harness can tell exactly what the *proxy* actually retained/replayed,
    # without asking it directly.
    visible = sorted(set(RUNTOKEN_RE.findall(prompt)), key=lambda pair: (pair[0], int(pair[1])))
    visible_str = ",".join("%s#%s" % pair for pair in visible)

    if control.get("planShaped"):
        # Deliberately a genuine GoalPlanRequest-shaped response with NO candidateIndex at all -
        # this is the exact shape that previously tripped gpt54-mini-goal-model-proxy.py's old
        # "every response must have an integer candidateIndex" validation and got silently
        # replaced with a fabricated fallback object (see the fix + regression tests in
        # test_gpt54_mini_goal_model_proxy.py). soakVisible/soakToken are extra keys the real
        # proxy passes through unchanged (record_session_turn only ever reads
        # candidateIndex/rationale) so the harness can still verify this call round-tripped.
        plan = {
            "id": control.get("turnToken", "plan"), "goal": "survival.wooden-axe-mine-tree",
            "metadata": {"completionPredicateReady": True}, "segments": [],
            "soakVisible": visible_str, "soakToken": control.get("turnToken", ""),
        }
        Path(output_path).write_text(json.dumps(plan), encoding="utf-8")
        return 0

    decision = {
        "candidateIndex": int(control.get("candidateIndex", 0)),
        "rationale": control.get("turnToken", ""),
        "soakVisible": visible_str,
    }
    Path(output_path).write_text(json.dumps(decision), encoding="utf-8")
    return 0


def write_fake_codex_shim(tmp_dir: Path) -> Path:
    """Write a tiny OS-executable wrapper that re-invokes THIS script in --fake-codex-worker mode,
    for use as CODEX_EXE. A wrapper is needed because CODEX_EXE must name a single executable (the
    proxy always runs [CODEX, "exec", ...]) and a bare .py file is not reliably directly
    executable via subprocess without shell=True on Windows, whereas .cmd/.sh wrappers are.
    Empirically verified (see verification/ soak-test development notes) that Windows .cmd %*
    forwarding round-trips the proxy's actual argv faithfully, including the embedded-quote
    `-c model_reasoning_effort="low"` argument and space-containing --output-last-message paths."""
    if os.name == "nt":
        shim = tmp_dir / "fake-codex.cmd"
        shim.write_text(
            "@echo off\r\n\"%s\" \"%s\" --fake-codex-worker %%*\r\n" % (sys.executable, SELF_PATH),
            encoding="utf-8",
        )
        return shim
    shim = tmp_dir / "fake-codex.sh"
    shim.write_text(
        "#!/bin/sh\nexec \"%s\" \"%s\" --fake-codex-worker \"$@\"\n" % (sys.executable, SELF_PATH),
        encoding="utf-8",
    )
    shim.chmod(0o755)
    return shim


# =====================================================================================
# Proxy process management
# =====================================================================================

class ProxyInstance:
    def __init__(self, name, port, proc, log_path):
        self.name = name
        self.port = port
        self.proc = proc
        self.log_path = log_path

    def rss_bytes(self):
        if psutil is None or self.proc.poll() is not None:
            return None
        try:
            return psutil.Process(self.proc.pid).memory_info().rss
        except psutil.Error:
            return None

    def stop(self):
        if self.proc.poll() is not None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=5)


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for_port(port, timeout_s=10.0) -> None:
    deadline = time.monotonic() + timeout_s
    last_error = None
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError as error:
            last_error = error
            time.sleep(0.1)
    raise RuntimeError("proxy did not open port %d within %.1fs (last error: %s)" % (
        port, timeout_s, last_error))


def start_proxy(name, env_overrides, log_dir: Path) -> ProxyInstance:
    port = find_free_port()
    log_path = log_dir / ("%s.log" % name)
    env = dict(os.environ)
    env.update(env_overrides)
    env["LODESTONE_PROXY_LOG"] = str(log_path)
    proc = subprocess.Popen(
        [sys.executable, str(PROXY_PATH), str(port)],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    wait_for_port(port)
    return ProxyInstance(name, port, proc, log_path)


# =====================================================================================
# HTTP client + wire-format payload builders (mirrors HttpJsonGoalModelProvider exactly)
# =====================================================================================

def post(port, payload, timeout_s) -> tuple:
    """POST payload to the proxy exactly as HttpJsonGoalModelProvider does (a bare JSON POST body,
    any path - the proxy's do_POST does not inspect self.path). Returns (status, parsed_body,
    elapsed_seconds); raises on a transport-level failure (connection refused/reset, client-side
    timeout) rather than swallowing it, since those are meaningful soak-test signals themselves."""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout_s)
    body = json.dumps(payload)
    start = time.perf_counter()
    try:
        connection.request("POST", "/v1/chat/completions", body=body,
                            headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        raw = response.read()
        elapsed = time.perf_counter() - start
        return response.status, json.loads(raw.decode("utf-8")), elapsed
    finally:
        connection.close()


def decision_content(run_id, control) -> str:
    """A representative GoalDecisionRequest-shaped prompt, mirroring the real fields
    HttpJsonGoalModelProvider.choose() sends (goal/mode/policy/state/candidates/response) - see
    that method in HttpJsonGoalModelProvider.java. The __SOAK_CONTROL__ suffix is this harness's
    own test-control channel, appended after the realistic JSON body so it never disturbs that
    body's shape; the real HttpJsonGoalModelProvider never sends one."""
    body = {
        "goal": "survival.wooden-axe-mine-tree" if "b2" in run_id else "survival.spawn-gauntlet",
        "mode": "REALTIME",
        "policy": {
            "intelligence": "adaptive-v1", "safety": "high",
            "chooseOnlyEligibleCandidates": True, "preferPlayerSafetyWhenHigh": True,
            "useFreshWorldObservation": True,
        },
        "state": {
            "player": {"health": 18.0, "maxHealth": 20.0, "onFire": False, "inLava": False,
                       "inWater": False, "fallDistance": 0.0},
            "nearbyThreats": [], "localNavigation": {"forwardDropRisk": False},
        },
        "candidates": [
            {"id": "observe", "kind": "observe", "capability": "", "input": {},
             "observeAfter": True, "preconditions": [], "assertionCount": 0},
            {"id": "move-to-target", "kind": "invoke", "capability": "minecraft.player.move",
             "input": {"x": 12, "y": 64, "z": -8}, "observeAfter": True,
             "preconditions": [], "assertionCount": 1},
            {"id": "mine-log", "kind": "invoke", "capability": "minecraft.goal.mine_block",
             "input": {"block": "minecraft:oak_log"}, "observeAfter": True,
             "preconditions": [{"path": "inventory.axe", "op": "exists"}], "assertionCount": 1},
        ],
        "response": "Return JSON only: {candidateIndex: integer, rationale: string}.",
    }
    return json.dumps(body) + "\n\n__SOAK_CONTROL__ " + json.dumps(control)


def plan_content(run_id, control) -> str:
    """A representative GoalPlanRequest-shaped prompt, mirroring HttpJsonGoalModelProvider.plan()'s
    real fields (goal/mode/policy/builtInTasks/planContract/prerequisiteRules/instructions/
    response). Notably this "response" instruction asks for a plan object with NO candidateIndex -
    exercising exactly the code path that gpt54-mini-goal-model-proxy.py's choose() previously
    mishandled (see the fix in that file and the regression tests in
    test_gpt54_mini_goal_model_proxy.py)."""
    body = {
        "goal": "survival.wooden-axe-mine-tree",
        "mode": "REALTIME",
        "policy": {"intelligence": "adaptive-v1", "safety": "high", "observation": "full",
                   "combatPolicy": "defensive", "allowBlockBreaking": True,
                   "allowBlockPlacing": True, "allowCommands": False},
        "builtInTasks": [],
        "planContract": {"segments": "non-empty array, at most 16 segments",
                          "stepsPerSegment": "at least one and at most 32 steps"},
        "prerequisiteRules": {"toolTiers": {"stoneOrCobblestone": "obtain a wooden or better pickaxe"}},
        "instructions": "Return JSON only. Create a bounded declarative plan.",
        "response": "Return the plan object directly: {id, goal, metadata, segments}.",
    }
    return json.dumps(body) + "\n\n__SOAK_CONTROL__ " + json.dumps(control)


def request_body(run_id, content, reasoning_effort="low", model="gpt-5.4-mini") -> dict:
    return {
        "model": model, "reasoning_effort": reasoning_effort, "temperature": 0,
        "messages": [{"role": "user", "content": content}],
        "response_format": {"type": "json_object"}, "runId": run_id,
    }


def end_session_body(run_id) -> dict:
    return {"runId": run_id, "endSession": True}


# =====================================================================================
# Call bookkeeping / report
# =====================================================================================

class CallLog:
    def __init__(self):
        self.records = []
        self.lock = threading.Lock()

    def add(self, **fields):
        with self.lock:
            self.records.append(fields)

    def by_phase(self, phase):
        return [r for r in self.records if r["phase"] == phase]


def latency_stats(elapsed_list):
    if not elapsed_list:
        return None
    ordered = sorted(elapsed_list)
    return {
        "n": len(ordered), "min": ordered[0], "max": ordered[-1],
        "mean": statistics.mean(ordered), "median": statistics.median(ordered),
        "p95": ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))],
    }


# =====================================================================================
# Phase 1: sustained multi-episode load (multiple seeds/runIds back-to-back) + MAX_SESSION_TURNS
# =====================================================================================

def run_episode(port, log, phase, run_id, decision_count, client_timeout_s, findings):
    """Runs one synthetic goal-run against the fake backend: a plan call, then decision_count
    sequential decisions, then endSession - matching a real GoalEngine.run() call shape
    (GoalEngine calls plan() once when no built-in plan matches, then choose() repeatedly, then
    HttpJsonGoalModelProvider.endSession() in its finally block)."""
    plan_control = {"turnToken": "RUNTOKEN-%s#plan" % run_id, "planShaped": True}
    status, parsed, elapsed = post(port, request_body(run_id, plan_content(run_id, plan_control)),
                                    client_timeout_s)
    log.add(phase=phase, run_id=run_id, kind="plan", elapsed_s=elapsed, status=status,
            fallback=_extract_fallback(parsed))
    if _extract_fallback(parsed):
        findings.append("%s: plan-shaped call for %s unexpectedly returned fallback=true "
                         "(regression of the GoalPlanRequest fix)" % (phase, run_id))
    if "id" not in _decision_dict(parsed):
        findings.append("%s: plan-shaped call for %s did not round-trip a plan-shaped object "
                         "(got %r)" % (phase, run_id, _decision_dict(parsed)))

    last_visible = ""
    for turn in range(1, decision_count + 1):
        control = {"turnToken": "RUNTOKEN-%s#%d" % (run_id, turn), "candidateIndex": turn % 3}
        status, parsed, elapsed = post(port, request_body(run_id, decision_content(run_id, control)),
                                        client_timeout_s)
        decision = _decision_dict(parsed)
        log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                fallback=bool(decision.get("fallback")), turn=turn)
        if decision.get("fallback"):
            findings.append("%s: turn %d of %s unexpectedly returned fallback=true" % (
                phase, turn, run_id))
        last_visible = decision.get("soakVisible", "")

    status, parsed, elapsed = post(port, end_session_body(run_id), client_timeout_s)
    log.add(phase=phase, run_id=run_id, kind="endSession", elapsed_s=elapsed, status=status,
            fallback=False)
    return last_visible


def _decision_dict(parsed) -> dict:
    try:
        return json.loads(parsed["choices"][0]["message"]["content"])
    except (KeyError, IndexError, TypeError, json.JSONDecodeError):
        return {}


def _extract_fallback(parsed) -> bool:
    return bool(_decision_dict(parsed).get("fallback"))


def check_trim_correctness(run_id, decision_count, last_visible, findings, max_session_turns):
    """Validates MAX_SESSION_TURNS trimming purely from the LAST decision call's echoed
    "soakVisible" set (see fake_codex_worker) - the set of RUNTOKEN-<run_id>#<turn> markers the
    proxy actually replayed as history ahead of that call, plus that call's own fresh turn."""
    tokens = [int(m.group(1)) for m in re.finditer(
        r"%s#(\d+)" % re.escape(run_id), last_visible)]
    tokens = sorted(set(tokens))
    if decision_count <= max_session_turns:
        findings.append("%s: skipped trim check, only %d decisions sent (<= cap %d)" % (
            run_id, decision_count, max_session_turns))
        return
    # The final call (turn = decision_count) sees history from turns 1..decision_count-1, capped
    # at max_session_turns, oldest-first-trimmed - plus its own about-to-be-recorded turn token,
    # which is always present in its own fresh prompt content regardless of history.
    expected_history_min = decision_count - max_session_turns
    expected_history_max = decision_count - 1
    history_tokens = [t for t in tokens if t != decision_count]
    ok = True
    if len(history_tokens) > max_session_turns:
        findings.append("%s: FAIL retained history turn count %d exceeds MAX_SESSION_TURNS %d" % (
            run_id, len(history_tokens), max_session_turns))
        ok = False
    if history_tokens and min(history_tokens) < expected_history_min:
        findings.append("%s: FAIL oldest retained turn %d is older than expected floor %d "
                         "(trimming did not drop the oldest turns first)" % (
                             run_id, min(history_tokens), expected_history_min))
        ok = False
    if history_tokens and max(history_tokens) != expected_history_max:
        findings.append("%s: FAIL newest retained turn %d != expected %d" % (
            run_id, max(history_tokens), expected_history_max))
        ok = False
    if ok:
        findings.append("%s: PASS trim check - retained turns %d..%d (%d turns, cap %d)" % (
            run_id, min(history_tokens) if history_tokens else decision_count,
            expected_history_max, len(history_tokens), max_session_turns))


def phase_sustained_multi_episode(port, log, client_timeout_s, max_session_turns, findings):
    """Several seeds' worth of decisions run back-to-back against one long-lived proxy instance,
    mirroring a real multi-seed benchmark pass (see run-goal-benchmark-multiseed.ps1). Episode
    lengths are grounded in the proxy's own MAX_SESSION_TURNS comment ("real realtime goal runs
    observed this session ran 5-15 steps; occasional recovery re-decisions can roughly double
    that") and the task's B1 (90-120s)/B2 (up to 6min) windows at that same rough per-decision
    cadence; b2-seed-1 is deliberately long enough to exceed MAX_SESSION_TURNS and doubles as the
    trim-correctness check."""
    phase = "sustained-multi-episode"
    episodes = [("b1-seed-1", 25), ("b1-seed-2", 18), ("b2-seed-1", 45), ("b2-seed-2", 32)]
    for run_id, decision_count in episodes:
        last_visible = run_episode(port, log, phase, run_id, decision_count, client_timeout_s, findings)
        check_trim_correctness(run_id, decision_count, last_visible, findings, max_session_turns)


# =====================================================================================
# Phase 2: concurrent requests for different runIds - cross-contamination check
# =====================================================================================

def phase_concurrency_isolation(port, log, client_timeout_s, findings):
    """Fires several runIds' episodes concurrently (interleaved via threads) and checks, via the
    same RUNTOKEN echo technique, that no run's replayed history ever contains another run's
    token - i.e. that SESSIONS_LOCK genuinely isolates sessions under real concurrent traffic, not
    merely that nothing crashed."""
    phase = "concurrency-isolation"
    run_ids = ["concurrent-seed-a", "concurrent-seed-b", "concurrent-seed-c"]
    decisions_per_run = 15
    foreign_hits = []
    lock = threading.Lock()

    def worker(run_id):
        for turn in range(1, decisions_per_run + 1):
            control = {"turnToken": "RUNTOKEN-%s#%d" % (run_id, turn)}
            status, parsed, elapsed = post(
                port, request_body(run_id, decision_content(run_id, control)), client_timeout_s)
            decision = _decision_dict(parsed)
            log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                    fallback=bool(decision.get("fallback")), turn=turn)
            visible = decision.get("soakVisible", "")
            for other in run_ids:
                if other != run_id and other in visible:
                    with lock:
                        foreign_hits.append("%s saw a token belonging to %s at turn %d: %s" % (
                            run_id, other, turn, visible))
        post(port, end_session_body(run_id), client_timeout_s)

    threads = [threading.Thread(target=worker, args=(run_id,)) for run_id in run_ids]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    if foreign_hits:
        findings.append("FAIL cross-contamination detected between concurrent runIds:")
        findings.extend("  " + hit for hit in foreign_hits)
    else:
        findings.append("PASS no cross-contamination across %d concurrent runIds x %d decisions "
                         "each" % (len(run_ids), decisions_per_run))


# =====================================================================================
# Phase 3: request timeout scenario
# =====================================================================================

def phase_timeout(log_dir, findings):
    """Starts a dedicated proxy instance with a short CALL_TIMEOUT_S and a fake-backend call that
    deliberately sleeps past it, to verify the documented timeout-guarded-independent-call design
    actually degrades to fallback=true rather than hanging or crashing the proxy. Uses a shorter
    sleep than the parent's Popen.kill() reaping concern would need (see module notes) so any
    orphaned child from the extra cmd.exe shim layer self-terminates quickly; this orphaning is a
    test-harness artifact of the batch-shim indirection, not a reflection of production behavior,
    where CODEX_EXE names the real codex executable directly with no intermediate shell."""
    phase = "timeout"
    call_timeout_s = 2
    tmp_dir = Path(tempfile.mkdtemp(prefix="lodestone-soak-timeout-"))
    shim = write_fake_codex_shim(tmp_dir)
    instance = start_proxy("timeout", {
        "CODEX_EXE": str(shim),
        "LODESTONE_PROXY_CALL_TIMEOUT_S": str(call_timeout_s),
    }, log_dir)
    log = CallLog()
    try:
        run_id = "timeout-probe"
        # Baseline: a normal, fast call on this same instance must NOT show fallback.
        control = {"turnToken": "RUNTOKEN-%s#1" % run_id, "sleepS": 0.05}
        status, parsed, elapsed = post(port=instance.port,
                                        payload=request_body(run_id, decision_content(run_id, control)),
                                        timeout_s=call_timeout_s + 15)
        baseline_fallback = _extract_fallback(parsed)
        log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                fallback=baseline_fallback, turn=1)
        if baseline_fallback:
            findings.append("FAIL baseline fast call on the timeout instance unexpectedly "
                             "returned fallback=true")

        # The actual timeout trigger: sleep well past call_timeout_s.
        control = {"turnToken": "RUNTOKEN-%s#2" % run_id, "sleepS": call_timeout_s + 4}
        status, parsed, elapsed = post(port=instance.port,
                                        payload=request_body(run_id, decision_content(run_id, control)),
                                        timeout_s=call_timeout_s + 15)
        timed_out_fallback = _extract_fallback(parsed)
        log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                fallback=timed_out_fallback, turn=2, note="deliberate-timeout")
        if not timed_out_fallback:
            findings.append("FAIL a call that slept %ds against a %ds CALL_TIMEOUT_S did not "
                             "return fallback=true" % (call_timeout_s + 4, call_timeout_s))
        elif elapsed > call_timeout_s + 10:
            findings.append("FAIL timed-out call took %.1fs to come back, far beyond "
                             "CALL_TIMEOUT_S=%ds - the timeout guard may not be tight" % (
                                 elapsed, call_timeout_s))
        else:
            findings.append("PASS timeout scenario: sleepS=%d against CALL_TIMEOUT_S=%d returned "
                             "fallback=true in %.1fs" % (call_timeout_s + 4, call_timeout_s, elapsed))

        # A call for the SAME runId right after must not still be poisoned - reset_session() on
        # error should have dropped whatever history existed, and the proxy must still be alive.
        control = {"turnToken": "RUNTOKEN-%s#3" % run_id, "sleepS": 0.05}
        status, parsed, elapsed = post(port=instance.port,
                                        payload=request_body(run_id, decision_content(run_id, control)),
                                        timeout_s=call_timeout_s + 15)
        recovered_fallback = _extract_fallback(parsed)
        log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                fallback=recovered_fallback, turn=3, note="post-timeout-recovery")
        if recovered_fallback:
            findings.append("FAIL the proxy did not recover after a timeout - the next call for "
                             "the same runId also returned fallback=true")
        else:
            findings.append("PASS proxy served a genuine decision again immediately after a "
                             "timeout on the same runId (no lingering wedge)")
    finally:
        instance.stop()
        shutil.rmtree(tmp_dir, ignore_errors=True)
    return log


# =====================================================================================
# Phase 4: abandoned session - idle-sweep cleanup
# =====================================================================================

def phase_idle_sweep(log_dir, findings):
    """Starts a dedicated proxy instance with a short SESSION_IDLE_TTL_S/SWEEP_INTERVAL_S and
    floods it with many abandoned runIds (decisions sent, endSession deliberately never sent - the
    scenario the task flags as most likely under-tested: a benchmark harness killing a client
    mid-run on a timeout never reaches HttpJsonGoalModelProvider.endSession()'s finally block).
    Verifies, via the RUNTOKEN echo technique, that a sampled abandoned runId's history is
    genuinely gone (not merely capped) after the TTL elapses, and samples process RSS as
    supporting (not authoritative - Python/OS memory reuse means RSS need not shrink even after
    the dict entries are freed) evidence that the flood did not runaway-grow memory."""
    phase = "idle-sweep"
    tmp_dir = Path(tempfile.mkdtemp(prefix="lodestone-soak-sweep-"))
    shim = write_fake_codex_shim(tmp_dir)
    ttl_s = 3
    instance = start_proxy("idle-sweep", {
        "CODEX_EXE": str(shim),
        "LODESTONE_PROXY_SESSION_IDLE_TTL_S": str(ttl_s),
        "LODESTONE_PROXY_SESSION_SWEEP_INTERVAL_S": "1",
    }, log_dir)
    log = CallLog()
    try:
        abandoned_run_ids = ["abandoned-%02d" % i for i in range(30)]
        rss_before = instance.rss_bytes()
        for run_id in abandoned_run_ids:
            for turn in range(1, 5):
                control = {"turnToken": "RUNTOKEN-%s#%d" % (run_id, turn)}
                status, parsed, elapsed = post(
                    instance.port, request_body(run_id, decision_content(run_id, control)), 15)
                log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed,
                        status=status, fallback=bool(_decision_dict(parsed).get("fallback")))
            # Deliberately no endSession call here - this is the abandoned-run scenario.
        rss_after_load = instance.rss_bytes()

        # Wait past the idle TTL (plus one full sweep interval of margin) with no further traffic
        # for these runIds, then let the background sweep thread do its unattended work.
        time.sleep(ttl_s + 3)
        rss_after_sweep = instance.rss_bytes()

        sample_ids = abandoned_run_ids[::6]  # a spread sample, not all 30, to keep this quick
        still_has_history = []
        for run_id in sample_ids:
            control = {"turnToken": "RUNTOKEN-%s#99" % run_id}
            status, parsed, elapsed = post(
                instance.port, request_body(run_id, decision_content(run_id, control)), 15)
            decision = _decision_dict(parsed)
            log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed, status=status,
                    fallback=bool(decision.get("fallback")), note="post-ttl-reprobe")
            visible = decision.get("soakVisible", "")
            history_turns = [m for m in re.finditer(r"%s#(\d+)" % re.escape(run_id), visible)
                              if m.group(1) != "99"]
            if history_turns:
                still_has_history.append(run_id)

        if still_has_history:
            findings.append("FAIL idle-sweep did not evict abandoned sessions still carrying "
                             "history after %ds past a %ds TTL: %s" % (
                                 ttl_s + 3, ttl_s, ", ".join(still_has_history)))
        else:
            findings.append("PASS idle-sweep evicted all %d sampled abandoned sessions "
                             "(never sent endSession) after %ds idle past a %ds TTL" % (
                                 len(sample_ids), ttl_s + 3, ttl_s))

        if rss_before and rss_after_load and rss_after_sweep:
            findings.append("INFO RSS (informational only, see caveat above): before-load=%.1fMB "
                             "after-load(%d abandoned runIds)=%.1fMB after-sweep-wait=%.1fMB" % (
                                 rss_before / 1e6, len(abandoned_run_ids), rss_after_load / 1e6,
                                 rss_after_sweep / 1e6))
        else:
            findings.append("INFO RSS sampling unavailable (psutil missing or process exited)")
    finally:
        instance.stop()
        shutil.rmtree(tmp_dir, ignore_errors=True)
    return log


# =====================================================================================
# Phase 5: real codex/gpt-5.4-mini episodes - genuine end-to-end latency
# =====================================================================================

def real_decision_content(run_id, turn) -> str:
    """Clean GoalDecisionRequest-shaped content with no __SOAK_CONTROL__ suffix - this is sent to
    the REAL model, so it should look exactly like what HttpJsonGoalModelProvider actually sends."""
    body = {
        "goal": "survival.spawn-gauntlet", "mode": "REALTIME",
        "policy": {"intelligence": "adaptive-v1", "safety": "high",
                   "chooseOnlyEligibleCandidates": True, "preferPlayerSafetyWhenHigh": True,
                   "useFreshWorldObservation": True},
        "state": {"player": {"health": 20.0, "maxHealth": 20.0, "onFire": False, "inLava": False,
                              "inWater": False, "fallDistance": 0.0, "position": {"x": 0, "y": 64, "z": turn}},
                  "nearbyThreats": [], "localNavigation": {"forwardDropRisk": False}},
        "candidates": [
            {"id": "observe", "kind": "observe", "capability": "", "input": {},
             "observeAfter": True, "preconditions": [], "assertionCount": 0},
            {"id": "move-forward", "kind": "invoke", "capability": "minecraft.player.move",
             "input": {"x": 0, "y": 64, "z": turn + 1}, "observeAfter": True,
             "preconditions": [], "assertionCount": 1},
        ],
        "response": "Return JSON only: {candidateIndex: integer, rationale: string}.",
    }
    return json.dumps(body)


def real_plan_content() -> str:
    body = {
        "goal": "survival.spawn-gauntlet", "mode": "REALTIME",
        "policy": {"intelligence": "adaptive-v1", "safety": "high", "observation": "full",
                   "combatPolicy": "defensive", "allowBlockBreaking": True,
                   "allowBlockPlacing": True, "allowCommands": False},
        "builtInTasks": [],
        "planContract": {"segments": "non-empty array, at most 16 segments"},
        "prerequisiteRules": {},
        "instructions": "Return JSON only. Create a bounded declarative plan.",
        "response": "Return the plan object directly: {id, goal, metadata, segments}.",
    }
    return json.dumps(body)


def phase_real_episodes(log_dir, episodes, decisions_per_episode, findings):
    if shutil.which("codex") is None and shutil.which("codex.exe") is None:
        findings.append("SKIPPED real-episode phase: `codex` executable not found on PATH")
        return CallLog()
    phase = "real-episodes"
    instance = start_proxy("real", {}, log_dir)  # CODEX_EXE unset -> defaults to real "codex"
    log = CallLog()
    try:
        for episode_index in range(episodes):
            run_id = "real-episode-%d" % (episode_index + 1)
            status, parsed, elapsed = post(instance.port, request_body(run_id, real_plan_content()),
                                            timeout_s=60)
            log.add(phase=phase, run_id=run_id, kind="plan", elapsed_s=elapsed, status=status,
                    fallback=_extract_fallback(parsed))
            if _extract_fallback(parsed):
                findings.append("%s: REAL plan call for %s returned fallback=true - either the "
                                 "model did not comply with the plan-shape instructions or there "
                                 "is a real integration issue" % (phase, run_id))

            for turn in range(1, decisions_per_episode + 1):
                status, parsed, elapsed = post(
                    instance.port, request_body(run_id, real_decision_content(run_id, turn)),
                    timeout_s=45)
                decision = _decision_dict(parsed)
                log.add(phase=phase, run_id=run_id, kind="decision", elapsed_s=elapsed,
                        status=status, fallback=bool(decision.get("fallback")), turn=turn)
                if decision.get("fallback"):
                    findings.append("%s: REAL turn %d of %s returned fallback=true (rationale=%r)" % (
                        phase, turn, run_id, decision.get("rationale")))
            post(instance.port, end_session_body(run_id), timeout_s=15)
        findings.append("INFO real-episode phase completed %d episode(s) x %d decision(s) each "
                         "against the real codex/gpt-5.4-mini backend" % (episodes, decisions_per_episode))
    finally:
        instance.stop()
    return log


# =====================================================================================
# Report
# =====================================================================================

def print_report(all_logs, findings, args):
    print("\n" + "=" * 88)
    print("gpt54-mini-goal-model-proxy soak test report")
    print("=" * 88)

    print("\n-- Latency distribution by phase (seconds) --")
    combined = []
    for log in all_logs:
        for phase in sorted({r["phase"] for r in log.records}):
            elapsed = [r["elapsed_s"] for r in log.by_phase(phase)]
            combined.extend(elapsed)
            stats = latency_stats(elapsed)
            if stats:
                print("  %-24s n=%-4d min=%.3f median=%.3f mean=%.3f p95=%.3f max=%.3f" % (
                    phase, stats["n"], stats["min"], stats["median"], stats["mean"],
                    stats["p95"], stats["max"]))
    overall = latency_stats(combined)
    if overall:
        print("  %-24s n=%-4d min=%.3f median=%.3f mean=%.3f p95=%.3f max=%.3f" % (
            "OVERALL", overall["n"], overall["min"], overall["median"], overall["mean"],
            overall["p95"], overall["max"]))

    print("\n-- Fallback audit --")
    for log in all_logs:
        unexpected = [r for r in log.records if r["fallback"] and r["kind"] != "decision"]
        decision_fallbacks = [r for r in log.records if r["fallback"] and r["kind"] == "decision"]
        expected_fallback_phases = {"timeout"}
        for phase in sorted({r["phase"] for r in log.records}):
            phase_records = log.by_phase(phase)
            fallback_count = sum(1 for r in phase_records if r["fallback"])
            print("  %-24s calls=%-4d fallback=%d%s" % (
                phase, len(phase_records), fallback_count,
                "  (expected: this phase deliberately triggers one)" if phase in expected_fallback_phases and fallback_count else ""))

    print("\n-- Findings --")
    for finding in findings:
        print("  " + finding)

    failures = [f for f in findings if f.strip().startswith("FAIL")]
    print("\n" + "=" * 88)
    if failures:
        print("VERDICT: NOT READY - %d check(s) failed. See FAIL lines above." % len(failures))
    else:
        print("VERDICT: soak test passed all checks. See findings above for details "
              "(including any SKIPPED phases, e.g. if --skip-real was used).")
    print("=" * 88 + "\n")
    return len(failures) == 0


# =====================================================================================
# main
# =====================================================================================

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--skip-real", action="store_true",
                         help="Skip the real codex/gpt-5.4-mini episode phase entirely (fast, free).")
    parser.add_argument("--real-episodes", type=int, default=1,
                         help="Number of genuine end-to-end episodes to run against the real "
                              "codex backend (default: 1). Each makes real, billed API calls.")
    parser.add_argument("--real-decisions", type=int, default=8,
                         help="Decisions per real episode (default: 8).")
    parser.add_argument("--keep-logs", action="store_true",
                         help="Print the temp directory holding proxy logs instead of deleting it.")
    args = parser.parse_args()

    if not PROXY_PATH.exists():
        print("ERROR: could not find gpt54-mini-goal-model-proxy.py next to this script at %s" %
              PROXY_PATH, file=sys.stderr)
        return 2

    log_dir = Path(tempfile.mkdtemp(prefix="lodestone-soak-"))
    findings = []
    all_logs = []
    max_session_turns = int(os.environ.get("LODESTONE_PROXY_MAX_SESSION_TURNS", "24"))

    print("Proxy under test: %s" % PROXY_PATH)
    print("Log/artifact directory: %s" % log_dir)

    tmp_dir = Path(tempfile.mkdtemp(prefix="lodestone-soak-fake-"))
    shim = write_fake_codex_shim(tmp_dir)
    try:
        print("\n[1/4] Starting default-config proxy instance for sustained multi-episode load + "
              "MAX_SESSION_TURNS trim check + concurrency isolation...")
        default_instance = start_proxy("default", {"CODEX_EXE": str(shim)}, log_dir)
        log1 = CallLog()
        try:
            phase_sustained_multi_episode(default_instance.port, log1, client_timeout_s=45,
                                           max_session_turns=max_session_turns, findings=findings)
            phase_concurrency_isolation(default_instance.port, log1, client_timeout_s=45,
                                         findings=findings)
        finally:
            default_instance.stop()
        all_logs.append(log1)

        print("[2/4] Running dedicated timeout-scenario instance...")
        all_logs.append(phase_timeout(log_dir, findings))

        print("[3/4] Running dedicated idle-sweep (abandoned session) instance...")
        all_logs.append(phase_idle_sweep(log_dir, findings))

        if args.skip_real:
            findings.append("SKIPPED real-episode phase: --skip-real was passed")
        else:
            print("[4/4] Running %d real episode(s) x %d decisions against real codex/gpt-5.4-mini "
                  "(this makes real API calls and will take a while)..." % (
                      args.real_episodes, args.real_decisions))
            all_logs.append(phase_real_episodes(log_dir, args.real_episodes, args.real_decisions,
                                                 findings))
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        if args.keep_logs:
            print("\nProxy logs kept at: %s" % log_dir)
        else:
            shutil.rmtree(log_dir, ignore_errors=True)

    ok = print_report(all_logs, findings, args)
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--fake-codex-worker":
        sys.exit(fake_codex_worker(sys.argv[2:]))
    sys.exit(main())
