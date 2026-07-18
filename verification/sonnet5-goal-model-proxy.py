"""Small OpenAI-compatible bridge for Lodestone realtime goal decisions, backed by Claude Sonnet 5.

Mirrors gpt54-mini-goal-model-proxy.py's HTTP bridge shape exactly (same request/response
contract expected by HttpJsonGoalModelProvider), but shells out to the `claude` CLI's
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
            stream.write(message + "\n")


def choose(prompt: str, reasoning_effort: str) -> dict:
    try:
        command = [
            CLAUDE, "-p", "--model", MODEL, "--output-format", "json",
            "--effort", reasoning_effort, "--strict-mcp-config",
        ]
        completed = subprocess.run(
            command, cwd=os.path.expanduser("~"), input=prompt,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90,
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
        log("model=%s effort=%s candidate=%s" % (MODEL, reasoning_effort, json.dumps(decision, separators=(",", ":"))))
        return decision
    except Exception as error:
        log("model=%s effort=%s error=%s" % (MODEL, reasoning_effort, error))
        return {"candidateIndex": 0, "rationale": "bridge fallback after model error"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages", [])
        prompt = messages[-1].get("content", "") if messages else ""
        reasoning_effort = normalize_reasoning_effort(body.get("reasoning_effort"))
        decision = choose(prompt, reasoning_effort)
        response = {"choices": [{"message": {"content": json.dumps(decision)}}]}
        encoded = json.dumps(response).encode("utf-8")
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
