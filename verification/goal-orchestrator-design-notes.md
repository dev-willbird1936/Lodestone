# Goal orchestrator v2 design notes: script mode, safety tiers, modularization

Status: **draft design report, not integrated.** Nothing in this document or its companion
sketch package (`verification/goal_orchestrator_draft/`) modifies
`verification/goal-orchestrator-milestone1.py` or any other existing file. That file is currently
owned by a separate, in-progress live-testing session (mutation-quarantine bug fix, then wiring a
new capability call into the orchestrator) — this report is written to be picked up **after** that
work lands, not concurrently with it. See "Coordination and sequencing" at the end.

Grounding: this report is based on a full read of the real
`verification/goal-orchestrator-milestone1.py` (1008 lines, as of commit `c5dbdfb`, "Auto-call
minecraft.session.reconcile on CAPABILITY_QUARANTINED in the orchestrator") and the real capability
schemas in `protocol/catalog/core-capabilities.json` (`minecraft.goal.navigation.safe-waypoint`,
`minecraft.entity.nearby.read`, `minecraft.player.state.read`, `minecraft.world.light.analyze`,
`minecraft.world.region.scan`, `minecraft.player.context.read`). Field names cited below
(`health`, `food`, `entities[].distance`, `mobSpawnRisk`, `darkSpots[].risk`, safe-waypoint's own
`safety`/`intelligence`/`combatPolicy` enum params) are copied from those real schemas, not
invented.

---

## 1. Script mode design

### 1.1 The core idea: batching is already latent in the loop shape, not a new mechanism

The realtime loop (`run_loop()`, api backend, lines 574-659) already has the raw mechanic script
mode needs: one model turn can contain **multiple** `tool_use` blocks, all of which get dispatched
and their results returned together before the next turn. Realtime mode only *feels* like
one-action-at-a-time because `SYSTEM_PROMPT_TEMPLATE` (line 362) explicitly tells the model "decide
the next single subaction... do not plan many steps ahead." Script mode does not need a different
orchestrator loop shape for the api backend — it needs a **different prompt** that permits (and
teaches good judgment about) multi-action turns, plus a couple of real orchestrator-level additions
(fail-fast batch execution, a defensive size cap, batch-aware tracing) that both modes benefit from.

The cli backend (`run_loop_cli()`, lines 662-813, the actual default today per the module docstring
— no API key on this machine) currently hard-codes a **single**-tool decision shape:
`{"tool": "<name-or-null>", "arguments": {...}, "done": bool, "rationale": "..."}`
(`CLI_SYSTEM_PROMPT_TEMPLATE`, lines 420-423). This needs an additive extension, not a rewrite —
see 1.4.

### 1.2 Where the "poll first, then script" behavior comes from

The owner's spec: *"scripting will at the start of the new subagent, poll minecraft for whatever
data it would want... then create an all in one script of other subactions."*

This is not a distinct orchestrator-managed "phase" — it falls out for free from allowing
multi-action batches plus history replay:

- Turn 1 of a script-mode sub-goal: the model, seeing no relevant data yet, puts only read-only
  observation calls in its batch (e.g. `minecraft_world_region_scan`, `minecraft_entity_nearby_read`,
  `minecraft_inventory_read`). The harness executes them and returns every result.
- Turn 2: the model now has that data (either still visible in the same api-backend `messages`
  list, or replayed in the cli backend's rebuilt-from-scratch prompt history — see
  `render_history_entry`, line 441, and `build_cli_prompt`, line 452, both already do full replay).
  It now emits the actual action batch — the "script" — using the data from turn 1.
- Turn 3+: whenever the model judges it has hit a boundary (1.3), it stops the batch, the harness
  executes what was requested, and the model gets a fresh turn to decide whether to observe again,
  batch more actions, or declare done.

So "poll, then script" is just the model choosing to make its first batch entirely observational.
**Do not build a hardcoded two-step observe-then-act ritual into the orchestrator** — it would
constrain exactly the flexibility the owner's own examples require (the flint-mining case needs
*repeated* observe-mine-observe-mine cycles, not one poll followed by one giant script).

### 1.3 The batch-boundary mechanism (the part the owner said "get right")

This is the actual design risk in script mode: if the boundary is too generous, the model plans
blind and wastes real, session-scoped, rate-limited mutating calls (see the module docstring's
`SHARED_MUTATION_ORDER` / `CAPABILITY_QUARANTINED` discussion — a single indeterminate outcome from
a blind multi-step batch can quarantine every other mutating capability for the rest of the
session). If it's too conservative, script mode degenerates into realtime with extra prompt
overhead and buys nothing.

**Decision: the model states, per batch, why it stopped there (`boundaryReason`), and the harness
logs and later can be graded on batch-size distribution — but the actual boundary judgment is left
to the model, per the owner's explicit instruction, not hardcoded as a batch-size number.** What
*is* engineered is the criteria the model is taught to apply, worded around the owner's own two
examples so the guidance is concrete rather than abstract:

Proposed script-mode system-prompt section (goes in the new `ScriptMode` prompt, additive to the
existing realtime prompt language about safe-waypoint/survival mode, not a replacement of it):

> You operate in SCRIPT mode. Each turn, you may request a BATCH of one or more subactions,
> executed in order without you seeing intermediate results until the whole batch finishes (or one
> step fails — see below). Start a turn with observation calls (world/entity/inventory reads) if
> you don't yet have the data you need; once you do, batch the action subactions that follow from
> that data.
>
> Put a subaction in the batch only if, using ONLY what you already know — this turn's observation
> results, ordinary Minecraft rules, and the results of earlier subactions in this SAME batch — you
> can predict its precondition will hold when it runs. End the batch (do not add another subaction)
> at the first point where any of these is true:
>
> 1. The next subaction's success depends on something you cannot derive without re-checking the
>    world — whether a specific block breaks in one hit, whether every drop landed in your
>    inventory versus on the ground, whether your target still exists, whether a path is still
>    clear.
> 2. The goal itself is conditional on a threshold only observation can confirm — e.g. "mine gravel
>    until you get flint": you cannot know in advance which mine yields flint, so batch exactly one
>    mining subaction, look at the result, and only then decide whether to mine again or move on.
>    Do not pre-plan N mining attempts.
> 3. You're in an area you haven't recently checked for hostiles, or enough turns have passed that
>    one could have wandered in — keep batches short there; batch more freely in an area you just
>    confirmed clear.
> 4. You aren't confident your destination/target is reachable or still exists.
>
> A tool failing mid-batch is NOT your responsibility to predict — the harness stops the batch at
> the first failed subaction and shows you exactly what ran, what failed, and what never ran, so you
> can replan with real data. Your boundary judgment is about needing NEW INFORMATION to choose the
> RIGHT next subaction, not about fear of a call failing.
>
> Prefer `minecraft_goal_navigation_safe-waypoint` over raw movement for any batch involving travel
> — it already has its own internal path safety handling (its own `safety`/`combatPolicy`
> parameters), so a batch containing one safe-waypoint call is lower-risk to plan ahead than a batch
> of many raw `minecraft_player_move` calls, because the primitive itself will report
> `reachedTarget: false` instead of silently stranding you.
>
> Example ("mine one log and craft an axe"): batch 1 = observe (region scan + inventory read).
> batch 2 = navigate to the tree (one safe-waypoint call) + face/interact to mine one log — then
> STOP (you don't yet know the log landed in inventory). batch 3 = observe inventory; if you have a
> log, batch 4 = the full craft sequence (planks, sticks, axe) in one batch, since a Minecraft
> crafting recipe's required inputs are deterministic once you know you hold the ingredients, and a
> failed craft call stops the batch and tells you exactly why.
>
> Example ("mine gravel until you get flint, then craft flint and steel and light a nether
> portal"): batches 2..k = ONE mining subaction each, checked before deciding to mine again — this
> cannot be planned as a single upfront batch because the outcome is inherently unknown in advance.
> Once you confirm you hold flint (and iron, for flint and steel — check inventory), the portal
> construction/lighting sequence CAN be one larger batch, since every step there is now
> deterministic given items you've already confirmed you hold.

Note the explicit re-framing in the middle: "the harness stops the batch at the first failed
subaction" is doing real work here — it means the model's boundary judgment only has to reason
about *epistemic* uncertainty (do I know enough to pick the right next call), not about *defensive*
uncertainty (what if this call errors), because the orchestrator's fail-fast execution (1.5) already
covers the second concern for free. Getting this framing right is most of what makes the boundary
guidance usable rather than pushing the model toward batches of size 1 out of caution.

### 1.4 Decision protocol: additive, not a rewrite of the proven realtime path

Current cli-backend decision shape (line 420-423):
```json
{"tool": "<name-or-null>", "arguments": {...}, "done": true|false, "rationale": "..."}
```

Proposed unified shape (superset, backward compatible):
```json
{
  "actions": [{"tool": "<name>", "arguments": {...}}, ...],
  "boundaryReason": "why you stopped the batch here (or why this is a single step)",
  "done": true|false,
  "rationale": "one short sentence"
}
```

The parser (see `decision_protocol.py` sketch) accepts **either** shape: legacy singular
`tool`/`arguments` normalizes to a one-element (or zero-element, if `done`) `actions` list. This
means realtime mode's already-proven prompt and schema (`CLI_SYSTEM_PROMPT_TEMPLATE`) **do not have
to change at all** to ship script mode — script mode is purely additive (a new mode strategy, a new
prompt, a schema superset the old shape already satisfies). This is a deliberate risk-reduction
choice: realtime mode was hard-won (see the module docstring's account of the model taking the
`minecraft.goal.survival.*` shortcut until those capabilities were excluded, and the
CAPABILITY_QUARANTINED investigation) — nothing about landing script mode should touch that path's
prompt or parsing.

Realtime mode's own constraint ("actions must have length ≤ 1") becomes something the *mode*
validates after parsing, not something baked into the parser.

### 1.5 New orchestrator-level execution semantics (mode-independent, both modes benefit)

1. **Sequential execution, always.** Actions in a batch dispatch one at a time through the same
   `LodestoneMcpClient.invoke_capability()` used today. Never parallelize — `SHARED_MUTATION_ORDER`
   (module docstring, lines 3-11) is a documented session-wide ordering invariant; concurrent
   dispatch would violate it.
2. **Fail-fast.** On the first action in a batch whose result `status != "ok"`, stop executing the
   rest of that batch immediately. Record every result obtained so far (including the failing one)
   and which requested actions never ran. This is the concrete mechanism behind "maybe it realised
   the tree wasn't reachable" from the owner's own risk example — the model gets partial, honest
   results instead of the harness blindly running action 5 of a 5-action batch after action 2
   already failed.
3. **CAPABILITY_QUARANTINED auto-reconcile still fires per-action inside a batch**, exactly as it
   does today for single actions (lines 795-812) — and after firing, the fail-fast rule above still
   applies (reconcile fixes state the model hasn't seen yet, so the rest of the batch is still
   blind and should not run).
4. **Defensive batch-size cap**, e.g. `--max-batch-size` (default suggestion: 12), purely a
   last-resort guard against a degenerate/hallucinated huge batch — not the primary boundary
   mechanism, which stays the model's own judgment per 1.3. If a requested batch exceeds the cap,
   truncate to the first N, execute those, and return a synthetic note explaining the truncation so
   the model can continue with a fresh batch. Log this distinctly (`batch_truncated` trace event) —
   a high truncation rate over many runs is a real signal the boundary prompt needs tuning.
5. **New trace event `script_batch`**: turn, requested action count, `boundaryReason`/`rationale`,
   executed count, whether it stopped early and at which index/why. Reuse the existing
   `record_tool_call` per action inside the batch so per-action evidence stays identical in shape to
   today's traces. Add average-batch-size to the run summary — the cheapest signal for whether
   script mode is actually saving turns over realtime (if it converges to batch size ~1 always, the
   feature isn't earning its complexity and the prompt needs work, not the orchestrator).

### 1.6 New CLI surface

`--mode {realtime,script}` (orthogonal to the existing `--backend {cli,api}` and `--effort`).
Default should stay `realtime` (the only mode that's actually been live-verified so far, per the
module docstring's 30-turn run).

---

## 2. Safety-tier design

Owner's framing: "ongoing checks/extra thinking done by the agent... layered ON TOP of the model's
own decisions, not replacing them, and not replacing whatever native safety guards already exist"
(e.g. `minecraft.goal.navigation.safe-waypoint` already has its own `safety`
(`low`/`balanced`/`high`), `intelligence` (`raw-v1`/`guarded-v1`/`adaptive-v1`/`deliberate-v1`), and
`combatPolicy` (`defensive`/`avoid`/`none`) input parameters — confirmed live in
`protocol/catalog/core-capabilities.json`).

So safety tier must never take a decision away from the model or silently substitute the
orchestrator's own plan — every mechanism below is additive: forced *extra* observation calls,
*extra* prompt text, and a *floor* (never a silent downgrade) on a capability's own existing safety
knob.

### 2.1 Three levers, three tiers

| Lever | low | balanced | high |
|---|---|---|---|
| Forced observation cadence | none (trust the model entirely — today's behavior) | 1 forced `minecraft.entity.nearby.read` (+ `minecraft.player.state.read` for `health`/`food`) every N turns/batches if the model hasn't made an equivalent call recently | every turn (realtime) / start+end of every batch (script), regardless of what the model already checked |
| Prompt addendum | none beyond the existing baseline hazard-avoidance line already in both system prompts | explicit hostile-radius and pre-travel-check guidance (2.2) | balanced's guidance + light-level/`mobSpawnRisk` awareness + smaller default batch cap + "retreat first" override rule |
| Safe-waypoint `safety` floor | none (model's own choice passes through unmodified) | floor at `balanced` if the model omits/under-requests it | floor at `high`; also default `combatPolicy` to `avoid` if the model omits it |

Forced observation calls are made **directly by the orchestrator** through `mcp_client` (bypassing
the model), not by asking the model to spend a turn on them — they cost the model nothing and are
injected into history as a labeled synthetic observation entry, e.g.:
`"[safety-check, harness-issued] minecraft_entity_nearby_read -> {...}"`. This is exactly the
"layered on top" framing: the model still decides everything about the goal; the harness just makes
sure fresh threat data exists in context whether or not the model thought to ask for it.

### 2.2 Prompt addendum text (draft)

`balanced` tier, appended after the mode's own system prompt (never replacing goal/tool-catalog
text):

> Before any batch or turn that moves you into an area you have not recently checked, look at your
> most recent `minecraft_entity_nearby_read` result. Treat any non-player entity within your risk
> radius whose type is a known hostile (zombie, skeleton, creeper, spider, drowned, etc.) as a
> reason to shorten your next batch to a single defensive or repositioning action rather than
> continuing your original plan. Prefer `minecraft_goal_navigation_safe-waypoint` with
> `safety: "balanced"` or higher for any travel. Check your `health`/`food` (from
> `minecraft_player_state_read`) before starting anything risky (mining near lava/water, working at
> height).

`high` tier adds:

> Also treat light level as a hazard signal even with no hostile currently visible —
> `minecraft_world_light_analyze`'s `mobSpawnRisk` field (`none`/`low`/`medium`/`high`) and its
> `darkSpots[].risk` entries tell you where mobs could spawn nearby even in a currently-empty area.
> Use `safety: "high"` and `combatPolicy: "avoid"` for all travel. Keep every batch to the smallest
> set of subactions that completes one clearly bounded sub-step. If you detect any hostile within
> your risk radius, your very next action must be retreat, reposition, or a defensive action — not
> a continuation of the original plan — even if that delays the goal.

### 2.3 Safety-param flooring (never silent, never a downgrade)

When the model calls `minecraft_goal_navigation_safe-waypoint` (or any future capability with its
own safety-style enum param):

- If the model omits the field entirely, the orchestrator fills in the tier's floor value before
  dispatch.
- If the model explicitly requests a value **below** the tier's floor, the orchestrator raises it to
  the floor and — critically — **says so in the tool result content handed back to the model**
  (e.g. `"note": "requested safety='low' was raised to this session's floor 'high'"`), so the model
  is never confused about what actually ran. It is a floor, not a substitute plan: intelligence,
  combatPolicy (unless omitted), and the target coordinates are always exactly what the model chose.
- If the model requests a value **at or above** the floor, nothing changes — the model's own,
  possibly-more-cautious-than-required choice always wins.

### 2.4 Reactive re-check (not just periodic)

Independent of the periodic cadence in 2.1, force an out-of-band `minecraft_entity_nearby_read` +
`minecraft_player_state_read` pair immediately whenever any tool result in the run shows a
concerning delta — concretely: `health` dropped versus the last known reading, or a mutating call's
own output signals an unexpected outcome (`directFallback: true`, non-zero
`safetyInterventions`/`replans` on a safe-waypoint result, etc.). This applies at `balanced` and
`high`; at `low` it is skipped entirely, matching "none of this exists yet" for that tier.

### 2.5 Explicitly out of scope for a first cut

A harder guard — client-side refusal of certain raw primitives (e.g. `minecraft.world.blocks.write`
removing a block under the player) unless a corroborating recent observation exists — is a
plausible `high`-tier escalation but is **not** part of this proposal's MVP. Flagging it here as a
considered-and-deferred option rather than silently omitting it, since it's the kind of thing that's
easy to want later and easy to get wrong (over-blocking legitimate model actions) if bolted on
without its own design pass.

---

## 3. Modularization plan

### 3.1 Why now

`goal-orchestrator-milestone1.py` is already 1008 lines and every deliverable above wants to touch
it: script mode wants a new decision schema + new prompt + new batch-execution semantics; safety
tiers want new prompt text + new forced-call hooks + a floor mechanism; the file's own docstring
already documents three rounds of live-discovered fixes (capability filtering,
bootstrap-before-catalog ordering, CAPABILITY_QUARANTINED auto-reconcile) landing in this same file.
Every future change is a collision risk against whichever other session is also mid-edit on it —
exactly the situation today (this task exists because a separate live session owns this file right
now).

### 3.2 Mapping the current file's real structure to modules

| Current location (real, as read) | New module | Contents |
|---|---|---|
| Lines 199-306 (`LodestoneMcpError`, `LodestoneMcpClient`, `capability_id_to_tool_name`) | `mcp_client.py` | The real MCP transport. No loop/mode/safety logic. |
| Lines 309-359 (`build_tool_catalog`) | `catalog.py` | Capability-list → Anthropic-tool-schema + dispatch table. Pure, testable in isolation from any backend. |
| Lines 489-568 (`TraceWriter`) | `trace.py` | Add `record_script_batch`, `record_safety_event` here; keep the existing methods byte-for-byte so old evidence-parsing tooling doesn't break. |
| Lines 362-426 (both `SYSTEM_PROMPT_TEMPLATE` / `CLI_SYSTEM_PROMPT_TEMPLATE`) + 429-457 (`render_tool_catalog_for_prompt`, `render_history_entry`, `build_cli_prompt`) | `modes/base.py`, `modes/realtime.py`, `modes/script.py` | A `Mode` protocol (`system_prompt(goal, safety_addendum) -> str`, `validate_decision(decision) -> list[ActionRequest]`, `max_batch_size`). `realtime.py` reuses the *existing, unmodified* prompt text. `script.py` is new (1.3's prompt). |
| Lines 460-486 (`call_claude_cli`) | `backends/cli_backend.py` | Backend = "how do I get a decision out of a model given a prompt". `backends/api_backend.py` would hold the api-backend equivalent extracted from `run_loop()`'s `model_client.messages.create(...)` call. Both implement one shared `ModelBackend.decide(...)` shape so modes/safety never need to know which backend is active. |
| New (doesn't exist today) | `decision_protocol.py` | The unified `{"actions": [...], "boundaryReason", "done", "rationale"}` schema (1.4), parsing + normalization (accepts legacy singular `tool`/`arguments` too), shared by both modes and both backends. |
| New (doesn't exist today) | `safety.py` | `SafetyTier` enum, prompt addenda (2.2), `apply_safety_floor()` (2.3), and `build_safety_hooks(tier) -> list[Hook]` (2.1/2.4) — implemented *as hooks* (3.3), not as special-cased branches inside the loop. |
| Lines 785-812 (CAPABILITY_QUARANTINED auto-reconcile) | `hooks/quarantine_recovery.py` | Extracted as a hook (an `after_action` handler that watches for `error.code == "CAPABILITY_QUARANTINED"`), always installed regardless of mode/safety tier — same behavior as today, just no longer inline in the loop. |
| Lines 574-659 (`run_loop`) + 662-813 (`run_loop_cli`) minus the parts moved above | `loop.py` | The generalized control flow: per turn, ask the active backend for a decision (via the active mode's prompt), validate/normalize it, execute the resulting action list sequentially with fail-fast (1.5), running installed hooks at 4 points (3.3), writing trace events. One loop, parameterized by `(backend, mode, safety_tier, hooks)` — not two near-duplicate functions. |
| Lines 816-848 (`ensure_fresh_world`), 851-866 (`verify_log_in_inventory`) | `bootstrap.py` | Unchanged logic; these are goal-agnostic infra helpers already, not touched by any of the three deliverables. |
| Lines 869-1008 (`main()`) | `cli.py` | Thin composition root: argparse (`--mode`, `--safety`, `--backend`, existing flags), builds the mode/backend/hooks objects, calls `loop.run(...)`, writes the summary — same shape as today's `main()`, just wiring pre-built pieces instead of containing all the logic inline. |

### 3.3 The hook protocol — the actual mechanism that keeps future features from colliding

```python
class Hook(Protocol):
    def before_turn(self, ctx: TurnContext) -> None: ...
    def before_action(self, ctx: TurnContext, action: ActionRequest) -> ActionRequest: ...
    def after_action(self, ctx: TurnContext, action: ActionRequest, result: dict) -> HookSignal: ...
    def after_turn(self, ctx: TurnContext) -> None: ...
```

`loop.py` owns a small ordered list of installed hooks and calls all four points every turn/action;
it has **no knowledge** of what safety tiers or quarantine-recovery are. `safety.py` builds a list
of `Hook` instances from a `SafetyTier` and hands them to `cli.py` at startup; `quarantine_recovery`
is always-installed hook. This is what actually delivers "script-mode work, safety-tier work, and
hooks work land as genuinely separate files": a new feature (a stuck-loop detector, a cost governor,
a Discord notifier — anything reacting to turns/actions/results) is a brand-new file implementing
`Hook`, registered in `cli.py`'s composition step. It never requires editing `loop.py`, `modes/*`,
or `safety.py`.

`HookSignal` is a tiny enum the fail-fast execution loop (1.5) checks after every action:
`CONTINUE` (keep going), `ABORT_BATCH` (stop the rest of this batch — used by quarantine-recovery
and could be used by a future emergency-stop hook), `FORCE_REOBSERVE` (inject the safety layer's
reactive re-check before the next turn, 2.4).

### 3.4 What this buys concretely, given the brief's own three features

- Script mode lands as `modes/script.py` + `decision_protocol.py` (new file) + the fail-fast/cap
  additions to `loop.py`. It does not touch `modes/realtime.py`, `safety.py`, or any hook file.
- Safety tiers land as `safety.py` (new) + zero or more new files under `hooks/` if a tier needs a
  genuinely new reactive behavior beyond what `Hook`'s four points already support. It does not
  touch `modes/*` or `mcp_client.py`/`catalog.py`.
- A future "hooks work" (the brief's own third named category) is, almost by definition, just "add
  a file implementing `Hook`" — the protocol itself (3.3) *is* that extension point, already
  factored out rather than left implicit inside a 1008-line loop function.

### 3.5 Package naming note

Real `verification/*.py` files today are standalone hyphenated scripts
(`goal-orchestrator-milestone1.py`, `sonnet5-goal-model-proxy.py`) — fine for entry points, but a
Python package meant to be *imported* (`from goal_orchestrator.modes import script`) needs
underscore-safe identifiers. The draft sketch below therefore lives under
`verification/goal_orchestrator_draft/` (underscore, `_draft` suffix to make status obvious in a
directory listing). A future real adoption would likely drop the `_draft` suffix and become the
actual importable package, with `verification/goal-orchestrator-milestone1.py` itself shrinking to
a thin `cli.py`-equivalent entry point (or being retired in favor of one under the new package,
implementer's call).

### 3.6 Illustrative diff (text only — not applied to the real file)

This is what adopting `--mode`/`--safety` flags into the *existing* `main()` would look like at the
seam, shown as a unified diff for illustration only:

```diff
--- a/verification/goal-orchestrator-milestone1.py
+++ b/verification/goal-orchestrator-milestone1.py
@@ argparse setup in main()
     parser.add_argument("--effort", type=str, default="low", choices=["low", "medium", "high", "xhigh", "max"])
+    parser.add_argument("--mode", type=str, default="realtime", choices=["realtime", "script"])
+    parser.add_argument("--safety", type=str, default="low", choices=["low", "balanced", "high"])
     parser.add_argument("--max-turns", type=int, default=20)
@@ dispatch in main()
-        if args.backend == "cli":
-            loop_result = run_loop_cli(
-                mcp_client, tools, dispatch, args.goal, model, args.effort, args.max_turns, trace
-            )
-        else:
-            ...
-            loop_result = run_loop(
-                mcp_client, model_client, model, args.effort, tools, dispatch, args.goal, args.max_turns, trace
-            )
+        mode = build_mode(args.mode)
+        hooks = build_safety_hooks(args.safety) + [QuarantineRecoveryHook()]
+        backend = build_backend(args.backend, model, args.effort)
+        loop_result = run(mcp_client, backend, mode, hooks, tools, dispatch, args.goal, args.max_turns, trace)
```

Not applied anywhere; shown for a future implementer's reference only.

---

## 4. Coordination and sequencing

A separate, currently-running session owns `verification/goal-orchestrator-milestone1.py` right
now: finishing a mutation-quarantine bug fix (new Java capability), then wiring a call to that
capability into this same orchestrator file, then a live rerun. Recommended sequencing for whoever
picks this design up:

1. Let that session land and commit its capability-wiring change first — it is a small, targeted
   edit to the existing monolith and should not conflict with anything here if it lands cleanly
   before the modularization split begins.
2. Do the mechanical split (3.2) as its own commit, preserving current behavior exactly (realtime +
   cli backend unchanged, byte-identical trace event shapes for existing event types) — this should
   be verifiable by diffing trace output for an identical run before/after the split, not by
   guesswork.
3. Land script mode (section 1) as an additive mode on top of the split, default-off (`--mode`
   defaults to `realtime`).
4. Land safety tiers (section 2) as an additive hook bundle, default-off (`--safety` defaults to
   `low`, which is a no-op per the table in 2.1).

This ordering means at every commit the previously-proven realtime/cli/`low`-safety path keeps
working unchanged, and each new capability is opt-in via an explicit flag until it's been live
-verified on its own.

---

## Addendum: MCP gateway wiring (applied)

Status update: the gateway-wiring design that was originally produced alongside this report (as
"Lane C" of the same parallel design Workflow) was returned as workflow output text but was never
actually written to this file — a supervisor-side gap, not a Lane A/B issue. It has since been
**applied for real** (not just drafted) by a follow-up implementer agent, verified via a real
`./gradlew.bat :gateway:mcp-server:test` run (53/53 passing, including 14 new/updated tests), with
an independent adversarial-verification pass launched immediately after to re-check permission
scoping, token handling, and process-tree-kill correctness before fully trusting it.

Applied files:
- `gateway/mcp-server/src/main/java/dev/lodestone/gateway/LoopbackHttpServer.java` — allows `port=0`
  (OS-assigned ephemeral port).
- `gateway/mcp-server/src/main/java/dev/lodestone/gateway/GoalExecutionQueue.java` — doc-only update
  explaining why serialization still matters post-migration.
- `gateway/mcp-server/src/main/java/dev/lodestone/gateway/GoalService.java` — `run()` now rejects
  `mode=script`, `dryRun`, a custom `plan`, `taskId`, and `worldSeed` with `IllegalArgumentException`
  before queueing, and delegates real execution to the new `GoalOrchestratorLauncher`.
- `gateway/mcp-server/src/main/java/dev/lodestone/gateway/McpGateway.java` — `minecraft_goal` tool
  description/schema rewritten for honesty about the new realtime-only backing.
- New: `gateway/mcp-server/src/main/java/dev/lodestone/gateway/GoalOrchestratorLauncher.java` — spawns
  `verification/goal-orchestrator-milestone1.py` as a subprocess against a throwaway ephemeral
  `McpGateway`/`LoopbackHttpServer` sharing the caller's own live `LodestoneRuntime` and
  already-resolved `AuthorizationPolicy` (never more permission than the caller had), Windows
  process-tree kill (`ProcessHandle#descendants` + `taskkill /T /F` fallback), `maxDurationMs`
  timeout, and evidence-file (`summary-*.json`) based mapping back to `GoalRunReport`/`GoalStatus`.

Deliberate deviations from the original text design (see the implementer's own report for full
rationale): `--skip-bootstrap` is always passed (the caller's `LodestoneRuntime` already has a live
world, so the script's cold-start menu navigation would be harmful); `--max-turns` is
`min(spec.maxSteps(), 60)`; `intelligence`/`safety`/`observation`/`combatPolicy`/`allow*` params are
accepted but currently have no effect under this backing (documented honestly in the tool
description rather than silently accepted or newly rejected, since rejecting them was out of the
original five-param scope); script/python executable resolution is heuristic
(`-Dlodestone.goalOrchestrator.scriptPath` → `lodestone.rootDir` → walk-up search → env → PATH),
untested against a real in-game host process yet.

Full original Lane C report text (design rationale, now-superseded diffs, and follow-ups list),
preserved verbatim for reference:

<details>
<summary>Original "Design Report: Wiring minecraft_goal to the Python Orchestrator" (click to expand)</summary>

Scope note up front: everything below is a **design + draft diff only**, as originally written. It
has since been superseded by the applied version described above — kept here only for the design
rationale (ephemeral gateway, throwaway token, process-tree kill approach) that isn't otherwise
captured in code comments.

I read `McpGateway.java`, `GoalService.java`, `GoalExecutionQueue.java`, `GoalQueueTicket.java`,
`LoopbackHttpServer.java`, `GoalRunReport.java`/`GoalStatus.java`/`GoalMode.java`/`GoalSpec.java`
under `common/goal-engine`, `InstanceRegistry.java`/`TokenFile.java`/`InstanceRegistryEntry.java`
under `common/runtime-core`, `hosts/neoforge/1.21.1/.../LodestoneNeoForgeMod.java`, and
`verification/goal-orchestrator-milestone1.py` (full argparse contract, `main()`,
`ensure_fresh_world`, `call_claude_cli`) to ground this. Nothing under `adapters/fabric`/`hosts/fabric`
was touched or needed.

### 1. Verified ground truth (line numbers as of the original read, since drifted)

In `gateway/mcp-server/src/main/java/dev/lodestone/gateway/McpGateway.java` (1396 lines, HEAD
`21c81da` at the time): `instructions` string mentioning `minecraft_goal` at lines 274-276; goal tool
registrations at lines 312-346; `toolCall()` switch cases at 628-630; `goalRun(args)` at 976-1006;
`supportsNeoForgeGoals()`/`requireNeoForgeGoals()` at 1054-1063.

### 2. The central design decision: don't reuse the host's real port/token — mint a throwaway one

`McpGateway` and `GoalService` do not know their own serving port or token — `LoopbackHttpServer` owns
both, constructed independently per host. Two approaches were considered and rejected: (a)
self-discovery via `InstanceRegistry` scanning — rejected because `InstanceRegistry.write()` is
explicitly best-effort and can silently find nothing; (b) constructor injection of port+token through
every host file — rejected as touching 10+ files outside the task's blast radius for a token that
would then ride along inside `McpGateway` for its whole lifetime with no reason to.

What was actually proposed (and is what got applied): the orchestrator does not need the *same*
`McpGateway` object or port/token pair — it needs the same live `LodestoneRuntime`/native actor
underneath. A throwaway `McpGateway` wraps the same `runtime`, with a `CallerGrantResolver` that
always returns the *original caller's already-resolved* `AuthorizationPolicy` (so the subprocess can
never gain more permission than the human/tool that invoked `minecraft_goal` already had), wrapped in
a `LoopbackHttpServer` bound to port 0 with a freshly random, single-use token, torn down the moment
the subprocess finishes.

Why this is correct: it really is "the same running Lodestone MCP loopback endpoint" at the level
that matters (same runtime, same live world/native actor, same negotiated capabilities —
`LodestoneRuntime` is already designed for multiple independent caller sessions); zero
session/token leakage into the long-lived main gateway's `sessions` map (hard `MAX_SESSIONS = 128`
cap, 30-minute TTL sweep only on the next unrelated request — reusing the main gateway directly could
eventually exhaust that cap during a benchmark sweep); no `ThreadLocal<GatewaySession>` collision risk
(the ephemeral server's own `ExecutorService` runs on threads distinct from the thread blocked in
`GoalExecutionQueue.run()` → `Process.waitFor()`); minimal token blast radius (random, loopback-only,
one-shot, dead the moment the port closes). `LoopbackHttpServer`'s constructor rejected `port == 0`
before this change even though `port()` already correctly read back the OS-assigned port — it looked
written with this in mind and just never got a `port == 0` caller.

### 3. `GoalExecutionQueue` preserved exactly, with an updated justification

Not touched behaviorally (one javadoc sentence added). `GoalExecutionQueue`'s own javadoc says it
exists because "exactly one native Minecraft goal actor can run at a time" — technically stale
post-migration (the new orchestrator doesn't drive `GoalEngine` at all, just ordinary subaction
capabilities like any other MCP client). But the practical need is arguably stronger now: two
concurrently-spawned orchestrator subprocesses would each independently drive the same single player
entity with zero shared plan or coordination — worse than the old single-actor race, not better. Same
queue, same serialization, updated doc comment.

### 4. Tool-contract compatibility mapping

The milestone-1 orchestrator only understands `--port --token --goal --backend --model --effort
--max-turns --evidence-dir --skip-bootstrap` — no task-catalog, declarative-plan, dry-run,
safety-tier, or world-seed concept. `minecraft_goal`'s existing schema is much richer:

| Existing arg | New behavior | Why |
|---|---|---|
| `goal` | Passed through as `--goal` | Direct match |
| `mode` | Hard reject if `script` | Realtime-loop only, no batch/script mode exists |
| `dryRun=true` | Hard reject | Every subaction is a real invocation |
| `plan` | Hard reject if present | No declarative-plan concept |
| `taskId` | Hard reject if present | Old task catalog doesn't back this path anymore |
| `worldSeed` | Hard reject if present | Silent non-honoring is worse than refusing |
| `maxSteps` | Mapped to `--max-turns`, clamped | Unit mismatch: a "turn" is a full model call, not a cheap engine step |
| `maxDurationMs` | Enforced Java-side as subprocess wall-clock timeout | No orchestrator-side equivalent |
| `intelligence`/`safety`/`combatPolicy`/`allow*`/`suppressInGameMessages` | Soft-accept, echoed, `state` map marks `*Enforced: false` | Policy dials the old `GoalEngine` enforced don't exist yet for the orchestrator; baseline `AuthorizationPolicy`/capability negotiation is still structurally enforced |
| `priority` | Unchanged | No change needed |

`minecraft_goal_tasks`/`minecraft_goal_benchmark` were explicitly out of scope, left on the old
`GoalTaskCatalog`/`GoalEngine`/`GoalBenchmarkRunner` path.

### 5. Subprocess lifecycle on Windows

Process tree: Java spawns `python.exe` directly, which synchronously per model turn shells out to
`claude -p` (a `.cmd` npm shim → `cmd.exe` → `node.exe`) via `subprocess.run(...)`. If Java kills
`python.exe` mid-`subprocess.run()`, the still-running `claude.cmd`/`node.exe` chain is not torn down
automatically (no implicit process-tree teardown on Windows without a Job Object).

Kill mechanism: (1) `process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)` then
`process.destroyForcibly()`; (2) redundant `taskkill /T /F /PID <pid>` sweep; (3) bounded
`process.waitFor(5s)` to actually reap. Timeout via `process.waitFor(maxDurationMs, MILLISECONDS)`
(same bound already validated/clamped in `McpGateway.goalRun`). Mid-run MCP-level cancellation:
confirmed this doesn't exist today past the queue-wait phase either way — preserved that same scope,
added defensive `InterruptedException` handling for future wiring. JVM graceful shutdown: a static
shutdown hook tracks live subprocess handles and force-kills their trees on JVM exit. JVM hard
crash/kill: accepted gap, consistent with `InstanceRegistry`'s own documented stale-entry trade-off;
flagged as a follow-up (PID marker file + lazy sweep on next call), not attempted.

`--max-turns` sizing: `maxSteps` defaults to 256 in the old schema; clamped to `min(maxSteps, 60)`
since each turn is a real, cold-context `claude -p` call (~5s+, real token cost).

Script/interpreter resolution: the script lives in the source checkout, not the mod jar, and a live
NeoForge client's `user.dir` is the Minecraft instance folder, not the repo root — flagged as
requiring an explicit fail-fast-if-unset system property/env var rather than guessing (later
implemented as a heuristic search chain — see the applied-version deviations above).

### 6. Result mapping

The orchestrator's `main()` writes exactly one `print(json.dumps(summary, ...))` and also writes
`evidence_dir / f"summary-{run_id}.json"`. Rather than parse stdout, the launcher gives each run its
own fresh evidence directory and globs for `summary-*.json` after the process exits. Absence of that
file is the actual "something went wrong at the infrastructure level" signal, independent of exit
code.

| Python `summary.status` | `GoalStatus` | Rationale |
|---|---|---|
| `SUCCEEDED` | `SUCCEEDED` | direct |
| `GOAL_NOT_CONFIRMED` | `FAILED` | ran to completion, own inventory check didn't confirm |
| `BLOCKED` | `INDETERMINATE` | infra-level model-call failure, not a definitive world-state result |
| `ERROR`/other | `FAILED` | uncaught exception in the script's top-level handler |
| no summary file | `INDETERMINATE` | can't tell whether any world mutation happened |
| Java-side timeout | `TIMED_OUT` | new |
| Java-side interrupt | `CANCELLED` | new, defensive |

(Note: the applied version simplified this slightly — `BLOCKED`/`ERROR` both map to `FAILED` with the
finer distinction preserved in `state.orchestratorStatus` rather than a dedicated `INDETERMINATE`
case for `BLOCKED`; see the implementer's own report for the exact rationale.)

### 7. Follow-ups flagged, not attempted in the original diff

1. Token argv visibility — `--token` is the orchestrator's only way to receive the token today,
   visible to other local processes via `tasklist /v`/Process Explorer for the subprocess's
   lifetime; recommend adding `--token-env` support to the script so Java can stop passing it via
   argv.
2. `worldSeed` wiring — `ensure_fresh_world()` has no seed parameter at all today.
3. `--run-id` flag — would let Java pre-choose the run id and read a known summary path directly
   instead of globbing.
4. `completedSteps`/`completedSegments` — left at 0 rather than digging through `loopResult`'s
   per-stop-reason-inconsistent key shapes.
5. Python environment reproducibility — no `requirements.txt`/venv tracked for the orchestrator's
   deps.
6. Orphan-subprocess reaper for JVM hard-crash — designed conceptually, not implemented.
7. `minecraft_goal_tasks`/`minecraft_goal_benchmark` — left entirely on the old path, need their own
   separate design.

</details>

