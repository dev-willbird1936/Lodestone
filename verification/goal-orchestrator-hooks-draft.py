"""DRAFT / design-spike module for a "hooks" feature on top of the Milestone-1 goal orchestrator.

STATUS: standalone draft, NOT wired into verification/goal-orchestrator-milestone1.py or any other
existing file. Nothing in this file has been live-tested (no live Minecraft client access was
available while writing it - see the design-report text this file's companion audit produced).
It is structurally complete and ready to be smoke-tested the next time a live client is up, but
treat every behavior here as unverified until then.

--------------------------------------------------------------------------------------------------
ORIGIN: the project owner's exact stretch idea (verbatim) -
    "maybe we could also have hooks where the mcp will return info or trigger something if a
    condition is set, like having x material pop into inventory, this may require jar changes idk,
    but the rest should work on MCP side"
This file is the concrete answer to "does it need jar changes", grounded in what the live NeoForge
1.21.1 adapter and the MCP gateway actually do today (read, not assumed):

1. minecraft.event.subscribe / .poll / .unsubscribe are real, implemented capabilities
   (protocol/catalog/core-capabilities.json), reachable ONLY through three dedicated MCP tools -
   NOT the generic lodestone_capability_invoke path, which explicitly rejects any
   "minecraft.event.*" capability (gateway/mcp-server/.../McpGateway.java invoke(), line ~958:
   `if (capability.startsWith("minecraft.event.")) throw ... "event capabilities must use the
   session-owned MCP event tools"`). The three dedicated tools, confirmed by reading
   McpGateway.java directly (tool registrations ~line 347-355, handlers subscribe()/poll()/
   unsubscribe() ~line 1098-1126), are:
     - lodestone_events_subscribe {eventPrefix?, bufferLimit?} -> {id, sessionId, eventPrefix,
       bufferLimit}
     - lodestone_events_poll {subscriptionId, maxEvents?} -> {events: [...]}
     - lodestone_events_unsubscribe {subscriptionId} -> {removed: bool}
   These three tools are UNCONDITIONALLY registered (not gated behind any feature flag in
   McpGateway.java), so EventHook below works with zero jar changes, for whatever event
   vocabulary the live adapter actually publishes.

2. THE CATCH (this is the part the owner flagged as "idk" and the honest answer is): the event
   *infrastructure* is real and generic, but the event *vocabulary* it can ever deliver is
   whatever native adapter code actually calls `publish(...)` for. Grepping every `publish("minecraft.` /
   `publish("lodestone.` call site across EVERY adapter in this repo (NeoForge, every Fabric
   version, every Forge version - not just the live NeoForge target) turns up exactly this
   closed set, and nothing else, ever:
     minecraft.lifecycle.server.started   minecraft.lifecycle.server.stopped
     minecraft.player.joined              minecraft.player.left            minecraft.player.respawned
     minecraft.chat.received
     minecraft.ui.screen.opened           minecraft.ui.screen.closed
     minecraft.input.key.received  (published internally but then explicitly dropped before
                                     delivery - EventHub.publish(): `if (event.startsWith(
                                     "minecraft.input.")) return;` - never reaches any subscriber)
   There is NO minecraft.inventory.* event, no minecraft.world.block.* event, no entity/damage/
   pickup event anywhere in this codebase today. Subscribing with eventPrefix="minecraft.inventory."
   would succeed (the subscribe capability does not validate that the prefix corresponds to
   anything that will ever fire) and then simply never deliver anything - a silent, permanent
   hang, not an error. KNOWN_PUBLISHED_EVENT_PREFIXES below exists specifically to catch this
   trap at hook-registration time instead of at "wait forever" time.

3. So the owner's actual example - "notify me when material X pops into inventory" - CANNOT be
   built today on the event system, full stop, zero-jar-change or not. It CAN be built with ZERO
   jar changes a different way: minecraft.inventory.read is a real, already-implemented query
   capability (confirmed in adapters/neoforge/1.21.1/.../NeoForgeAdapter.java's IMPLEMENTED set
   and handlers map - `handlers.put("minecraft.inventory.read", this::readInventory)`), reachable
   through the ordinary lodestone_capability_invoke path used everywhere else in
   goal-orchestrator-milestone1.py. InventoryDeltaHook below polls it and diffs slot contents
   client/orchestrator-side. This is push-emulated-by-poll, not a true server-pushed event - the
   trade-off is latency bounded by the poll interval, not one game tick - but it needs no Java
   change at all and satisfies the owner's literal ask ("the mcp will return info... if a
   condition is set").

4. What a REAL jar-side push event for inventory changes would look like is sketched (as text, not
   applied) in this task's design-report output, not in this file - per this task's constraints,
   no existing Java file may be edited or even have a real patch staged against it. The short
   version: NeoForge has no single vanilla event that reliably covers every way items enter a
   player's inventory (pickup, craft, command give, creative-mode give, hopper/insert from a
   backpack mod, etc.); the robust approach is the same diffing strategy InventoryDeltaHook uses
   here, just moved server-side into a per-tick (or per-inventory-mutation) hook inside
   NeoForgeClientController/NeoForgeAdapter that calls the existing `publish(...)` helper with a
   new "minecraft.inventory.changed" event name, plus a new capability row in
   protocol/catalog/core-capabilities.json with `"kind":"event"`.

--------------------------------------------------------------------------------------------------
WHY THIS FILE DUPLICATES A SMALL TRANSPORT CLASS INSTEAD OF IMPORTING goal-orchestrator-milestone1.py:
verification/goal-orchestrator-milestone1.py is explicitly off-limits to edit for this task AND is
reported to be actively edited again shortly by a separate, concurrently running live-testing
session (to wire in a mutation-quarantine recovery capability call). Importing its
LodestoneMcpClient here would create a live runtime coupling to a file mid-change outside this
draft's control. HookMcpClient below reproduces only the already-proven request/response shape
from that file (same JSON-RPC method names, same X-Lodestone-Token / Mcp-Session-Id /
MCP-Protocol-Version headers, same structuredContent-unwrap convention) - see that file for the
canonical, actually-tested implementation. If/when this hooks module is promoted out of draft
status, it should import the shared client instead of duplicating it.
"""

from __future__ import annotations

import argparse
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable

import requests

LOG = logging.getLogger("lodestone.hooks")

PROTOCOL_VERSION = "2025-11-25"  # must match goal-orchestrator-milestone1.py's PROTOCOL_VERSION


# ====================================================================================================
# Fact #2 above, made machine-checkable: every event name any adapter in this repo has ever been
# observed to call publish(...) for (grepped `publish("minecraft.` / `publish("lodestone.` across
# adapters/neoforge, adapters/fabric/*, adapters/forge/* - all versions - on the date this draft was
# written). Used only as a best-effort sanity check in EventHook.__post_init__ below: a hook whose
# eventPrefix matches NONE of these gets a loud warning (not a hard error - the catalog itself does
# not validate this, and new event names could ship later) instead of silently hanging forever.
# KEEP THIS LIST IN SYNC if new `publish(...)` call sites are ever added to a live adapter; it is a
# point-in-time audit finding, not a protocol guarantee.
# ====================================================================================================
KNOWN_PUBLISHED_EVENT_PREFIXES: tuple[str, ...] = (
    "minecraft.lifecycle.server.started",
    "minecraft.lifecycle.server.stopped",
    "minecraft.player.joined",
    "minecraft.player.left",
    "minecraft.player.respawned",
    "minecraft.chat.received",
    "minecraft.ui.screen.opened",
    "minecraft.ui.screen.closed",
    # minecraft.input.key.received is published internally but EventHub.publish() drops every
    # "minecraft.input.*" event before any subscriber ever sees it - deliberately NOT listed here
    # so a hook on that prefix gets the "will never fire" warning, which is the honest outcome.
)


def _looks_unreachable(event_prefix: str) -> bool:
    """True if no known-published event name could ever match this prefix (see module docstring
    fact #2). A blank prefix subscribes to everything and is always considered reachable.
    """
    if not event_prefix:
        return False
    return not any(
        known.startswith(event_prefix) or event_prefix.startswith(known) for known in KNOWN_PUBLISHED_EVENT_PREFIXES
    )


# ====================================================================================================
# Minimal standalone MCP transport (see module docstring for why this is a deliberate, documented
# duplication of goal-orchestrator-milestone1.py's LodestoneMcpClient rather than an import).
# ====================================================================================================
class LodestoneMcpError(RuntimeError):
    """Raised for any MCP-level failure: transport, protocol, or an RPC-level `error` field."""


class HookMcpClient:
    """Real MCP client over JSON-RPC 2.0 / HTTP - request/response shape proven live by
    goal-orchestrator-milestone1.py's LodestoneMcpClient (see that file for the original).
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
            return {}
        return response.json()

    def initialize(self, client_name: str = "lodestone-hooks-draft") -> dict[str, Any]:
        result = self._rpc(
            "initialize",
            {"protocolVersion": PROTOCOL_VERSION, "clientInfo": {"name": client_name, "version": "1.0"}},
        )
        if result.get("error") or result.get("result") is None:
            raise LodestoneMcpError(f"initialize failed: {result.get('error')}")
        self._rpc("notifications/initialized", {})
        return result["result"]

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        """Unwraps `result.structuredContent` into a {status,error,output} shape. Handles both
        response shapes actually observed by reading McpGateway.java:
          - most tools: toolResult(ResultEnvelope) -> structuredContent already has status/error/output
          - lodestone_events_subscribe/poll/unsubscribe: toolResult(<raw payload>) on success (the
            subscription object / {events:[...]} / {removed:bool} directly, no envelope) but
            toolResult(<ResultEnvelope>) on failure (invokeEventCapability's non-OK path) - both are
            handled by the same isinstance/status-key sniff below, matching
            goal-orchestrator-milestone1.py's LodestoneMcpClient.call_tool() exactly.
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

    def invoke_capability(
        self, capability: str, input_payload: dict[str, Any], capability_version: str | None = None
    ) -> dict[str, Any]:
        args: dict[str, Any] = {"capability": capability, "input": input_payload, "dryRun": False}
        if capability_version:
            args["capabilityVersion"] = capability_version
        return self.call_tool("lodestone_capability_invoke", args)


# ====================================================================================================
# Hook model
# ====================================================================================================
class HookFireMode(str, Enum):
    """Answers the owner's "return info OR trigger something" framing with two explicit modes."""

    NOTIFY = "notify"  # produces a HookFire the caller can surface to the model (e.g. append to the
    # run_loop_cli-style `history` list) so the model reacts on its own next turn - "return info".
    CALLBACK = "callback"  # synchronously invokes an arbitrary Python callable when the hook fires -
    # "trigger something", e.g. auto-dispatch a follow-up MCP capability call, write an evidence
    # marker, or interrupt the goal loop - without waiting on the model to notice and act.


@dataclass
class HookFire:
    hook_name: str
    fired_at_utc: str
    message: str
    payload: dict[str, Any]


class Hook:
    """Base class for one registered watch condition. Subclasses implement _start/_poll/_stop;
    HookManager drives the lifecycle and enforces min_poll_interval_s so a fast outer decision
    loop cannot hammer a rate-limited MCP capability (see min_poll_interval_s docstring below).
    """

    def __init__(
        self,
        name: str,
        fire_mode: HookFireMode = HookFireMode.NOTIFY,
        callback: Callable[[HookFire], None] | None = None,
        once: bool = False,
        min_poll_interval_s: float = 1.0,
    ) -> None:
        if fire_mode is HookFireMode.CALLBACK and callback is None:
            raise ValueError(f"hook {name!r}: fire_mode=CALLBACK requires a callback")
        self.name = name
        self.fire_mode = fire_mode
        self.callback = callback
        self.once = once
        # Rate-limit self-defense: minecraft.inventory.read allows 30 calls/second (generous), but
        # lodestone_events_poll allows only 60 calls/60s burst 10 (protocol/catalog/
        # core-capabilities.json's minecraft.event.poll rateLimit) - a hook ticked once per outer
        # decision-loop turn against a fast/cheap model backend could plausibly exceed that. This
        # floor is enforced in HookManager.tick(), independent of how often the caller ticks.
        self.min_poll_interval_s = min_poll_interval_s
        self.active = False
        self.fired_count = 0
        self._last_polled_monotonic = 0.0

    def start(self, client: HookMcpClient) -> None:
        self._start(client)
        self.active = True

    def poll_due(self) -> bool:
        return (time.monotonic() - self._last_polled_monotonic) >= self.min_poll_interval_s

    def poll(self, client: HookMcpClient) -> list[HookFire]:
        self._last_polled_monotonic = time.monotonic()
        fires = self._poll(client)
        self.fired_count += len(fires)
        return fires

    def stop(self, client: HookMcpClient) -> None:
        if self.active:
            self._stop(client)
            self.active = False

    # -- overridden by subclasses --
    def _start(self, client: HookMcpClient) -> None:
        raise NotImplementedError

    def _poll(self, client: HookMcpClient) -> list[HookFire]:
        raise NotImplementedError

    def _stop(self, client: HookMcpClient) -> None:
        raise NotImplementedError


class EventHook(Hook):
    """Zero-jar-change hook backed by the REAL minecraft.event.subscribe/poll/unsubscribe
    capabilities, via the dedicated lodestone_events_* MCP tools (see module docstring fact #1).
    Only ever fires for event names an adapter actually publishes today (fact #2) - construction
    warns loudly (does not raise; the catalog itself performs no such validation) if event_prefix
    cannot match anything in KNOWN_PUBLISHED_EVENT_PREFIXES, so a doomed subscription is visible
    immediately instead of hanging silently forever.
    """

    def __init__(
        self,
        name: str,
        event_prefix: str,
        predicate: Callable[[dict[str, Any]], bool] | None = None,
        buffer_limit: int = 64,
        max_events_per_poll: int = 100,
        **hook_kwargs: Any,
    ) -> None:
        super().__init__(name, **hook_kwargs)
        self.event_prefix = event_prefix
        self.predicate = predicate
        self.buffer_limit = buffer_limit
        self.max_events_per_poll = max_events_per_poll
        self.subscription_id: str | None = None
        if _looks_unreachable(event_prefix):
            LOG.warning(
                "hook %r: eventPrefix=%r matches none of the event names any live Lodestone adapter "
                "is currently known to publish (see KNOWN_PUBLISHED_EVENT_PREFIXES). The subscribe "
                "call will still succeed - the capability does not validate this - but this hook may "
                "never fire. Confirmed live example: no adapter publishes any minecraft.inventory.* "
                "event today; use InventoryDeltaHook for inventory-content conditions instead.",
                name,
                event_prefix,
            )

    def _start(self, client: HookMcpClient) -> None:
        result = client.call_tool(
            "lodestone_events_subscribe", {"eventPrefix": self.event_prefix, "bufferLimit": self.buffer_limit}
        )
        if result["status"] != "ok":
            raise LodestoneMcpError(f"hook {self.name!r}: lodestone_events_subscribe failed: {result}")
        self.subscription_id = result["output"]["id"]
        LOG.info("hook %r: subscribed id=%s prefix=%r", self.name, self.subscription_id, self.event_prefix)

    def _poll(self, client: HookMcpClient) -> list[HookFire]:
        if self.subscription_id is None:
            return []
        result = client.call_tool(
            "lodestone_events_poll",
            {"subscriptionId": self.subscription_id, "maxEvents": self.max_events_per_poll},
        )
        if result["status"] != "ok":
            LOG.warning("hook %r: lodestone_events_poll failed: %s", self.name, result)
            return []
        fires: list[HookFire] = []
        for envelope in result["output"].get("events", []):
            # `lodestone.events.lost` is a synthetic marker EventHub.poll() injects when the bounded
            # per-subscription buffer overflowed (see EventHub.Subscription.poll()) - surface it as a
            # fire too rather than silently discarding it, since it means real events were dropped.
            if envelope.get("event") != "lodestone.events.lost" and self.predicate is not None:
                try:
                    if not self.predicate(envelope):
                        continue
                except Exception:  # noqa: BLE001 - a broken predicate must not kill the whole hook loop
                    LOG.exception("hook %r: predicate raised on envelope %r", self.name, envelope)
                    continue
            fires.append(
                HookFire(
                    hook_name=self.name,
                    fired_at_utc=datetime.now(timezone.utc).isoformat(),
                    message=f"event {envelope.get('event')!r} observed (seq={envelope.get('sequence')})",
                    payload=envelope,
                )
            )
        return fires

    def _stop(self, client: HookMcpClient) -> None:
        if self.subscription_id is not None:
            client.call_tool("lodestone_events_unsubscribe", {"subscriptionId": self.subscription_id})


def _extract_slot_item_and_count(slot: dict[str, Any]) -> tuple[str, int]:
    """protocol/catalog/core-capabilities.json's outputSchema for minecraft.inventory.read declares
    `"slots":{"type":"array"}` with NO items schema - the catalog does not pin per-slot field names.
    goal-orchestrator-milestone1.py's own verify_log_in_inventory() defensively reads slot.get("item",
    "") for exactly this reason (real field name confirmed only by a live call, which this draft could
    not make - no live client access, see module docstring). Mirrors that same defensiveness, trying
    a short list of plausible key names for both the item id and the stack count rather than assuming
    one; update this once a live minecraft.inventory.read response is captured.
    """
    if not isinstance(slot, dict):
        return "", 0
    item_id = str(slot.get("item") or slot.get("id") or slot.get("itemId") or "")
    count = slot.get("count")
    if count is None:
        count = slot.get("quantity", 0)
    try:
        return item_id, int(count)
    except (TypeError, ValueError):
        return item_id, 0


class InventoryDeltaHook(Hook):
    """The concrete, zero-jar-change answer to the owner's literal example ("x material pop into
    inventory"): polls the real minecraft.inventory.read query capability (already implemented on
    the live NeoForge 1.21.1 adapter - confirmed via NeoForgeAdapter.java's IMPLEMENTED set and
    handlers map) and diffs slot contents client-side. This is push-emulated-by-poll, not a true
    server-pushed event: latency is bounded by min_poll_interval_s, not one game tick. See the
    design report's jar-change sketch for what a real push-based version would need.

    Fires when the summed count of items whose id matches `item_match` INCREASES by at least
    `min_increase` since the last poll (default: since the hook started, i.e. it does NOT fire
    just because the item was already present when the hook was registered - set
    fire_if_already_present=True to change that). Only fires once per crossing, not on every poll
    while the condition remains true - the internal baseline is updated on every poll regardless of
    whether it fired, exactly like a debounced watcher.
    """

    def __init__(
        self,
        name: str,
        item_match: str | Callable[[str], bool],
        min_increase: int = 1,
        player: str | None = None,
        fire_if_already_present: bool = False,
        **hook_kwargs: Any,
    ) -> None:
        super().__init__(name, **hook_kwargs)
        self._match = (lambda item_id: item_match.lower() in item_id.lower()) if isinstance(
            item_match, str
        ) else item_match
        self._item_match_repr = item_match if isinstance(item_match, str) else getattr(
            item_match, "__name__", "<callable>"
        )
        self.min_increase = min_increase
        self.player = player
        self.fire_if_already_present = fire_if_already_present
        self._baseline_count: int | None = None

    def _read_matched_total(self, client: HookMcpClient) -> int | None:
        input_payload = {"player": self.player} if self.player else {}
        result = client.invoke_capability("minecraft.inventory.read", input_payload)
        if result["status"] != "ok" or not isinstance(result.get("output"), dict):
            LOG.warning("hook %r: minecraft.inventory.read failed: %s", self.name, result)
            return None
        slots = result["output"].get("slots") or result["output"].get("items") or []
        total = 0
        for slot in slots:
            item_id, count = _extract_slot_item_and_count(slot)
            if item_id and self._match(item_id):
                total += count
        return total

    def _start(self, client: HookMcpClient) -> None:
        baseline = self._read_matched_total(client)
        # If fire_if_already_present, seed the baseline at 0 so the very first poll's real count -
        # if already >= min_increase - immediately fires; otherwise seed at the real current total
        # so only a further increase counts.
        self._baseline_count = 0 if self.fire_if_already_present else (baseline or 0)
        LOG.info(
            "hook %r: watching item_match=%r, starting baseline=%s (fire_if_already_present=%s)",
            self.name,
            self._item_match_repr,
            self._baseline_count,
            self.fire_if_already_present,
        )

    def _poll(self, client: HookMcpClient) -> list[HookFire]:
        current = self._read_matched_total(client)
        if current is None:
            return []
        baseline = self._baseline_count if self._baseline_count is not None else current
        delta = current - baseline
        fires: list[HookFire] = []
        if delta >= self.min_increase:
            fires.append(
                HookFire(
                    hook_name=self.name,
                    fired_at_utc=datetime.now(timezone.utc).isoformat(),
                    message=(
                        f"matched inventory total for {self._item_match_repr!r} rose from {baseline} to "
                        f"{current} (+{delta})"
                    ),
                    payload={"itemMatch": self._item_match_repr, "previousTotal": baseline, "currentTotal": current},
                )
            )
        # Always resync the baseline after a poll, matched or not, so this is a debounced
        # crossing-detector rather than an "every poll while above threshold" spammer.
        self._baseline_count = current
        return fires

    def _stop(self, client: HookMcpClient) -> None:
        pass  # nothing to release - this hook never held any server-side subscription state


# ====================================================================================================
# Manager
# ====================================================================================================
@dataclass
class HookManager:
    """Owns a set of registered Hooks and drives their lifecycle. Designed to be ticked once per
    outer iteration of a realtime decide-execute-observe loop (see goal-orchestrator-milestone1.py's
    run_loop_cli() for the loop shape this is meant to sit inside) rather than run on a background
    thread - the existing orchestrator is single-threaded and synchronous (one `claude -p` subprocess
    call per turn), and interleaving hook polls with model turns keeps the trace deterministic and
    easy to log, matching this codebase's existing preference for explicit, traceable steps over
    hidden concurrency (see run_loop_cli()'s own extensive comments on exactly this kind of
    trade-off).
    """

    hooks: dict[str, Hook] = field(default_factory=dict)

    def register(self, client: HookMcpClient, hook: Hook) -> None:
        if hook.name in self.hooks:
            raise ValueError(f"a hook named {hook.name!r} is already registered")
        hook.start(client)
        self.hooks[hook.name] = hook

    def unregister(self, client: HookMcpClient, name: str) -> None:
        hook = self.hooks.pop(name, None)
        if hook is not None:
            hook.stop(client)

    def tick(self, client: HookMcpClient) -> list[HookFire]:
        """Poll every due, active hook once. Returns the NOTIFY-mode fires (for the caller to surface
        to the model, e.g. append to a run_loop_cli-style `history` list via
        render_hook_fires_for_prompt() below) after already having synchronously invoked every
        CALLBACK-mode hook's callback. once=True hooks are stopped and removed after their first
        fire.
        """
        notify_fires: list[HookFire] = []
        for name in list(self.hooks.keys()):
            hook = self.hooks.get(name)
            if hook is None or not hook.active or not hook.poll_due():
                continue
            try:
                fires = hook.poll(client)
            except Exception:  # noqa: BLE001 - one hook's transport hiccup must not kill every other
                # hook or the outer goal loop; log and move on, exactly like run_loop_cli()'s own
                # per-turn exception handling philosophy.
                LOG.exception("hook %r: poll raised", name)
                continue
            for fire in fires:
                if hook.fire_mode is HookFireMode.CALLBACK:
                    assert hook.callback is not None  # enforced in Hook.__init__
                    try:
                        hook.callback(fire)
                    except Exception:  # noqa: BLE001 - a broken callback must not kill the loop
                        LOG.exception("hook %r: callback raised for fire %r", name, fire)
                else:
                    notify_fires.append(fire)
            if fires and hook.once:
                self.unregister(client, name)
        return notify_fires

    def close(self, client: HookMcpClient) -> None:
        """Release every remaining hook's server-side state (event subscriptions). Mirrors
        McpGateway's own session-close subscription cleanup (McpGateway.java line ~251:
        `session.subscriptions.forEach(...) -> runtime.unsubscribe(...)`) - a hooks-driven run that
        exits without calling this would leak lodestone.event subscriptions until the MCP session
        itself is torn down.
        """
        for name in list(self.hooks.keys()):
            self.unregister(client, name)


def render_hook_fires_for_prompt(fires: list[HookFire]) -> list[str]:
    """NOTIFY-mode integration point: turns HookFires into plain-text lines shaped exactly like
    goal-orchestrator-milestone1.py's render_history_entry() output, so they can be appended
    directly to that file's run_loop_cli() `history: list[str]` (surfaced to the model on its very
    next turn) WITHOUT modifying that file - the caller does:
        history.extend(render_hook_fires_for_prompt(hook_manager.tick(mcp_client)))
    right before build_cli_prompt(goal, tool_catalog_text, history) on each turn.
    """
    return [
        f"[hook:{fire.hook_name}] {fire.fired_at_utc}: {fire.message} "
        f"(payload={json.dumps(fire.payload, separators=(',', ':'), default=str)})"
        for fire in fires
    ]


# ====================================================================================================
# Demo / smoke-test entry point. NOT executed as part of this design task (constraint: no live
# Minecraft client access) - included so this draft is ready to run the moment a live client is
# available, not just a set of classes with no worked example. Mirrors goal-orchestrator-milestone1.py's
# main() argument shape (--port/--token) so it can reuse the same launch scripts' port/token output.
# ====================================================================================================
def _demo_main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", type=str, required=True)
    parser.add_argument("--watch-item", type=str, default="log", help="substring to match against inventory item ids")
    parser.add_argument("--ticks", type=int, default=30)
    parser.add_argument("--interval-s", type=float, default=2.0)
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    client = HookMcpClient(args.port, args.token)
    client.initialize()

    manager = HookManager()
    manager.register(
        client,
        InventoryDeltaHook(
            name="watch-item",
            item_match=args.watch_item,
            min_increase=1,
            min_poll_interval_s=max(args.interval_s, 1.0),
        ),
    )
    manager.register(
        client,
        EventHook(
            name="watch-chat",
            event_prefix="minecraft.chat.received",
            min_poll_interval_s=max(args.interval_s, 1.0),
        ),
    )

    try:
        for tick in range(1, args.ticks + 1):
            fires = manager.tick(client)
            for line in render_hook_fires_for_prompt(fires):
                print(f"tick {tick}: {line}")
            time.sleep(args.interval_s)
    finally:
        manager.close(client)
    return 0


if __name__ == "__main__":
    raise SystemExit(_demo_main())
