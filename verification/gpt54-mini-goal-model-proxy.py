"""Small OpenAI-compatible bridge for Lodestone realtime goal decisions.

Persistence model (per-goal-run conversation continuity):

Each HTTP request from HttpJsonGoalModelProvider carries a "runId" - the same id as the owning
goal run's eventual GoalRunReport.runId(), stable for every decision/plan-synthesis call within
that one run (see GoalDecisionRequest#runId / GoalPlanRequest#runId on the Java side). This bridge
keeps an in-memory, runId-keyed history of ITS OWN PAST DECISIONS ONLY - candidateIndex + rationale
for each prior turn in that run - and injects a short summary of that history ahead of every new
call to codex. World state is never persisted here: the caller always sends a fresh, authoritative
world observation on every single call (see GoalDecisionState.project on the Java side), so this
bridge's memory is advisory context about the model's own past reasoning, never a cache of world
truth that could go stale. A run's history is discarded when GoalEngine.run() reaches a terminal
outcome (success, failure, or cancellation), via an explicit "endSession" request sent from
HttpJsonGoalModelProvider.endSession() in that run's finally block.

Each individual round trip to the model stays a bounded, timeout-guarded, independent `codex exec`
call, exactly as before - this deliberately does NOT become a long-lived interactive subprocess
held open across calls, since a wedged persistent process would become its own failure mode. Prior
turns are replayed as plain text context in the prompt of each fresh, independent call instead.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time


MODEL = os.environ.get("LODESTONE_PROXY_MODEL", "gpt-5.4-mini")
CODEX = os.environ.get("CODEX_EXE", "codex")
LOG_PATH = os.environ.get("LODESTONE_PROXY_LOG", "")
LOG_LOCK = threading.Lock()

# Re-examined per an advisor flag that the original 90s bound might be too long for a realtime
# per-step decision cadence. Measured directly this session (see verification/evidence and the
# live NeoForge tree-mining runs backing this change): individual low-effort `codex exec` calls
# through this exact bridge, with prompt sizes representative of real goal decisions (including a
# multi-turn history block), completed in ~9-15s, never close to 90s. 30s keeps roughly 2x headroom
# over the slowest observed real call while meaningfully tightening the worst case a genuinely
# wedged call can impose on a realtime goal loop, down from 90s.
CALL_TIMEOUT_S = int(os.environ.get("LODESTONE_PROXY_CALL_TIMEOUT_S", "30"))

# Bounds how many of a run's own prior decisions are replayed as context on each new call. Real
# realtime goal runs observed this session ran 5-15 steps; occasional recovery re-decisions can
# roughly double that. 24 comfortably covers that range with headroom while still bounding prompt
# growth for a pathological very-long-running goal - older turns fall off (oldest-first) once a
# run exceeds this many decisions, rather than growing the prompt without limit.
MAX_SESSION_TURNS = int(os.environ.get("LODESTONE_PROXY_MAX_SESSION_TURNS", "24"))

SESSIONS_LOCK = threading.Lock()
SESSIONS: dict[str, list[dict]] = {}

# Mirrors the Java-side HttpJsonGoalModelProvider.isValidReasoningEffort whitelist. Previously this
# bridge ignored whatever "reasoning_effort" the caller sent and always ran codex at a hardcoded
# "low" effort, which would have silently defeated the deliberate-v1 situational deliberation
# budget end-to-end for this backend: the caller could ask for "xhigh" on a safe decision and still
# get the fast/low-effort response. An unrecognized value still falls back to "low".
VALID_REASONING_EFFORTS = {"low", "medium", "high", "xhigh"}


def normalize_reasoning_effort(value) -> str:
    normalized = str(value).strip().lower() if value else "low"
    return normalized if normalized in VALID_REASONING_EFFORTS else "low"


def log(message: str) -> None:
    if not LOG_PATH:
        return
    with LOG_LOCK:
        with open(LOG_PATH, "a", encoding="utf-8") as stream:
            stream.write("%s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), message))


def session_history_block(run_id: str) -> str:
    """Render a run's own prior decisions as short advisory context, or "" if none exist yet."""
    if not run_id:
        return ""
    with SESSIONS_LOCK:
        history = list(SESSIONS.get(run_id, []))
    if not history:
        return ""
    lines = [
        "Your own prior decisions earlier in THIS SAME goal run, for continuity of your own "
        "reasoning only. This is NOT current world state - always trust the fresh state given "
        "below over anything implied here; a candidate you picked before may no longer exist or "
        "may no longer be the right choice now:",
    ]
    for index, turn in enumerate(history, start=1):
        lines.append("%d. candidateIndex=%s rationale=%s" % (
            index, turn.get("candidateIndex"), turn.get("rationale", "")))
    return "\n".join(lines) + "\n\n"


def record_session_turn(run_id: str, decision: dict) -> None:
    if not run_id:
        return
    with SESSIONS_LOCK:
        history = SESSIONS.setdefault(run_id, [])
        history.append({
            "candidateIndex": decision.get("candidateIndex"),
            "rationale": decision.get("rationale", ""),
        })
        if len(history) > MAX_SESSION_TURNS:
            del history[: len(history) - MAX_SESSION_TURNS]


def reset_session(run_id: str) -> None:
    """Drop a run's accumulated history. Used both for explicit end-of-run cleanup and to recover
    from a wedged/malformed call: a corrupted turn must not poison every subsequent decision in the
    run, and the next call reseeds itself for free since it always carries fresh world state
    regardless of what history (if any) is replayed alongside it."""
    if not run_id:
        return
    with SESSIONS_LOCK:
        SESSIONS.pop(run_id, None)


def render_messages(messages) -> str:
    """Bug fix: this bridge previously took messages[-1] only, silently discarding every other
    message in the array - the root cause of this bridge having zero conversation continuity even
    if a caller already sent multiple messages. Every message the caller sends is now used."""
    if not messages:
        return ""
    if len(messages) == 1:
        return str(messages[0].get("content", ""))
    parts = []
    for message in messages:
        role = message.get("role", "user")
        parts.append("[%s]\n%s" % (role, message.get("content", "")))
    return "\n\n".join(parts)


def choose(messages, reasoning_effort: str, run_id: str) -> dict:
    with SESSIONS_LOCK:
        prior_turns = len(SESSIONS.get(run_id, []))
    prompt = session_history_block(run_id) + render_messages(messages)
    output_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="lodestone-gpt54-", suffix=".txt", delete=False) as file:
            output_path = file.name
        command = [
            CODEX, "exec", "--ephemeral", "--ignore-user-config", "--ignore-rules",
            "--skip-git-repo-check", "--model", MODEL, "--sandbox", "read-only",
            "-c", 'model_reasoning_effort="%s"' % reasoning_effort, "--color", "never",
            "--output-last-message", output_path, "-",
        ]
        completed = subprocess.run(
            command, cwd=os.path.expanduser("~"), input=prompt,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=CALL_TIMEOUT_S,
            check=False,
        )
        answer = ""
        if os.path.exists(output_path):
            with open(output_path, "r", encoding="utf-8") as stream:
                answer = stream.read().strip()
        match = re.search(r"\{.*\}", answer, re.DOTALL)
        if not match:
            match = re.search(r"\{.*\}", completed.stdout, re.DOTALL)
        if not match:
            raise RuntimeError("model did not return a JSON object")
        decision = json.loads(match.group(0))
        if not isinstance(decision.get("candidateIndex"), int):
            raise RuntimeError("model response omitted integer candidateIndex")
        # historyTurns is how many of this SAME run's own prior decisions were replayed as context
        # ahead of this call's prompt (0 on a run's first call) - direct, log-visible proof that a
        # later call in a run actually carried earlier context, rather than starting from scratch
        # every time as this bridge did before.
        log("model=%s effort=%s runId=%s historyTurns=%d candidate=%s" % (
            MODEL, reasoning_effort, run_id, prior_turns, json.dumps(decision, separators=(",", ":"))))
        record_session_turn(run_id, decision)
        return decision
    except Exception as error:
        log("model=%s effort=%s runId=%s historyTurns=%d error=%s" % (
            MODEL, reasoning_effort, run_id, prior_turns, error))
        # A wedged or malformed call must not poison every later decision in this run with a
        # broken/partial history - drop whatever had accumulated so the next call starts clean.
        reset_session(run_id)
        # "fallback": true distinguishes a degraded, could-not-really-decide response from a
        # genuine model choice. Previously this bridge returned an identical-looking response on
        # error, so a wedged or failing model call could silently drive live player input via a
        # hardcoded first-candidate pick with no signal anywhere that it was degraded.
        return {"candidateIndex": 0, "rationale": "bridge fallback after model error", "fallback": True}
    finally:
        if output_path:
            try:
                os.unlink(output_path)
            except OSError:
                pass


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        run_id = str(body.get("runId") or "")

        if body.get("endSession"):
            reset_session(run_id)
            log("endSession runId=%s" % run_id)
            self._write_json({"ok": True})
            return

        messages = body.get("messages", [])
        reasoning_effort = normalize_reasoning_effort(body.get("reasoning_effort"))
        decision = choose(messages, reasoning_effort, run_id)
        response = {"choices": [{"message": {"content": json.dumps(decision)}}]}
        self._write_json(response)

    def _write_json(self, payload: dict) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):  # noqa: A002
        return


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 37842
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log("listening port=%s model=%s" % (port, MODEL))
    server.serve_forever()
