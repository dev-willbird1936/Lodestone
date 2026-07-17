"""Small OpenAI-compatible bridge for Lodestone realtime goal decisions."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import subprocess
import sys
import tempfile
import threading


MODEL = os.environ.get("LODESTONE_PROXY_MODEL", "gpt-5.4-mini")
CODEX = os.environ.get("CODEX_EXE", "codex")
LOG_PATH = os.environ.get("LODESTONE_PROXY_LOG", "")
LOG_LOCK = threading.Lock()

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
            stream.write(message + "\n")


def choose(prompt: str, reasoning_effort: str) -> dict:
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
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90,
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
        log("model=%s effort=%s candidate=%s" % (MODEL, reasoning_effort, json.dumps(decision, separators=(",", ":"))))
        return decision
    except Exception as error:
        log("model=%s effort=%s error=%s" % (MODEL, reasoning_effort, error))
        return {"candidateIndex": 0, "rationale": "bridge fallback after model error"}
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
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 37842
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log("listening port=%s model=%s" % (port, MODEL))
    server.serve_forever()
