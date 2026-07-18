"""Small OpenAI-compatible bridge for Lodestone realtime goal decisions, backed by Claude Sonnet 5.

Mirrors gpt54-mini-goal-model-proxy.py's HTTP bridge shape exactly (same request/response
contract expected by HttpJsonGoalModelProvider, including the runId-keyed per-run decision-history
persistence, the "fallback": true degraded-decision flag, and the endSession cleanup request - see
that file's module docstring for the full persistence model), but shells out to the `claude` CLI's
non-interactive print mode instead of `codex exec`, since Codex CLI cannot address Claude models
(confirmed live: `codex exec --model claude-sonnet-5` is accepted by the CLI's own flag parser but
rejected server-side with "The 'claude-sonnet-5' model is not supported when using Codex with a
ChatGPT account.").

Caveat this proxy's caller should know: unlike the gpt54-mini bridge's `codex exec --sandbox
read-only` (a lightweight ephemeral call), a bare `claude -p` invocation in this environment loads
this machine's full interactive context (CLAUDE.md, skills, connected MCP tool schemas) before
answering, which was observed to cost several seconds of latency and real per-call token spend
even for a trivial one-line JSON request (~6-9s wall time, tens of thousands of cached input
tokens on the first call in a cache window). `--strict-mcp-config` is passed to at least drop
MCP tool schemas from that context. A genuinely low-latency deployment would additionally need
`--bare` (skip hooks/LSP/plugin-sync/CLAUDE.md discovery), but `--bare` requires
ANTHROPIC_API_KEY or apiKeyHelper auth per `claude --help`; OAuth/subscription auth (what this
machine has configured) is not read in `--bare` mode, so `--bare` was not usable here without
separately provisioning an API key. This bridge is therefore correctness-verified but not
latency-tuned; treat LODESTONE_GOAL_MODEL_P95_MS for this provider as illustrative, not measured.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time


MODEL = os.environ.get("LODESTONE_PROXY_MODEL", "sonnet")
# On Windows, `claude` resolves to a `.cmd` npm shim (confirmed live: `where claude` ->
# C:\Users\<user>\AppData\Roaming\npm\claude.cmd), and subprocess.run(["claude", ...]) without
# shell=True calls CreateProcess directly, which does not apply PATHEXT resolution the way
# cmd.exe does - it fails with WinError 2 ("system cannot find the file specified") even though
# `claude` runs fine from an interactive shell. shutil.which() resolves the same PATH search
# cmd.exe would, including the .cmd extension, so the real executable path is always used
# instead of the bare command name. codex.exe does not need this (it is a native .exe already).
CLAUDE = os.environ.get("CLAUDE_EXE") or shutil.which("claude") or "claude"
LOG_PATH = os.environ.get("LODESTONE_PROXY_LOG", "")
LOG_LOCK = threading.Lock()

# Unlike gpt54-mini-goal-model-proxy.py's CALL_TIMEOUT_S, this backend's default is left at the
# original 90s rather than measured down: this session's real timing measurements were all taken
# against the codex-backed bridge, and this file's own module docstring already documents that a
# bare `claude -p` call here loads this machine's full interactive context and was observed to be
# both slower and less consistent than the codex path - lowering this blind, without a fresh
# measurement of that call shape, risks spurious fallbacks. Still overridable per-deployment.
CALL_TIMEOUT_S = int(os.environ.get("LODESTONE_PROXY_CALL_TIMEOUT_S", "90"))

# See gpt54-mini-goal-model-proxy.py's MAX_SESSION_TURNS comment for the reasoning behind 24.
MAX_SESSION_TURNS = int(os.environ.get("LODESTONE_PROXY_MAX_SESSION_TURNS", "24"))

SESSIONS_LOCK = threading.Lock()
SESSIONS: dict[str, list[dict]] = {}

# Mirrors gpt54-mini-goal-model-proxy.py's whitelist, which mirrors the Java-side
# HttpJsonGoalModelProvider.isValidReasoningEffort. The Claude CLI's own --effort flag accepts an
# extra "max" tier that Lodestone's vocabulary does not use; that value is intentionally omitted
# here so this bridge's accepted set matches the goal-engine contract exactly.
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
    """See gpt54-mini-goal-model-proxy.py's copy of this function for the full rationale."""
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
    if not run_id:
        return
    with SESSIONS_LOCK:
        SESSIONS.pop(run_id, None)


def render_messages(messages) -> str:
    """Bug fix: this bridge previously took messages[-1] only, silently discarding every other
    message in the array. Every message the caller sends is now used."""
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
    try:
        command = [
            CLAUDE, "-p", "--model", MODEL, "--output-format", "json",
            "--effort", reasoning_effort, "--strict-mcp-config",
        ]
        completed = subprocess.run(
            command, cwd=os.path.expanduser("~"), input=prompt,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=CALL_TIMEOUT_S,
            check=False,
        )
        envelope = json.loads(completed.stdout)
        if envelope.get("is_error"):
            raise RuntimeError("claude CLI returned an error result: %s" % envelope.get("result"))
        answer = str(envelope.get("result", ""))
        match = re.search(r"\{.*\}", answer, re.DOTALL)
        if not match:
            raise RuntimeError("model did not return a JSON object")
        decision = json.loads(match.group(0))
        if not isinstance(decision.get("candidateIndex"), int):
            raise RuntimeError("model response omitted integer candidateIndex")
        log("model=%s effort=%s runId=%s historyTurns=%d candidate=%s" % (
            MODEL, reasoning_effort, run_id, prior_turns, json.dumps(decision, separators=(",", ":"))))
        record_session_turn(run_id, decision)
        return decision
    except Exception as error:
        log("model=%s effort=%s runId=%s historyTurns=%d error=%s" % (
            MODEL, reasoning_effort, run_id, prior_turns, error))
        reset_session(run_id)
        return {"candidateIndex": 0, "rationale": "bridge fallback after model error", "fallback": True}


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
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 37843
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log("listening port=%s model=%s" % (port, MODEL))
    server.serve_forever()
