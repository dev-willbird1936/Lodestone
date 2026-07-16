# Minecraft goals

This surface is currently enabled only by the NeoForge 1.21.1 host. Other loaders continue to
expose the individual capability gateway without these goal tools.

NeoForge 1.21.1 exposes three goal tools through the MCP gateway:

- `minecraft_goal`: run one goal in `script` or `realtime` mode.
- `minecraft_goal_tasks`: list built-in tasks, required capabilities, and success contracts.
- `minecraft_goal_benchmark`: run matched script/realtime cases and compare correctness before elapsed time.

`minecraft_goal` defaults to `guarded-v1` with `balanced` safety. Select `raw-v1` explicitly for
legacy action order, or `adaptive-v1` for highest prerequisite/replanning behavior. Adaptive
realtime requires an available model provider; adaptive script uses deterministic native planning
and segment checkpoints.

Both modes use the same bounded plan format and verification kernel. A plan contains segments, and each segment contains `observe`, `invoke`, or `assert` steps. Outputs are stored under `steps.<step-id>` and can be passed to later steps with `${steps.<step-id>.<field>}`. Arbitrary shell, JavaScript, or Python is never executed by a plan.

Script mode runs segments in declared order. Realtime mode asks the selected provider for one candidate step, invokes it, then reads fresh UI, server, or player state after every action. It always attempts `minecraft.input.release-all` during realtime cleanup. Both modes stop before the next action when their step or elapsed-duration budget is exhausted; a capability that returns after the elapsed budget makes the run `TIMED_OUT`.

Realtime reports retain `model-decision` trace entries with candidate index and rationale; native
goal actors remain deterministic low-level executors beneath those high-level decisions.

Command execution is denied by default through `allowCommands=false`. Survival Nether and tree
goals do not use commands; the creative wool-tree fixture declares only its bounded setup commands
inside its task plan.

Goal success is stricter than MCP invocation success. A native `ok` result only means that one capability completed. The goal reports `SUCCEEDED` only after its assertions pass and the plan explicitly declares `metadata.completionPredicateReady=true`. Other terminal states are `FAILED`, `UNSUPPORTED`, `CANCELLED`, `TIMED_OUT`, and `INDETERMINATE`.

## Built-in coverage

The task catalog includes survival gathering and crafting, creative placement/removal, navigation, combat, commands, bounded observations, and stale-state failure checks. Each task declares its required capabilities and fixture assumptions. On NeoForge 1.21.1, `survival.wooden-axe-mine-tree` uses a bounded physical-client input workflow: visible walking/look/attack input, dropped-item pickup, visible inventory and crafting-table clicks, wooden-axe equip, and terminal readback of every initially observed target-tree log. Commands, teleportation, direct inventory edits, and direct block mutation are forbidden by the capability contract.

## Realtime model selection

Realtime provider selection is environment-driven:

1. load `GoalModelProvider` implementations through Java `ServiceLoader`;
2. add the optional OpenAI-compatible provider when `LODESTONE_GOAL_MODEL_URL` is configured;
3. choose the lowest configured measured p95 latency, preferring the pinned GPT-5.4 mini on ties;
4. use deterministic plan order if no provider is available.

Optional environment variables are `LODESTONE_GOAL_MODEL_ID`, `LODESTONE_GOAL_MODEL_API_KEY`, `LODESTONE_GOAL_MODEL_P95_MS`, `LODESTONE_GOAL_MODEL_TIMEOUT_MS`, and `LODESTONE_GOAL_MODEL_REASONING_EFFORT` (`low` by default). Credentials are never included in reports. The provider should return JSON with `candidateIndex` and `rationale`.

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
