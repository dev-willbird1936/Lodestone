"""Single-user loopback settings page for Lodestone goals."""

from __future__ import annotations

import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
HTML = ROOT / "lodestone-goal-settings.html"
SETTINGS = ROOT / "lodestone-goal-settings.json"
CHOICES = {
    "mode": {"script", "realtime"},
    "intelligence": {"low", "medium", "high"},
    "safety": {"low", "medium", "high"},
    "selection": {"auto", "codex-cli", "claude-cli"},
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        if self.path == "/api/settings":
            body = SETTINGS.read_bytes() if SETTINGS.exists() else b"{}"
            return self.send_body(200, "application/json", body)
        if self.path in ("/", "/index.html"):
            return self.send_body(200, "text/html; charset=utf-8", HTML.read_bytes())
        self.send_error(404)

    def do_POST(self):  # noqa: N802
        if self.path != "/api/settings":
            return self.send_error(404)
        expected_origin = f"http://127.0.0.1:{self.server.server_port}"
        if self.headers.get("Origin") not in (None, expected_origin):
            return self.send_body(403, "application/json", b'{"error":"origin denied"}')
        if self.headers.get_content_type() != "application/json":
            return self.send_body(415, "application/json", b'{"error":"JSON required"}')
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 2 or size > 4096:
                raise ValueError("invalid settings size")
            value = json.loads(self.rfile.read(size))
            for field, choices in CHOICES.items():
                if value.get(field) not in choices:
                    raise ValueError(f"invalid {field}")
            for field in ("codexP95", "claudeP95"):
                raw = value.get(field, "")
                if raw != "" and int(raw) < 1:
                    raise ValueError(f"invalid {field}")
            SETTINGS.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
            self.send_body(200, "application/json", b'{"saved":true}')
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self.send_body(400, "application/json", json.dumps({"error": str(error)}).encode())

    def log_message(self, _format, *_args):
        return

    def send_body(self, status: int, content_type: str, body: bytes):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    url = f"http://127.0.0.1:{server.server_port}/"
    print(f"Lodestone Goal Settings: {url}")
    threading.Timer(0.2, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
