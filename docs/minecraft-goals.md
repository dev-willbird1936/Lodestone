# Minecraft goals

This surface is currently enabled only by the NeoForge 1.21.1 host. Other loaders continue to
expose the individual capability gateway without these goal tools.

NeoForge 1.21.1 exposes these goal tools through the MCP gateway:

- `minecraft_goal`: model-agnostic fallback runner for one goal in `script` or `realtime` mode.
- `minecraft_goal_tasks`: list built-in tasks, required capabilities, execution modes, policy
  profiles, prerequisite contracts, and success contracts.
- `minecraft_goal_benchmark`: run matched script/realtime cases and compare correctness before elapsed time.
- `minecraft_subactions_execute`: execute one bounded, fail-fast script segment through the same
  capability runtime as individual MCP actions.
- `minecraft_hook_create`, `minecraft_hook_poll`, and `minecraft_hook_remove`: session-owned
  inventory condition hooks. They poll fresh inventory state, so this feature does not need a
  NeoForge JAR event change.

The public policy is now only `low`, `medium`, or `high` for both `intelligence` and `safety`.
Both default to `medium`. Old `raw-v1`, `guarded-v1`, `adaptive-v1`, `deliberate-v1`, and
`balanced` inputs remain accepted as compatibility aliases inside the Java implementation, but
they are not advertised in MCP schemas.

The preferred entry point is the shared `$lodestone-goal` agent skill. It asks the current agent
host to start one native goal worker with the lowest-latency tool-capable model that host exposes.
The skill does not name a provider or model. The parent agent monitors the worker and verifies the
terminal predicate with fresh MCP reads. If native subagents are unavailable, the current agent
uses the same workflow directly.

In `script` mode, the worker observes the required state and submits deterministic subactions only
through the next uncertainty boundary. Random drops, unreachable routes, changed inventory, combat,
and UI transitions force a fresh observation and a new segment. In `realtime` mode, the worker
observes and executes one logical subaction at a time. High safety observes threats and player
state before every action or batch; medium checks at material boundaries; low retains baseline
health and failure checks.

## Legacy native engine internals

The enum names below describe retained Java implementation profiles and benchmark history. They
map to the new public low/medium/high contract and are not separate MCP choices.

### `deliberate-v1`: the top intelligence tier

`deliberate-v1` (aliases: `deliberate`, `deliberate-v1`, `perfect`, `xhigh`) sits above `adaptive-v1`
and inherits every one of its guardrails, prerequisite planning, obstruction mining, and
action-segment replanning behavior unchanged. It adds two things on top:

- **Realtime lookahead-plan consultation.** At every realtime segment boundary (not per step),
  `deliberate-v1` also asks the selected model provider for a bounded declarative plan even when
  the native/declared task is already supported, and stashes a short summary of that plan (segment
  and step ids plus each segment's description, never the full verbose plan object) into the
  decision state the model sees for its next per-step choice. This gives per-step decisions a
  short-lived strategy instead of being purely greedy. A provider that cannot or does not
  synthesize a plan simply leaves no lookahead behind; the goal still runs normally.
- **Situational deliberation budget.** A perfect player thinks longer when it is safe to stand
  still and reacts fast when it is not. When `deliberate-v1` is running and the current decision is
  not hazardous (no fire/lava/water contact, no forward drop risk, no mob actively targeting the
  player, no active fall, health not critically low), the realtime executor's HTTP provider may
  request the `xhigh` reasoning effort and a wider timeout for that one decision instead of its
  fast configured default. The moment a hazard is observed, or for any other intelligence tier, the
  fast configured effort/timeout is used so a threatened player is never slowed down. The base
  `reasoningEffort` recorded once per run stays the provider's configured default; a separate
  `lastDecisionReasoningEffort` value records what was actually requested for the most recent
  realtime decision.

**Backward compatibility note:** `highest` remains a legacy alias frozen at `adaptive-v1` for
existing MCP callers — it does **not** resolve to `deliberate-v1`. Use `deliberate`,
`deliberate-v1`, `perfect`, or `xhigh` explicitly to opt into the new top tier.

Both modes use the same bounded plan format and verification kernel. A plan contains segments, and each segment contains `observe`, `invoke`, or `assert` steps. Outputs are stored under `steps.<step-id>` and can be passed to later steps with `${steps.<step-id>.<field>}`; automatic post-action observations are available under `steps.postObserve.<step-id>`. Custom invoke steps may also declare assertion-shaped `preconditions`; realtime excludes steps whose inputs or preconditions are not satisfied, while script mode fails closed before invoking an invalid step. The selected low-latency model receives those preconditions with each candidate. Arbitrary shell, JavaScript, or Python is never executed by a plan.

When adaptive planning synthesizes a previously unknown goal, the model may emit only this
declarative DSL. The engine bounds it to 16 segments and 256 total steps, requires explicit
terminal assertions and `metadata.completionPredicateReady=true`, rejects unknown capability
namespaces, and rejects direct commands, text injection, raw input, or world mutation for
survival-scoped plans before execution. Typed goal movement/interact calls carry the requested
intelligence and safety policy into the NeoForge adapter; calls made outside `minecraft_goal` remain
raw. If synthesis is unavailable or invalid, the goal fails as `UNSUPPORTED` rather than falling
back to blind inputs.

If `maxDurationMs` is omitted, short bounded tasks use 120 seconds. The native wool-tree,
wooden-axe tree, collect-wood, and Nether workflows use 480 seconds so normal movement, hand
mining, visible crafting, and terminal readback are not cut off mid-prerequisite. An explicit
caller budget still wins and remains capped at 600 seconds.

Script mode runs segments in declared order and, for guarded or adaptive intelligence, reads fresh UI, server, or player state after each action so the next segment receives a real state handoff. Guarded realtime follows that declared order while still observing after each action. Adaptive realtime asks the selected provider for one eligible candidate step, invokes it, then reads fresh state before selecting again. It always attempts `minecraft.input.release-all` during realtime cleanup. Both modes stop before the next action when their step or elapsed-duration budget is exhausted; a capability that returns after the elapsed budget makes the run `TIMED_OUT`.

Adaptive realtime reports retain `model-decision` trace entries with candidate index and rationale;
guarded realtime reports `deterministic-selection`. Native goal actors remain deterministic
low-level executors beneath those high-level decisions, with their terminal output still subject to
goal assertions.

The NeoForge `minecraft.player.state.read` post-action observation also includes the bounded local
goal observation, so realtime decisions see fresh inventory/tool, threat, target-block, fluid, and
fall state after ordinary actions—not only the final actor result.

Intelligence is applied at the `minecraft_goal` layer. Direct `minecraft.input.*` or
`minecraft.player.interact` calls remain raw low-level controls; they are not silently upgraded into
tool acquisition. For guarded and adaptive survival workflows, the native prerequisite chain owns
the lower steps: gather starter wood by hand, craft the required tools through visible UI, equip the
right tool, and only then mine tool-required terrain. The intelligent guard vetoes a stray bare-hand
stone/dirt attack and records the recovery diagnostic instead of allowing an accidental tunnel.

## Native continuation checkpoints

Highest-intelligence native workflows are resumable at safe phase boundaries. The wooden-axe tree
workflow exposes `resource-gather -> craft-axe -> complete`; the Nether workflow exposes
`starter-tools -> portal-tools -> complete`. Each phase is a separate MCP invocation carrying an
opaque `continuationToken` returned by the previous phase. The NeoForge actor retains its client-
thread state while paused, releases every held input, and rejects stale or unknown tokens.

Adaptive realtime can select only phases whose token dependencies are already resolved, so a model
cannot jump into crafting or portal construction before the required observations and tools exist.
Adaptive script uses the same phase boundaries in declared order, passing each phase result to the
next script. A phase boundary is not a per-tick model call: the native actor continues to use
ordinary look, movement, mining, visible container clicks, and loaded-chunk observations between
boundaries.

Native intelligent phase results also include a bounded `worldObservation` object: dimension and
game mode, player position/health/food/fall and fluid state, held item, bounded inventory counts,
nearby hostile/threat entities, the currently targeted block, and a small local collision grid with
safe neighbors and forward-drop risk. Native actors may read the full loaded chunk directly for
path planning; the local projection gives realtime model decisions enough geometry to choose
movement, retreat, or a permitted obstruction action. The full execution trace stays local; model
prompts receive a bounded tail projection so low-latency decisions do not degrade as an action
trace grows.

Guarded and adaptive navigation treat an unavailable loaded-chunk safe path as a recovery failure,
not permission to walk blindly toward the target. Guarded intelligence routes around visible
obstructions; adaptive intelligence may mine a visible obstruction only when the held tool and
mutation control allow it. Intelligent movement also blocks an observed multi-block forward drop
before movement input, while low/raw profiles retain their comparison fallback behavior.

Command execution is denied by default through `allowCommands=false`. Survival Nether and tree
goals do not use commands; the creative wool-tree fixture declares only its bounded setup commands
inside its task plan.

Goal success is stricter than MCP invocation success. A native `ok` result only means that one capability completed. The goal reports `SUCCEEDED` only after its assertions pass and the plan explicitly declares `metadata.completionPredicateReady=true`. Other terminal states are `FAILED`, `UNSUPPORTED`, `CANCELLED`, `TIMED_OUT`, and `INDETERMINATE`.

## Built-in coverage

The task catalog includes survival gathering and crafting, creative placement/removal, navigation, combat, commands, bounded observations, and stale-state failure checks. Each task declares its required capabilities and fixture assumptions. On NeoForge 1.21.1, guarded and adaptive `survival.collect-wood` are routed through the prerequisite workflow instead of a blind generic attack: visible walking/look input gathers starter logs, visible inventory/table clicks craft planks, sticks, and a wooden axe, and only then does the actor clear the target tree. `survival.wooden-axe-mine-tree` uses the same bounded physical-client input workflow with terminal readback of every initially observed target-tree log. `combat.attack-nearest` observes a loaded hostile, selects an available hotbar weapon at guarded or adaptive intelligence, path-plans with loaded chunks, attacks through the normal input mapping, and requires entity death readback while the player remains alive. Commands, teleportation, direct inventory edits, and direct block mutation are forbidden by the survival capability contracts.

## Fallback runner model selection

The preferred skill uses the current agent host's own model inventory and native subagent launch.
It selects the lowest-latency tool-capable model available at that time. It does not pin Sonnet,
GPT, or another provider.

The MCP-owned Python fallback is also environment-driven:

1. detect installed Codex and Claude CLIs;
2. compare optional `LODESTONE_CODEX_P95_MS` and `LODESTONE_CLAUDE_P95_MS` measurements;
3. use the lowest measured backend, or the current Codex host when no measurements exist;
4. omit a model override unless `LODESTONE_CODEX_FAST_MODEL` or
   `LODESTONE_CLAUDE_FAST_MODEL` is explicitly configured.

An explicit `--model` still wins. The raw Anthropic API fallback requires `--model` or
`LODESTONE_ANTHROPIC_FAST_MODEL`. Credentials are never included in reports.

Run `Open-Lodestone-Goal-Settings.bat` to open the local browser settings page for mode, policy,
backend selection, and latency hints. Its Save button stores the defaults in that browser. Explicit
skill or MCP arguments still take priority.

## KeepFocus profile

KeepFocus is a separate client-only NeoForge 1.21.1 mod. The Lodestone host stages the artifact only for `runKeepFocusClient`; it is not shaded into the Lodestone mod and is not a server dependency.

```text
cd ..\KeepFocus
gradlew.bat build --no-daemon
cd ..\Lodestone
gradlew.bat :hosts:neoforge:mc1_21_1:stageKeepFocusClient --no-daemon
```

Set `LODESTONE_TOKEN` and run `verification\run-neoforge-keepfocus-client.bat` to launch the focused client. Override the artifact with `KEEPFOCUS_ARTIFACT` or `-DkeepFocusArtifact=...` when using a different build location.

## Verification

Run `Run-Lodestone-Goal-Checks.bat` for goal-engine/gateway tests and the NeoForge 1.21.1 build. Use `minecraft_goal_benchmark` only against an isolated world; `dryRun=true` is safe only for capabilities whose own contract documents dry-run support. Benchmark output records both status and elapsed time so a faster false success cannot win.

To record a live NeoForge 1.21.1 KeepFocus goal run, launch the focused client and run
`powershell -ExecutionPolicy Bypass -File verification\run-neoforge-goal-benchmark.ps1 -ExistingClient`.
The script writes a timestamped JSON report under `verification/evidence/` and records script,
realtime, and benchmark results without storing the MCP token.
