"""Milestone 1 standalone model-orchestrated goal loop (replacement-architecture spike).

CAPABILITY_QUARANTINED recovery: an earlier run this session found and reported a severe,
reproducible bug - any mutating minecraft.* capability that commits a native mutation and then
hits an indeterminate outcome (timeout, schema violation, pathfinding failure after commit) leaves
EVERY mutating minecraft.* capability in the session permanently blocked (LodestoneRuntime's
SHARED_MUTATION_ORDER), not just the one that failed. That is now fixed on the Java side with a new
minecraft.session.reconcile capability (quiesce residual client activity, re-observe fresh state,
clear the quarantine only on confirmed success) - see run_loop_cli()'s automatic
CAPABILITY_QUARANTINED handling below, which calls it deterministically the moment that error code
is observed, rather than leaving recovery to the model's own discretion.

This is a throwaway-if-wrong proof of concept for the NEW goal-engine design the user chose to
replace GoalTaskCatalog/BuiltinGoalPlanner/GoalEngine with: instead of a hardcoded Java plan
executed step-by-step, a fast/low-thinking model decides every "subaction" itself, one real MCP
tool call at a time, by directly driving the same MCP capability surface any Lodestone client
already exposes. Nothing here wires into the existing `minecraft_goal` MCP tool and nothing about
GoalTaskCatalog/BuiltinGoalPlanner/GoalEngine is touched or deleted - this script only proves the
loop shape and the capability-schema -> Anthropic-tool-schema conversion work.

Architecture proved here:
  1. A REAL MCP client (LodestoneMcpClient below) speaks the exact JSON-RPC-over-HTTP protocol
     Lodestone's gateway exposes - initialize / notifications/initialized / tools/call, with the
     X-Lodestone-Token header and Mcp-Session-Id + MCP-Protocol-Version headers Lodestone's
     gateway.mcp-server McpGateway requires. See verification/adhoc-mcp-call.ps1 for the reference
     shape this reimplements in Python (not by shelling out to PowerShell).
  2. Tool-schema auto-generation: at startup, this script calls the real `lodestone_capabilities_list`
     MCP tool (NOT the internal capability id `lodestone.system.capabilities.list` that backs it -
     those are two different things) to enumerate every capability the live client has actually
     negotiated, then builds one Anthropic tool-use definition per `minecraft.*` capability whose
     listed availability is "available" OR "restricted" (excluding `minecraft.event.*`, which
     McpGateway#invoke rejects from the generic invoke path by design - those require the dedicated
     session-owned event tools instead, out of scope for this milestone). Every generated tool
     dispatches back through the generic `lodestone_capability_invoke` MCP tool, so this script
     never needs Lodestone's small curated COMPATIBILITY_ALIASES list of dedicated per-capability
     tool names - one dispatch path handles the entire capability surface uniformly.

     IMPORTANT CORRECTION vs. the original brief (found live this session, not assumed): including
     "restricted" alongside "available" was necessary, not optional. On the live NeoForge 1.21.1
     adapter, `minecraft.player.move`/`.look`/`.interact`/`minecraft.world.blocks.write`/
     `minecraft.chat.send`/etc. are listed with availability "restricted" and reason.code
     "native-permission" - but that reason is the STATIC descriptor default from
     protocol/catalog/core-capabilities.json, carried through verbatim by NeoForgeAdapter#adapt
     (adapters/neoforge/1.21.1/.../NeoForgeAdapter.java's `adapt()`), not a live per-session
     authorization check. Directly invoking `minecraft.player.move` via `lodestone_capability_invoke`
     against this same session returned status "ok" with real movement output. Filtering to only
     "available" (as the brief originally implied by treating the listing's availability as
     authoritative) would have silently hidden the exact subactions - movement and mining - this
     milestone's goal depends on. See build_tool_catalog() below for the corrected filter.
  3. Realtime-only decision loop: one Anthropic Messages API turn -> zero or more tool calls
     dispatched for real against the live client -> tool results returned to the model -> repeat.
     No script-mode batching, no safety-tier injection - both explicitly out of scope this
     milestone. The model itself decides when to observe (nearby blocks/entities/inventory), when
     to navigate (it can just call `minecraft_goal_navigation_safe-waypoint`... see NAME NOTE
     below), when to mine, and when it believes the goal is done; nothing about that sequencing is
     hardcoded in this script.

     NAME NOTE: capability ids contain '.' and this script converts '.' -> '_' for Anthropic tool
     names (Anthropic tool names must match ^[a-zA-Z0-9_-]{1,128}$, and capability ids contain '.'
     which is not allowed). `minecraft.goal.navigation.safe-waypoint` becomes the tool name
     `minecraft_goal_navigation_safe-waypoint` (the '-' in "safe-waypoint" is left as-is, since '-'
     is legal in Anthropic tool names).
  4. Every model turn (full request context size, response content blocks, every tool call
     requested and the real tool result returned) is appended to a JSONL trace file under
     verification/evidence/goal-orchestrator-milestone1/ - that trace file, not "the goal
     succeeded", is this milestone's actual deliverable per the brief.

Model backend (two, selectable via --backend, "cli" is the default): this milestone was originally
specced against the raw Anthropic Messages API directly via the `anthropic` Python SDK (the "api"
backend, run_loop() below) - native `tools=`/`tool_use` blocks, model id `claude-sonnet-5`,
`output_config.effort="low"`. That path is fully implemented and stays in this file, but it is
BLOCKED end to end on this machine: exhaustively confirmed (env vars in Process/User/Machine scope,
this machine's agent-env.json credential config, `ant` CLI presence, OAuth profile directories, repo
.env files - all absent) that no ANTHROPIC_API_KEY/ANTHROPIC_AUTH_TOKEN/OAuth profile exists here,
and the coordinator independently confirmed the same. Per explicit coordinator direction mid-
milestone, the DEFAULT backend for actually running this milestone is now "cli" (run_loop_cli()
below): shell out to the `claude` CLI's non-interactive print mode, reusing
verification/sonnet5-goal-model-proxy.py's exact proven invocation shape (`claude -p --model sonnet
--output-format json --effort <level> --strict-mcp-config`, JSON piped via stdin, response parsed
from the `result` field's embedded `{...}` via the same greedy-DOTALL regex that proxy uses) -
subscription/OAuth auth, no API key needed. Smoke-tested directly this session: `sonnet` resolves
to `claude-sonnet-5` per the real response's `modelUsage` key, ~5.6s wall time on a cold cache,
`is_error:false`, clean JSON round-trip.

KNOWN, ACCEPTED TRADEOFF (explicitly flagged per coordinator instruction, so it is not lost once a
real API key becomes available and the "api" backend can be used instead): `claude -p` loads this
machine's full interactive context (CLAUDE.md, skills, connected MCP tool schemas) before answering
every single call, even with `--strict-mcp-config` dropping MCP tool schemas specifically - the
smoke test above shows `cache_creation_input_tokens: 28133` on a cold call and a real
`total_cost_usd` per call. That is exactly the overhead this milestone's original brief said to
avoid ("too much overhead per turn... `claude -p` loading full interactive context"). It is being
paid deliberately here to get a genuine, real-model-backed result now rather than stay blocked on
the API-key gap. The "api" backend (no such overhead, true native tool-use, real per-token cost
only) should be the one actually adopted once a key exists - do not let "cli" quietly become
permanent just because it works today.

Because `claude -p` is a stateless, independent subprocess per call (no server-side session
continuity for this bridge-style usage), run_loop_cli() cannot use the Messages API's native
multi-turn `messages=[...]` + `tool_use`/`tool_result` content-block protocol. Instead, exactly like
sonnet5-goal-model-proxy.py's own candidateIndex/rationale text-embedded-JSON pattern, every turn's
full prompt is rebuilt from scratch: the goal, a text rendering of the entire generated tool
catalog (name/description/schema), and a full replay of every prior (decision, tool result) pair
this run - the model is asked to reply with ONLY one JSON object shaped
`{"tool": "<name-or-null>", "arguments": {...}, "done": bool, "rationale": "..."}`, which is
regex-extracted and dispatched through the same LodestoneMcpClient.invoke_capability() path as the
api backend. See build_cli_prompt()/call_claude_cli()/run_loop_cli() below.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any

import requests

# `anthropic` is ONLY needed by the --backend api path (run_loop() below uses it for type hints
# and anthropic.Anthropic() construction). It is deliberately NOT imported at module load time: a
# top-level `import anthropic` would make the default --backend cli path (the one actually used
# this milestone, per coordinator direction - no API key available) fail to even start on any
# machine where the anthropic package isn't installed, for a backend that path never touches. See
# TYPE_CHECKING below for static analysis without a runtime dependency, and main()'s "api" branch
# for the actual lazy import.
if TYPE_CHECKING:
    import anthropic


PROTOCOL_VERSION = "2025-11-25"
DEFAULT_MODEL = "claude-sonnet-5"  # "api" backend model id
DEFAULT_CLI_MODEL = "sonnet"  # "cli" backend model alias - confirmed live this session to resolve
# to claude-sonnet-5 (see the real `claude -p --output-format json` response's `modelUsage` key)
DEFAULT_GOAL = "mine one log from the nearest tree"

# Raised from the original 20 (task #11): trace-b5227c321af8.jsonl ran a full 30 turns (already
# raised once via --max-turns 30 on the CLI, not this default) and still hit max_turns_reached one
# navigation hop short of ever attempting to mine - by turn 30 it had reached tree-adjacent block
# coordinates but never issued a single interact/mine call. That run also spent roughly a third of
# its turns recovering from 3 separate CAPABILITY_QUARANTINED events (each costs a
# player_context_read + a retried navigation call, i.e. 2+ turns per incident) - now fixed
# separately (minecraft.session.reconcile), but navigation retries in general still consume turns
# even without a quarantine involved (see the same trace's OUTCOME_INDETERMINATE-only turns 5 and
# 17). 60 gives roughly 2x the headroom that still weren't enough, while staying a bounded, finite
# budget rather than open-ended.
DEFAULT_MAX_TURNS = 60

# See sonnet5-goal-model-proxy.py's own CLAUDE resolution comment: on Windows, `claude` resolves to
# a `.cmd` npm shim, and subprocess.run(["claude", ...]) without shell=True calls CreateProcess
# directly, which does not apply PATHEXT resolution the way cmd.exe does - it fails with WinError 2
# even though `claude` runs fine from an interactive shell. shutil.which() resolves the same PATH
# search cmd.exe would, including the .cmd extension.
CLAUDE_EXE = os.environ.get("CLAUDE_EXE") or shutil.which("claude") or "claude"

# Mirrors sonnet5-goal-model-proxy.py's CALL_TIMEOUT_S default and its own documented rationale: a
# bare `claude -p` call here loads this machine's full interactive context and was observed (both
# in that proxy's own smoke test and this script's own smoke test this session) to take single-
# digit seconds on a cold cache; 90s leaves generous headroom without a fresh measurement across
# many turns.
CLI_CALL_TIMEOUT_S = int(os.environ.get("LODESTONE_ORCHESTRATOR_CLI_TIMEOUT_S", "90"))

# Matches sonnet5-goal-model-proxy.py's VALID_REASONING_EFFORTS exactly - the claude CLI's own
# --effort flag accepts an extra "max" tier that this set intentionally omits for the cli backend,
# to match the proxy's own accepted vocabulary. The "api" backend additionally accepts "max" (see
# --effort argparse choices in main()).
VALID_CLI_EFFORTS = {"low", "medium", "high", "xhigh"}

# `minecraft.event.*` capabilities are explicitly rejected by McpGateway#invoke - see
# gateway/mcp-server/src/main/java/dev/lodestone/gateway/McpGateway.java's invoke() method, which
# throws GatewayException(-32602, "event capabilities must use the session-owned MCP event tools").
# They are out of scope for this milestone regardless (noted in the brief for a later milestone),
# so they are filtered out of the generated tool catalog rather than generating tools that would
# always fail if the model tried to call them.
#
# minecraft.goal.survival.*/.combat.*/.creative.* are the OLD monolithic native goal actors
# (GoalTaskCatalog/BuiltinGoalPlanner-era "just do the whole task" capabilities) that this entire
# redesign exists to replace with model-composed subactions - they must be EXCLUDED deliberately,
# not left available "because the filter happens to allow restricted/available capabilities
# through". Confirmed live this session (coordinator caught this, not self-discovered): with these
# visible, the model reliably picks the shortcut instead of composing real subactions (every one of
# turns 1/2/4 in trace-d2dcbcd455b4.jsonl called minecraft_goal_survival_wooden-axe-tree instead of
# player_move/interact/etc). minecraft.goal.navigation.safe-waypoint is the one deliberate
# exception - it is a reusable pathfinding primitive the model is meant to call directly (per the
# coordinator's original brief), not task-completion planner logic, so only the survival/combat/
# creative goal families are excluded here, not the whole minecraft.goal.* namespace.
#
# minecraft.session.* (specifically minecraft.session.reconcile, the CAPABILITY_QUARANTINED
# recovery capability) is excluded for the opposite reason: it is harness-owned, deterministic
# recovery logic (see the CAPABILITY_QUARANTINED handling in run_loop_cli() below), not a subaction
# choice offered to the model. A live run showed the model burning several turns testing individual
# tools and reasoning (sometimes wrongly) about the failure's scope before it could have discovered
# and chosen a recovery tool itself; the harness now reacts to the exact error code immediately and
# reliably instead of leaving that to the model's discretion.
EXCLUDED_CAPABILITY_PREFIXES = (
    "minecraft.event.",
    "minecraft.goal.survival.",
    "minecraft.goal.combat.",
    "minecraft.goal.creative.",
    "minecraft.session.",
)

# --- Minimal between-turn safety guard (task #11) ------------------------------------------------
# Two prior live full-run attempts (trace-b5227c321af8.jsonl, trace-3eb8d0d2ad19.jsonl) both failed
# to complete "mine one log" for reasons unrelated to the CAPABILITY_QUARANTINED bug: one ran out of
# turn budget just short of mining, the other died to fall damage around turn 11-12. This section
# addresses the second failure mode with a deliberately small, reactive check - NOT the full
# safety-tier system being built separately in verification/goal_orchestrator_draft/ (not touched
# here). It mirrors the SHAPE of adapters/neoforge/1.21.1/.../NeoForgeGoalPolicy.java's
# fallProtectionEnabled() - a single boolean gate off live state, not a tiered policy object - but
# applied at the harness's own decision loop rather than inside a native goal actor: after every
# turn, the harness itself calls minecraft.player.state.read directly (permissions:["observe"],
# sideEffect:"none", rateLimit 30/1000ms - cheap, and NOT a model turn, so it costs no turn budget)
# and, if health dropped sharply or is critically low, injects a corrective warning into the
# replayed history for the model's next turn. It never blocks or overrides the model's tool choice,
# matching the existing CAPABILITY_QUARANTINED auto-reconcile pattern right below: the harness acts/
# observes and informs, the model still decides.
#
# The exact death in trace-3eb8d0d2ad19.jsonl: the model never once called minecraft.player.state.read
# in turns 1-9 (health last confirmed 20.0 at turn 1), then at turn 10 called
# minecraft_player_move({"forward":1,"jump":true,"sprint":true,"durationMs":2000}) - a raw jump-sprint
# move, which unlike minecraft_goal_navigation_safe-waypoint performs no fall/hazard checking - and
# turn 11's health read came back 0.0. A per-turn poll cannot always outrun a single instantly-fatal
# fall, so this also flags that exact risky pattern (raw movement with jump set) proactively, right
# after it is dispatched, rather than relying only on the reactive health delta.
HEALTH_READ_CAPABILITY = "minecraft.player.state.read"
CRITICAL_HEALTH = 6.0  # <= 3 hearts
SHARP_HEALTH_DROP = 4.0  # a single fall/hit/hazard doing >= this much damage in one turn is never incidental


def read_player_health(mcp_client: LodestoneMcpClient) -> tuple[float | None, dict[str, Any]]:
    """Best-effort health poll for the between-turn safety guard below. Returns (health-or-None, raw
    tool result) - None on any failure (capability not yet available, transient transport error,
    missing/non-numeric field), so callers must treat a None health as "unknown", never as "full" or
    "zero".
    """
    result = mcp_client.invoke_capability(HEALTH_READ_CAPABILITY, {})
    if result.get("status") != "ok" or not isinstance(result.get("output"), dict):
        return None, result
    health = result["output"].get("health")
    if not isinstance(health, (int, float)):
        return None, result
    return float(health), result


class LodestoneMcpError(RuntimeError):
    """Raised for any MCP-level failure: transport, protocol, or an RPC-level `error` field."""


class LodestoneMcpClient:
    """A real MCP client over JSON-RPC 2.0 / HTTP, matching verification/adhoc-mcp-call.ps1's shape.

    Reimplemented fresh in Python per this milestone's brief (not shelling out to PowerShell).
    Session continuity: the gateway mints an `Mcp-Session-Id` on the `initialize` response; every
    subsequent request must echo it back plus `MCP-Protocol-Version`, exactly like the existing
    PowerShell harnesses already do.
    """

    def __init__(self, port: int, token: str, timeout_s: float = 60.0) -> None:
        self.base_url = f"http://127.0.0.1:{port}/mcp"
        self.token = token
        self.timeout_s = timeout_s
        self.session_id: str | None = None
        self._request_id = 0
        self._http = requests.Session()

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "X-Lodestone-Token": self.token}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
            headers["MCP-Protocol-Version"] = PROTOCOL_VERSION
        return headers

    def _rpc(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        self._request_id += 1
        body = {"jsonrpc": "2.0", "id": self._request_id, "method": method, "params": params or {}}
        response = self._http.post(self.base_url, headers=self._headers(), json=body, timeout=self.timeout_s)
        session_header = response.headers.get("Mcp-Session-Id")
        if session_header:
            self.session_id = session_header
        response.raise_for_status()
        if not response.content:
            # Confirmed live against Lodestone's gateway: "notifications/initialized" (a genuine
            # JSON-RPC notification, not a request expecting a reply) comes back HTTP 202 with an
            # empty body - there is nothing to decode. Any other empty-body response is likewise
            # not JSON and must not be handed to response.json(), which raises JSONDecodeError on
            # an empty string.
            return {}
        return response.json()

    def initialize(self, client_name: str = "lodestone-goal-orchestrator-milestone1") -> dict[str, Any]:
        result = self._rpc(
            "initialize",
            {"protocolVersion": PROTOCOL_VERSION, "clientInfo": {"name": client_name, "version": "1.0"}},
        )
        if result.get("error") or result.get("result") is None:
            raise LodestoneMcpError(f"initialize failed: {result.get('error')}")
        self._rpc("notifications/initialized", {})
        return result["result"]

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        """Call an MCP tool and unwrap `result.structuredContent` into a {status,error,output} shape,
        mirroring Convert-ToolResponse in the existing PowerShell harnesses so trace output stays
        directly comparable to prior evidence.

        Guards against transport-level failures (confirmed real, flagged by the coordinator's
        parallel review: gateway/mcp-server's LoopbackHttpServer.java returns HTTP 429 "too many
        active requests" past 64 concurrent exchanges - a model-driven loop hammering read/observe
        capabilities at machine speed can plausibly hit this, and a per-turn decision loop must
        surface that as a recoverable per-turn error, not an uncaught exception that kills the
        entire run with zero context).
        """
        try:
            raw = self._rpc("tools/call", {"name": name, "arguments": arguments or {}})
        except requests.exceptions.RequestException as exc:
            return {
                "status": "error",
                "error": {"code": "MCP_TRANSPORT_ERROR", "message": f"{type(exc).__name__}: {exc}"},
                "output": None,
            }
        if raw.get("error"):
            return {"status": "rpc-error", "error": raw["error"], "output": None}
        payload = (raw.get("result") or {}).get("structuredContent")
        if payload is None:
            return {"status": "empty", "error": None, "output": None}
        if isinstance(payload, dict) and payload.get("status") in ("ok", "error", "cancelled", "timed-out"):
            return {"status": payload["status"], "error": payload.get("error"), "output": payload.get("output")}
        return {"status": "ok", "error": None, "output": payload}

    def list_capabilities(self, query: str = "") -> list[dict[str, Any]]:
        result = self.call_tool("lodestone_capabilities_list", {"query": query})
        if result["status"] != "ok":
            raise LodestoneMcpError(f"lodestone_capabilities_list failed: {result}")
        return result["output"]["capabilities"]

    def invoke_capability(
        self,
        capability: str,
        input_payload: dict[str, Any],
        capability_version: str | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {"capability": capability, "input": input_payload, "dryRun": dry_run}
        if capability_version:
            args["capabilityVersion"] = capability_version
        return self.call_tool("lodestone_capability_invoke", args)


def capability_id_to_tool_name(capability_id: str) -> str:
    """Anthropic tool names must match ^[a-zA-Z0-9_-]{1,128}$; capability ids use '.' as a
    separator, which is not legal, so '.' becomes '_'. See the NAME NOTE in the module docstring.
    """
    return capability_id.replace(".", "_")


def build_tool_catalog(capabilities: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    """Auto-generate one Anthropic tool-use definition per available minecraft.* capability.

    Returns (tools, dispatch) where `tools` is the list to pass as Messages API `tools=`, and
    `dispatch` maps the generated tool name back to {"capability": id, "capabilityVersion": version}
    for routing every tool_use block through `lodestone_capability_invoke`.
    """
    tools: list[dict[str, Any]] = []
    dispatch: dict[str, dict[str, Any]] = {}
    seen_names: set[str] = set()
    for capability in capabilities:
        # NOTE (empirically verified live against a real NeoForge 1.21.1 client this session, not
        # assumed): "restricted" in this listing is frequently just the STATIC descriptor default
        # baked into protocol/catalog/core-capabilities.json, carried through verbatim by
        # NeoForgeAdapter#adapt for any capability whose static entry is already RESTRICTED - it
        # does NOT reflect a live per-session authorization check. Directly invoking
        # minecraft.player.move through lodestone_capability_invoke on a session with full/broad
        # authorization returned status "ok" despite the listing showing it "restricted" with
        # reason.code "native-permission". Excluding "restricted" here would silently hide working
        # capabilities (player.move/look/interact, world.blocks.write, chat.send, etc.) from the
        # model - the actual authorization gate is enforced for real at invoke time by
        # LodestoneRuntime/McpGateway regardless of what this script generates as a tool, so the
        # safe, correct filter is to exclude only the states that are genuinely never invokable:
        # "unavailable" (not-implemented / client-not-ready) and "degraded" (no server/world yet).
        if capability.get("availability") not in ("available", "restricted"):
            continue
        capability_id = capability.get("id", "")
        if not capability_id.startswith("minecraft."):
            continue
        if any(capability_id.startswith(prefix) for prefix in EXCLUDED_CAPABILITY_PREFIXES):
            continue

        tool_name = capability_id_to_tool_name(capability_id)
        if tool_name in seen_names:
            # Defensive: two capability versions of the same id would collide on tool name. Keep
            # the first (capabilities are typically returned newest/most-stable first); this
            # milestone doesn't need multi-version exposure of the same capability as two tools.
            continue
        seen_names.add(tool_name)

        input_schema = capability.get("inputSchema") or {}
        if not isinstance(input_schema, dict) or input_schema.get("type") != "object":
            # Anthropic requires a JSON Schema object with type "object" at the top level; a
            # capability with an empty/absent schema (no-argument capabilities) still needs a
            # valid empty-object schema, not `{}` or None.
            input_schema = {"type": "object", "properties": {}}

        description = capability.get("documentation") or capability_id
        tools.append({"name": tool_name, "description": description, "input_schema": input_schema})
        dispatch[tool_name] = {"capability": capability_id, "capabilityVersion": capability.get("version")}
    return tools, dispatch


SYSTEM_PROMPT_TEMPLATE = """You are an autonomous agent controlling a live Minecraft NeoForge 1.21.1 \
client through the Lodestone MCP bridge. Every tool available to you maps directly onto a Lodestone \
capability - there is no built-in "mine a tree" action; you must decide and issue the individual \
subactions yourself (observe world/entities/inventory, navigate, interact/mine, craft, etc).

Goal: {goal}

You operate in REALTIME mode: after every observation, decide the next single subaction (or a very \
small batch of subactions) and call the matching tool immediately, then look at the real result \
before deciding what to do next. Do not plan many steps ahead and batch them blindly - the world can \
change between your decisions (mobs, falling blocks, other players), so re-observe after any action \
that could have changed the world or your state.

You may call read-only/observe tools as often as you need - they are cheap. For movement over any \
meaningful distance, prefer the safe waypoint navigation tool over raw low-level movement, since it \
already accounts for terrain and hazards. Stay in survival mode and avoid unnecessary risk (lava, \
fall damage, hostile mobs) while pursuing the goal.

When you believe the goal has been achieved, reply with a plain text message explaining why (no \
further tool calls) - the harness will independently verify your inventory before accepting that the \
goal is actually complete."""


# --- "cli" backend (claude -p): text-embedded-JSON decision protocol -----------------------------
# The api backend above gets a native tools=[...] parameter and structured tool_use content blocks
# back from the Messages API. `claude -p` has no equivalent for an external caller - it is Claude
# Code's own print-mode CLI, and even with --strict-mcp-config dropping this machine's own MCP tool
# schemas from context, it still just returns free text. So the cli backend describes Lodestone's
# tool catalog as plain text in the prompt and asks for a single JSON object back, exactly like
# sonnet5-goal-model-proxy.py's candidateIndex/rationale pattern - see that file's `choose()`.
CLI_SYSTEM_PROMPT_TEMPLATE = """You are an autonomous agent controlling a live Minecraft NeoForge 1.21.1 \
client through the Lodestone MCP bridge. There is no built-in "mine a tree" action; you must decide \
and issue the individual subactions yourself (observe world/entities/inventory, navigate, \
interact/mine, craft, etc) by choosing ONE tool per turn from the catalog below.

Goal: {goal}

Available tools (name, description, JSON Schema for "arguments"):
{tools}

You operate in REALTIME mode: after every observation, decide the next single subaction and pick \
the matching tool, then you will be shown the real result before deciding what to do next. Do not \
plan many steps ahead - the world can change between decisions (mobs, falling blocks, other \
players), so re-observe after any action that could have changed the world or your state. You may \
call read-only/observe tools as often as you need - they are cheap. For movement over any \
meaningful distance, prefer the safe waypoint navigation tool (minecraft_goal_navigation_safe-waypoint) \
over raw low-level movement, since it already accounts for terrain and hazards. Stay in survival \
mode and avoid unnecessary risk (lava, fall damage, hostile mobs) while pursuing the goal.

SAFETY: minecraft_player_move performs NO fall or hazard checking at all - only use jump/sprint \
with it over ground you have directly observed as solid and level. If you are not certain the \
ground ahead is safe (a possible drop-off, gap, or terrain you have not directly seen), use \
minecraft_goal_navigation_safe-waypoint instead, which does check. The harness will warn you here if \
your health drops sharply or is critically low - treat that warning as urgent and stabilize (retreat \
to safe ground, re-observe) before continuing toward the goal.

IMPORTANT: each tool has its OWN "arguments" schema, shown next to it above - it is not shared \
across tools. Some tools (like the safe-waypoint navigation tool) take fields such as \
"intelligence"/"safety"/"observation"; most simple action tools (like the raw interact/move/look \
tools) do NOT and will reject unknown fields, since the game action they trigger is dispatched \
BEFORE that rejection is caught - unlike a normal validation error, a rejected-after-dispatch call \
can leave that specific tool permanently unusable for the rest of this session. Before calling any \
tool, use ONLY the exact field names listed in that tool's own schema above - never copy a field \
from a different tool's schema just because it seemed like a reasonable convention.

Respond with EXACTLY ONE JSON object and nothing else - no prose, no markdown code fences, no \
explanation outside the object. Shape:
{{"tool": "<tool name from the catalog above, or null if you are done>", "arguments": {{...}}, \
"done": true or false, "rationale": "one short sentence"}}

Set "done": true (and "tool": null) only when you believe the goal has genuinely been achieved - \
the harness will independently verify your inventory before accepting that the goal is complete."""


def render_tool_catalog_for_prompt(tools: list[dict[str, Any]]) -> str:
    """Compact text rendering of the generated tool catalog for embedding in a claude -p prompt -
    the cli backend's only way to expose "available tools" to the model, since claude -p has no
    native tools= parameter for an external caller (see module docstring).
    """
    lines = []
    for tool in tools:
        schema_json = json.dumps(tool["input_schema"], separators=(",", ":"))
        lines.append(f"- {tool['name']}: {tool['description']} | arguments schema: {schema_json}")
    return "\n".join(lines)


# --- Bounded replayed history (task #16) -----------------------------------------------------
# Root cause of trace-abdc6a99d3e5.jsonl's real completion attempt hitting a hard model context
# limit (1,004,298 tokens, limit 1,000,000) at turn 17: `history` used to be a list of pre-rendered
# strings that embedded a tool result's raw JSON verbatim, forever - a single
# minecraft.world.blocks.read call over a 16x16x16 volume returns 4096 per-cell entries
# ({position, block, air, loaded}) serializing to ~366KB, and because the cli backend has no native
# multi-turn state, that whole blob got replayed in full on every subsequent turn's prompt. Three
# layers fix this, all render/storage-side - no capability-side scan-size caps were touched (advisor
# call: shrinking those would still let several smaller scans accumulate the same way, and the model
# genuinely needs that search radius):
#   1. reencode_volumetric_result(): applied ONCE, at ingestion into `history` (never to what goes
#      into the JSONL trace, which keeps the raw, unabridged result regardless - see run_loop_cli()).
#      Detects a minecraft.world.blocks.read-shaped output (a flat list of one entry per queried
#      cell) and re-encodes it as a per-block-type coordinate map, eliding air cells to a count.
#      Every non-air block's exact type and coordinate survives - a format change, not data loss.
#      minecraft.world.region.scan's output is already a compact blockCounts histogram with no
#      per-cell array, so it passes through unchanged (nothing to re-encode).
#   2. HistoryEntry/render_history(): `history` is now a list of structured (turn, decision, result)
#      entries (or harness-authored notes), not pre-rendered strings. Rendering only happens at
#      prompt-build time, as a policy: the last RECENT_FULL_RENDER_TURNS turns' results render in
#      full (using the already-re-encoded format from step 1); any older entry whose rendered size
#      still exceeds DIGEST_THRESHOLD_BYTES renders as a compact digest instead (digest_result()) -
#      small results always render in full regardless of age. This bounds prompt growth for
#      arbitrarily long runs and needs no live client to test (see
#      verification/test_goal_orchestrator_milestone1_history.py).
#   3. The HISTORY_ENTRY_HARD_CAP_CHARS backstop in render_history(): applied to every rendered
#      entry regardless of kind or the two mechanisms above, so an unanticipated future capability
#      with a large, non-volumetric-shaped result still can't reproduce this failure mode.
RECENT_FULL_RENDER_TURNS = 2
DIGEST_THRESHOLD_BYTES = 4096
DIGEST_MAX_COORDS = 50
HISTORY_ENTRY_HARD_CAP_CHARS = 30_000


def reencode_volumetric_result(result: dict[str, Any]) -> dict[str, Any]:
    """Lossless re-encoding of a minecraft.world.blocks.read-shaped result for storage in
    `history` - see the module comment above. Any other shape (including a dict with no "blocks"
    list, e.g. minecraft.world.region.scan's already-compact blockCounts histogram) passes through
    unchanged, so this is safe to call unconditionally on every tool result before it enters
    history, not just on results known in advance to be volumetric.
    """
    output = result.get("output")
    if not isinstance(output, dict):
        return result
    blocks = output.get("blocks")
    if not isinstance(blocks, list):
        return result

    by_type: dict[str, list[list[Any]]] = {}
    air_count = 0
    unloaded: list[list[Any]] = []
    for entry in blocks:
        if not isinstance(entry, dict):
            continue
        position = entry.get("position") or {}
        coordinate = [position.get("x"), position.get("y"), position.get("z")]
        if entry.get("loaded") is False:
            unloaded.append(coordinate)
            continue
        if entry.get("air"):
            air_count += 1
            continue
        block_id = str(entry.get("block", "unknown"))
        by_type.setdefault(block_id, []).append(coordinate)

    reencoded_output: dict[str, Any] = {
        "dimension": output.get("dimension"),
        "origin": output.get("origin"),
        "size": output.get("size"),
        "count": output.get("count"),
        "airCellsElided": air_count,
        "blockTypes": by_type,
    }
    if unloaded:
        reencoded_output["unloadedCells"] = unloaded

    reencoded = dict(result)
    reencoded["output"] = reencoded_output
    return reencoded


def digest_result(result: dict[str, Any]) -> str:
    """Render-time compact digest for an aged, large history entry - see render_history(). Status/
    error always pass through (small, always meaningful). For a reencode_volumetric_result() output
    (has "blockTypes"), summarizes as a per-type count histogram plus up to DIGEST_MAX_COORDS
    example coordinates total, with an explicit staleness note. For any other large output shape
    (unanticipated by step 1), falls back to just its top-level key set plus the same staleness
    note, rather than reproducing it - the model can always re-observe if it needs current detail.
    """
    output = result.get("output")
    parts: dict[str, Any] = {"status": result.get("status")}
    if result.get("error"):
        parts["error"] = result["error"]
    block_types = output.get("blockTypes") if isinstance(output, dict) else None
    if isinstance(block_types, dict):
        parts["blockTypeCounts"] = {block_id: len(coords) for block_id, coords in block_types.items()}
        examples: dict[str, list[list[Any]]] = {}
        remaining = DIGEST_MAX_COORDS
        for block_id, coords in block_types.items():
            if remaining <= 0:
                break
            take = coords[:remaining]
            examples[block_id] = take
            remaining -= len(take)
        parts["exampleCoordinates"] = examples
        parts["note"] = "stale summary, re-scan to refresh if needed"
    elif isinstance(output, dict):
        parts["outputKeys"] = sorted(output.keys())
        parts["note"] = "large result summarized/stale, re-observe to refresh if needed"
    return json.dumps(parts, separators=(",", ":"), default=str)


@dataclass
class HistoryEntry:
    """One entry in the cli backend's replayed history - see the module comment above
    render_history(). Either a model decision (+ the tool result, already re-encoded compactly at
    ingestion) that render_history()'s policy may show in full or as a digest depending on age/
    size, or a harness-authored plain-text `note` (safety warnings, reconcile status, retry
    prompts) that always renders as-is, subject only to the hard backstop cap.
    """

    turn: int
    decision: dict[str, Any] | None = None
    result: dict[str, Any] | None = None
    note: str | None = None


def render_action_entry(entry: HistoryEntry, full: bool) -> str:
    decision_json = json.dumps(entry.decision, separators=(",", ":"), default=str)
    if entry.result is None:
        return (
            f"Turn {entry.turn}: you responded {decision_json} - INVALID, no tool was called. Your "
            'response must always have either a real "tool" name or "done": true; try again.'
        )
    result_json = json.dumps(entry.result, separators=(",", ":"), default=str)
    if full or len(result_json) <= DIGEST_THRESHOLD_BYTES:
        return f"Turn {entry.turn}: you chose {decision_json} -> tool result: {result_json}"
    return f"Turn {entry.turn}: you chose {decision_json} -> tool result (digest): {digest_result(entry.result)}"


def render_history(history: list[HistoryEntry]) -> list[str]:
    """Render-time policy for the whole replayed history - see the module comment above. Recency is
    computed over distinct turn numbers, not list position: a single turn can produce several
    entries (an action plus a safety-warning note plus a reconcile note, say), and all of them
    should render in full together while that turn is recent.
    """
    distinct_turns = sorted({entry.turn for entry in history})
    recent_turns = set(distinct_turns[-RECENT_FULL_RENDER_TURNS:])
    rendered = []
    for entry in history:
        text = entry.note if entry.note is not None else render_action_entry(entry, full=entry.turn in recent_turns)
        if len(text) > HISTORY_ENTRY_HARD_CAP_CHARS:
            text = text[:HISTORY_ENTRY_HARD_CAP_CHARS] + " ...[TRUNCATED: entry exceeded the hard size cap]"
        rendered.append(text)
    return rendered


def build_cli_prompt(goal: str, tool_catalog_text: str, history: list[HistoryEntry]) -> str:
    parts = [CLI_SYSTEM_PROMPT_TEMPLATE.format(goal=goal, tools=tool_catalog_text)]
    if history:
        parts.append("--- Actions taken so far this run, oldest first ---\n" + "\n".join(render_history(history)))
    parts.append("Respond now with ONLY the JSON object describing your next action.")
    return "\n\n".join(parts)


def call_claude_cli(prompt: str, model: str, effort: str, timeout_s: float) -> dict[str, Any]:
    """Shell out to `claude -p`, reusing sonnet5-goal-model-proxy.py's exact invocation shape and
    response-parsing logic (see that file's `choose()`), so this milestone's cli backend is a
    direct reuse of an already-proven pattern, not a fresh reinvention.
    """
    command = [
        CLAUDE_EXE, "-p", "--model", model, "--output-format", "json",
        "--effort", effort, "--strict-mcp-config",
    ]
    completed = subprocess.run(
        command, cwd=os.path.expanduser("~"), input=prompt,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout_s, check=False,
    )
    try:
        envelope = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(
            f"claude CLI stdout was not valid JSON (exit={completed.returncode}): {completed.stdout[:2000]!r}"
        ) from exc
    if envelope.get("is_error"):
        raise RuntimeError(f"claude CLI returned an error result: {envelope.get('result')}")
    answer = str(envelope.get("result", ""))
    match = re.search(r"\{.*\}", answer, re.DOTALL)
    if not match:
        raise RuntimeError(f"model did not return a JSON object; raw answer={answer!r}")
    decision = json.loads(match.group(0))
    return {"decision": decision, "envelope": envelope}


@dataclass
class TraceWriter:
    path: Path

    def __post_init__(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("w", encoding="utf-8")

    def _write(self, record: dict[str, Any]) -> None:
        record["loggedAtUtc"] = datetime.now(timezone.utc).isoformat()
        self._fh.write(json.dumps(record, default=str) + "\n")
        self._fh.flush()

    def record_event(self, event: str, **fields: Any) -> None:
        self._write({"event": event, **fields})

    def record_model_turn(
        self,
        turn: int,
        messages_before: list[dict[str, Any]],
        response: Any,
        latency_s: float,
    ) -> None:
        self._write(
            {
                "event": "model_turn",
                "turn": turn,
                "promptMessageCount": len(messages_before),
                "promptContextChars": len(json.dumps(messages_before, default=str)),
                "stopReason": response.stop_reason,
                "usage": response.usage.model_dump() if response.usage else None,
                "latencySeconds": round(latency_s, 3),
                "responseContent": [block.model_dump() for block in response.content],
            }
        )

    def record_tool_call(self, turn: int, tool_name: str, arguments: Any, result: dict[str, Any]) -> None:
        self._write(
            {
                "event": "tool_call",
                "turn": turn,
                "toolName": tool_name,
                "arguments": arguments,
                "result": result,
            }
        )

    def record_cli_turn(
        self,
        turn: int,
        prompt: str,
        envelope: dict[str, Any],
        decision: Any,
        latency_s: float,
    ) -> None:
        """cli backend's equivalent of record_model_turn(): the full prompt text IS the request
        context here (there is no separate structured `messages` list to log), so it is logged in
        full - per the brief, "full trace log of every model turn (prompt context, tool calls
        requested, tool results returned)" is the actual deliverable, not a summary of it.
        """
        self._write(
            {
                "event": "cli_model_turn",
                "turn": turn,
                "promptChars": len(prompt),
                "prompt": prompt,
                "decision": decision,
                "latencySeconds": round(latency_s, 3),
                "cliDurationMs": envelope.get("duration_ms"),
                "cliApiDurationMs": envelope.get("duration_api_ms"),
                "cliIsError": envelope.get("is_error"),
                "cliTotalCostUsd": envelope.get("total_cost_usd"),
                "cliUsage": envelope.get("usage"),
                "cliModelUsage": envelope.get("modelUsage"),
            }
        )

    def close(self) -> None:
        self._fh.close()


def extract_text(response: Any) -> str:
    return "".join(block.text for block in response.content if block.type == "text")


def run_loop(
    mcp_client: LodestoneMcpClient,
    model_client: anthropic.Anthropic,
    model: str,
    effort: str,
    tools: list[dict[str, Any]],
    dispatch: dict[str, dict[str, Any]],
    goal: str,
    max_turns: int,
    trace: TraceWriter,
) -> dict[str, Any]:
    messages: list[dict[str, Any]] = []
    system_prompt = SYSTEM_PROMPT_TEMPLATE.format(goal=goal)
    latencies: list[float] = []

    for turn in range(1, max_turns + 1):
        started = time.monotonic()
        try:
            response = model_client.messages.create(
                model=model,
                max_tokens=1024,
                system=system_prompt,
                tools=tools,
                messages=messages,
                output_config={"effort": effort},
            )
        except Exception as exc:  # noqa: BLE001 - deliberately broad: any failure here is a real,
            # reportable blocker for this milestone (auth, rate limit, network, bad request), not a
            # bug to swallow silently. See module docstring's API key resolution section.
            latency_s = time.monotonic() - started
            trace.record_event(
                "model_call_failed",
                turn=turn,
                latencySeconds=round(latency_s, 3),
                exceptionType=type(exc).__name__,
                exceptionMessage=str(exc),
            )
            return {
                "stopReason": "model_call_failed",
                "turns": turn - 1,
                "exceptionType": type(exc).__name__,
                "exceptionMessage": str(exc),
                "turnLatenciesSeconds": latencies,
            }

        latency_s = time.monotonic() - started
        latencies.append(round(latency_s, 3))
        trace.record_model_turn(turn, messages, response, latency_s)

        assistant_content = [block.model_dump() for block in response.content]
        messages.append({"role": "assistant", "content": assistant_content})

        tool_use_blocks = [block for block in response.content if block.type == "tool_use"]
        if not tool_use_blocks:
            return {
                "stopReason": "model_stopped_requesting_tools",
                "turns": turn,
                "finalText": extract_text(response),
                "turnLatenciesSeconds": latencies,
            }

        tool_result_blocks = []
        for block in tool_use_blocks:
            mapping = dispatch.get(block.name)
            if mapping is None:
                result = {
                    "status": "error",
                    "error": {"code": "UNKNOWN_TOOL", "message": f"no dispatch mapping for tool '{block.name}'"},
                    "output": None,
                }
            else:
                result = mcp_client.invoke_capability(
                    mapping["capability"], block.input, mapping.get("capabilityVersion")
                )
            trace.record_tool_call(turn, block.name, block.input, result)
            tool_result_blocks.append(
                {
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": json.dumps(result, default=str),
                    "is_error": result.get("status") not in ("ok",),
                }
            )
        messages.append({"role": "user", "content": tool_result_blocks})

    return {"stopReason": "max_turns_reached", "turns": max_turns, "turnLatenciesSeconds": latencies}


def run_loop_cli(
    mcp_client: LodestoneMcpClient,
    tools: list[dict[str, Any]],
    dispatch: dict[str, dict[str, Any]],
    goal: str,
    model: str,
    effort: str,
    max_turns: int,
    trace: TraceWriter,
) -> dict[str, Any]:
    """cli backend: see the module docstring's "cli backend" section and
    sonnet5-goal-model-proxy.py's `choose()` for the pattern this reuses. Each turn rebuilds the
    full prompt from scratch (goal + tool catalog text + full history replay) since `claude -p` is
    a stateless subprocess call with no server-side session continuity for this usage.
    """
    if effort not in VALID_CLI_EFFORTS:
        # Mirrors sonnet5-goal-model-proxy.py's normalize_reasoning_effort() fallback-to-"low"
        # behavior, but logged instead of silent, since this is a CLI argument mistake, not a
        # runtime condition to mask.
        trace.record_event("cli_effort_normalized", requested=effort, normalizedTo="low")
        effort = "low"

    # Bounds retries on a genuine call/parse failure (CLI missing, timeout, non-JSON stdout,
    # is_error result, no embedded JSON found, malformed decision JSON). Fix per coordinator's
    # parallel-review finding: the previous behavior aborted the ENTIRE run on the very first such
    # failure, unlike sonnet5-goal-model-proxy.py's own choose(), which this file's docstring claims
    # to mirror and which always returns a safe fallback rather than raising. A transient CLI hiccup
    # (or one verbose response the regex still can't extract JSON from) should cost one retried turn,
    # not the whole run - but a PERSISTENT failure is still a real, reportable blocker, not something
    # to retry forever. 3 matches this session's own established convention (see module docstring /
    # this agent's working style) of treating 3 consecutive identical blockers as genuine, not
    # transient.
    MAX_CONSECUTIVE_CALL_FAILURES = 3

    tool_catalog_text = render_tool_catalog_for_prompt(tools)
    history: list[HistoryEntry] = []
    latencies: list[float] = []
    consecutive_call_failures = 0
    last_health: float | None = None  # see the health/fall-damage guard below

    for turn in range(1, max_turns + 1):
        prompt = build_cli_prompt(goal, tool_catalog_text, history)
        started = time.monotonic()
        try:
            call_result = call_claude_cli(prompt, model, effort, CLI_CALL_TIMEOUT_S)
        except Exception as exc:  # noqa: BLE001 - broad on purpose: any of CLI-missing/timeout/
            # non-JSON-stdout/is_error/no-JSON-found/bad-decision-JSON lands here and must be
            # logged explicitly (never silently dropped) and retried up to the cap above.
            latency_s = time.monotonic() - started
            consecutive_call_failures += 1
            trace.record_event(
                "turn_failed",
                turn=turn,
                latencySeconds=round(latency_s, 3),
                exceptionType=type(exc).__name__,
                exceptionMessage=str(exc),
                consecutiveFailures=consecutive_call_failures,
            )
            if consecutive_call_failures >= MAX_CONSECUTIVE_CALL_FAILURES:
                return {
                    "stopReason": "model_call_failed",
                    "turns": turn,
                    "exceptionType": type(exc).__name__,
                    "exceptionMessage": str(exc),
                    "turnLatenciesSeconds": latencies,
                }
            history.append(HistoryEntry(turn=turn, note=(
                f"Turn {turn}: your call failed ({type(exc).__name__}: {exc}). Respond again with "
                "ONLY the required JSON object - no extra text before or after it."
            )))
            continue

        consecutive_call_failures = 0
        latency_s = time.monotonic() - started
        latencies.append(round(latency_s, 3))
        decision = call_result["decision"]
        trace.record_cli_turn(turn, prompt, call_result["envelope"], decision, latency_s)

        if not isinstance(decision, dict):
            # Explicit, not silent (a bare `continue` here previously left a cli_model_turn entry
            # with no matching tool_call/failure event and no visible explanation - flagged by the
            # coordinator as making the trace untrustworthy): a non-dict decision is unusable.
            trace.record_event(
                "turn_no_action", turn=turn, reason="decision was not a JSON object", decision=decision
            )
            history.append(HistoryEntry(turn=turn, decision={"raw": decision}, result=None))
            continue

        done = bool(decision.get("done"))
        tool_name = decision.get("tool")
        if done:
            return {
                "stopReason": "model_declared_done",
                "turns": turn,
                "finalRationale": decision.get("rationale", ""),
                "turnLatenciesSeconds": latencies,
            }
        if not tool_name:
            # Malformed turn: neither a real tool nor an explicit done:true. Logged explicitly (see
            # comment above) rather than silently falling through to the next turn. Give the model
            # one more chance next turn with a corrective note in the replayed history, rather than
            # aborting the whole run on a single bad response.
            trace.record_event(
                "turn_no_action",
                turn=turn,
                reason="decision had neither a 'tool' nor 'done': true",
                decision=decision,
            )
            history.append(HistoryEntry(turn=turn, decision=decision, result=None))
            continue

        arguments = decision.get("arguments") or {}
        mapping = dispatch.get(tool_name)
        if mapping is None:
            result = {
                "status": "error",
                "error": {"code": "UNKNOWN_TOOL", "message": f"no dispatch mapping for tool '{tool_name}'"},
                "output": None,
            }
        else:
            result = mcp_client.invoke_capability(mapping["capability"], arguments, mapping.get("capabilityVersion"))
        trace.record_tool_call(turn, tool_name, arguments, result)
        # reencode_volumetric_result() is applied ONLY to what goes into the replayed history, never
        # to `result` itself - the trace call right above already logged the raw, unabridged result.
        # See the module comment above render_history() (task #16).
        history.append(HistoryEntry(turn=turn, decision=decision, result=reencode_volumetric_result(result)))

        # Minimal between-turn health/fall-damage guard - see the module-level comment above
        # HEALTH_READ_CAPABILITY for why this exists and what it deliberately does NOT do (no
        # blocking, no predictive terrain analysis, no safety-tier system). Never a model turn, so
        # it costs no turn budget.
        current_health, health_result = read_player_health(mcp_client)
        trace.record_event(
            "health_check", turn=turn, health=current_health, previousHealth=last_health, result=health_result
        )
        if current_health is not None:
            if current_health <= 0:
                history.append(HistoryEntry(turn=turn, note=(
                    f"Turn {turn}: SAFETY - your health just read {current_health}. If you are on the "
                    "death screen, use minecraft_ui_state_read to see the exact screen, then "
                    "minecraft_ui_key to respawn before doing anything else."
                )))
            elif last_health is not None and (
                current_health <= CRITICAL_HEALTH or (last_health - current_health) >= SHARP_HEALTH_DROP
            ):
                history.append(HistoryEntry(turn=turn, note=(
                    f"Turn {turn}: SAFETY WARNING - health went from {last_health} to {current_health}. "
                    "Stop and re-observe before continuing toward the goal: work out what caused this "
                    "(fall, mob, lava, drowning), retreat to solid/safe ground if needed, and avoid raw "
                    "movement (minecraft_player_move, especially with jump/sprint) over any drop, gap, "
                    "or terrain you have not directly confirmed is solid - prefer "
                    "minecraft_goal_navigation_safe-waypoint for uncertain movement instead."
                )))
            last_health = current_health

        # Targeted, proactive caution for the exact pattern that killed a prior live run
        # (trace-3eb8d0d2ad19.jsonl, turn 10 -> turn 11's health read of 0.0): a raw jump move can be
        # instantly fatal if the ground ahead is not what the model assumed, since
        # minecraft_player_move performs no fall/hazard checking at all (unlike
        # minecraft_goal_navigation_safe-waypoint). The reactive check above cannot outrun a single
        # instantly-fatal fall, so this fires right after the risky call itself, not on the next
        # health delta.
        if tool_name == "minecraft_player_move" and arguments.get("jump"):
            history.append(HistoryEntry(turn=turn, note=(
                f"Turn {turn}: NOTE - that was a raw jump move. minecraft_player_move never checks "
                "for fall hazards; only use jump/sprint over ground you have directly observed as "
                "solid and level. Prefer minecraft_goal_navigation_safe-waypoint whenever you are not "
                "certain the ground ahead is safe."
            )))

        # Automatic, harness-level recovery from CAPABILITY_QUARANTINED (see
        # LodestoneRuntime's SHARED_MUTATION_ORDER: one mutating minecraft.* capability's
        # indeterminate outcome quarantines every other one for the rest of this session). This is
        # deliberately NOT left to the model to notice and pick out of its own tool catalog - the
        # coordinator asked for the orchestrator itself to call minecraft.session.reconcile
        # reliably the moment this specific error code is observed, since a real run already showed
        # a model burn several turns testing tools individually before concluding the whole bridge
        # was down (it wasn't - only mutations were). Reconcile is deliberately NOT itself in
        # `dispatch` as a model-callable tool for this reason: it is harness-owned recovery, not a
        # subaction choice.
        if (
            isinstance(result, dict)
            and result.get("status") == "error"
            and isinstance(result.get("error"), dict)
            and result["error"].get("code") == "CAPABILITY_QUARANTINED"
        ):
            reconcile_result = mcp_client.invoke_capability("minecraft.session.reconcile", {})
            trace.record_event("auto_reconcile", turn=turn, triggeredByTool=tool_name, result=reconcile_result)
            history.append(HistoryEntry(turn=turn, note=(
                f"Turn {turn}: the harness automatically called minecraft.session.reconcile after your "
                f"last tool hit CAPABILITY_QUARANTINED. Result: "
                f"{json.dumps(reconcile_result, separators=(',', ':'), default=str)}. If cleared is true, "
                "mutating tools (move/interact/navigate/etc) should work again - try your last action "
                "again or re-observe first if you're unsure of the current state. If cleared is false, "
                "the adapter could not confirm it was safe to clear; re-observe state and consider a "
                "different approach."
            )))

    return {"stopReason": "max_turns_reached", "turns": max_turns, "turnLatenciesSeconds": latencies}


def ensure_fresh_world(mcp_client: LodestoneMcpClient, trace: TraceWriter) -> dict[str, Any]:
    """Bootstrap infrastructure (NOT part of the model-decided goal loop): get the live client into
    a loaded survival world so the model's first observation has something real to look at. This
    mirrors the existing run-neoforge-goal-benchmark.ps1 UI flow (ui_navigate through
    singleplayer -> create_new_world -> create_world) since that flow is already proven to work
    against a fresh Lodestone client.
    """
    state = mcp_client.call_tool("ui_state")
    trace.record_event("bootstrap_ui_state", result=state)
    if state["status"] == "ok" and state["output"].get("inWorld"):
        return state["output"]

    mcp_client.call_tool("ui_navigate", {"target": "singleplayer"})
    for _ in range(120):
        state = mcp_client.call_tool("ui_state")
        screen = str(state["output"].get("screenClass", "")) if state["status"] == "ok" else ""
        if "SelectWorld" in screen or "WorldSelection" in screen:
            break
        time.sleep(0.25)
    mcp_client.call_tool("ui_navigate", {"target": "create_new_world"})
    for _ in range(120):
        state = mcp_client.call_tool("ui_state")
        if state["status"] == "ok" and str(state["output"].get("screenClass", "")).find("CreateWorld") != -1:
            break
        time.sleep(0.25)
    mcp_client.call_tool("ui_navigate", {"target": "create_world"})
    for _ in range(240):
        state = mcp_client.call_tool("ui_state")
        if state["status"] == "ok" and state["output"].get("inWorld") and not state["output"].get("screenClass"):
            trace.record_event("bootstrap_world_ready", result=state)
            return state["output"]
        time.sleep(0.5)
    raise LodestoneMcpError(f"bootstrap: world did not become ready in time, last state={state}")


def verify_log_in_inventory(mcp_client: LodestoneMcpClient, trace: TraceWriter) -> dict[str, Any]:
    """Independent, harness-side verification (not model-decided) that a log actually landed in
    inventory - per the brief, this is the real pass/fail signal for this milestone, not the
    model's own self-report that it thinks it is done.
    """
    result = mcp_client.invoke_capability("minecraft.inventory.read", {})
    trace.record_event("final_inventory_check", result=result)
    found_log = False
    if result["status"] == "ok" and isinstance(result.get("output"), dict):
        slots = result["output"].get("slots") or result["output"].get("items") or []
        for slot in slots:
            item_id = str((slot or {}).get("item", "")).lower()
            if "log" in item_id:
                found_log = True
                break
    return {"logFound": found_log, "inventoryResult": result}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True, help="Lodestone MCP gateway port on the live client")
    parser.add_argument("--token", type=str, required=True, help="X-Lodestone-Token for the live client")
    parser.add_argument("--goal", type=str, default=DEFAULT_GOAL)
    parser.add_argument(
        "--backend",
        type=str,
        default="cli",
        choices=["cli", "api"],
        help=(
            "'cli' (default): shell out to the claude CLI (subscription/OAuth auth, no API key "
            "needed, but pays full-interactive-context overhead per call - see module docstring). "
            "'api': raw Anthropic Messages API via the anthropic SDK (native tool-use, no per-call "
            "context overhead, but requires ANTHROPIC_API_KEY/ANTHROPIC_AUTH_TOKEN, confirmed "
            "absent on this machine at the time this backend flag was added)."
        ),
    )
    parser.add_argument(
        "--model",
        type=str,
        default=None,
        help=f"defaults to {DEFAULT_CLI_MODEL!r} for --backend cli, {DEFAULT_MODEL!r} for --backend api",
    )
    parser.add_argument("--effort", type=str, default="low", choices=["low", "medium", "high", "xhigh", "max"])
    parser.add_argument("--max-turns", type=int, default=DEFAULT_MAX_TURNS)
    parser.add_argument(
        "--evidence-dir",
        type=str,
        default=str(Path(__file__).resolve().parent / "evidence" / "goal-orchestrator-milestone1"),
    )
    parser.add_argument(
        "--skip-bootstrap",
        action="store_true",
        help="assume the client is already in a loaded survival world; skip create-world flow",
    )
    args = parser.parse_args()
    model = args.model or (DEFAULT_CLI_MODEL if args.backend == "cli" else DEFAULT_MODEL)

    run_id = uuid.uuid4().hex[:12]
    evidence_dir = Path(args.evidence_dir)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    trace_path = evidence_dir / f"trace-{run_id}.jsonl"
    summary_path = evidence_dir / f"summary-{run_id}.json"
    trace = TraceWriter(trace_path)

    summary: dict[str, Any] = {
        "runId": run_id,
        "startedAtUtc": datetime.now(timezone.utc).isoformat(),
        "goal": args.goal,
        "backend": args.backend,
        "model": model,
        "effort": args.effort,
        "port": args.port,
        "tracePath": str(trace_path),
    }

    try:
        mcp_client = LodestoneMcpClient(args.port, args.token)
        mcp_client.initialize()
        trace.record_event("mcp_initialized", sessionId=mcp_client.session_id)

        # IMPORTANT ORDERING FIX (root cause of a real bug caught by the coordinator, not
        # self-discovered): capability listing/tool-catalog generation MUST happen AFTER the world
        # is bootstrapped, not before. NeoForgeAdapter#adapt gates every CLIENT_CAPABILITIES entry
        # (player.move/.look/.interact, ui.click, inventory.container.*, etc) on
        # `clientBridge.available(id)` - with no live player/world yet, those genuinely ARE
        # Availability.UNAVAILABLE ("client-not-ready"), which build_tool_catalog() correctly
        # excludes; only AFTER a world exists do they settle into their true "restricted-but-
        # actually-invokable" state (see build_tool_catalog()'s own comment on that). Listing before
        # bootstrap silently produced a 20-tool catalog missing player_move/look/interact/etc even
        # though the "available"+"restricted" filter fix itself was correct - the earlier 35-tool
        # verification this session happened to use --skip-bootstrap against an already-loaded
        # world, which is why it didn't surface this ordering bug.
        if not args.skip_bootstrap:
            world_state = ensure_fresh_world(mcp_client, trace)
            summary["bootstrapWorldState"] = {
                "screenClass": world_state.get("screenClass"),
                "inWorld": world_state.get("inWorld"),
            }

        capabilities = mcp_client.list_capabilities()
        trace.record_event("capabilities_listed", count=len(capabilities))
        tools, dispatch = build_tool_catalog(capabilities)
        trace.record_event(
            "tool_catalog_built",
            toolCount=len(tools),
            toolNames=sorted(dispatch.keys()),
        )
        (evidence_dir / f"tool-catalog-{run_id}.json").write_text(
            json.dumps({"tools": tools, "dispatch": dispatch}, indent=2), encoding="utf-8"
        )
        summary["capabilityCount"] = len(capabilities)
        summary["generatedToolCount"] = len(tools)
        summary["toolNames"] = sorted(dispatch.keys())

        if args.backend == "cli":
            loop_result = run_loop_cli(
                mcp_client, tools, dispatch, args.goal, model, args.effort, args.max_turns, trace
            )
        else:
            try:
                import anthropic  # lazy: only the api backend needs this - see top-of-file comment
            except ImportError as exc:
                raise SystemExit(
                    "--backend api requires the 'anthropic' package (pip install anthropic); "
                    f"import failed: {exc}"
                ) from exc
            model_client = anthropic.Anthropic()
            loop_result = run_loop(
                mcp_client, model_client, model, args.effort, tools, dispatch, args.goal, args.max_turns, trace
            )
        summary["loopResult"] = loop_result

        if loop_result["stopReason"] == "model_call_failed":
            summary["status"] = "BLOCKED"
            summary["blockerReason"] = (
                f"{loop_result['exceptionType']}: {loop_result['exceptionMessage']}"
            )
        else:
            verification = verify_log_in_inventory(mcp_client, trace)
            summary["verification"] = verification
            summary["status"] = "SUCCEEDED" if verification["logFound"] else "GOAL_NOT_CONFIRMED"

    except Exception as exc:  # noqa: BLE001 - top-level: always write a summary, never crash silently
        trace.record_event("fatal_error", exceptionType=type(exc).__name__, exceptionMessage=str(exc))
        summary["status"] = "ERROR"
        summary["exceptionType"] = type(exc).__name__
        summary["exceptionMessage"] = str(exc)
    finally:
        summary["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        trace.close()
        summary_path.write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")

    print(json.dumps(summary, indent=2, default=str))
    return 0 if summary["status"] in ("SUCCEEDED",) else 1


if __name__ == "__main__":
    sys.exit(main())
