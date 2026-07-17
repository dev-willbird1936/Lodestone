# Judgment: intelligence layers in other-repos/ (2026-07-17)

Phase 1 deliverable for the goal-engine competence push. Fifteen sibling repos under
`other-repos/` were surveyed for real Minecraft AI control/intelligence layers, in four parallel
research passes (batched to keep any one pass from having to read an entire large repo like
baritone or minecraft-numen cold). Each repo was rated 0.0-10.0 overall plus per-mechanism-category
where meaningful, flagged for control method (visible-input-level = portable vs entity-level/
fake-player/command-driven = concepts-only), and flagged for license. No source code, identifiers,
or comments were copied out of any repo into this report or into Lodestone; LGPL repos are
concepts-only by policy (see the PROVENANCE.md note added alongside this report), and even the MIT
repos are described in behavior/algorithm terms rather than quoted at length.

## Summary ratings

| Repo | Overall | License | Control method | One-line verdict |
|---|---|---|---|---|
| **minecraft-numen** | **9.0** | LGPL-3.0 (core) | Entity-level/fake-player | Best-in-survey: typed retry-vs-escalate failure taxonomy, generic recovery ladder, goal-heuristic-unit stall detection, reachability-validated target election, priority-bid reflex chains, budgeted A*+learned-heuristic pathing. Reflex layer currently ships disabled by default. |
| **baritone** | 8.5 | LGPL-3.0 | **Visible-input-level** | Best-in-class pathfinding/stall-detection/blacklist-and-retry and priority-arbitrated process control; the single most directly portable *control paradigm* in the survey (real simulated input, no commands/teleport). Narrow scope: movement/mining/building only, no survival decision-making. |
| **player-engine** (AltoClef/Baritone fork, "Automatone") | 8.5 | LGPL-3.0 | Mostly visible-input-level (dual-natured; a generalization layer is entity-level) | Mature always-on priority-scored reflex chains (lava/fire/drown/fall-MLG), position-history-window stuck detection, generic progress-reset blacklist tracker, unified priority scheduler. |
| mindcraft | 8.0 | **MIT** | Entity-level/fake-player (mineflayer) | Priority-ordered always-on reflex "modes" list; the most sophisticated planner in the survey (recursive AND/OR item-dependency graph with cost-based re-ranking); layered multi-timescale stuck detection. Concepts portable, code safe to reference. |
| ai-player | 6.5 | MIT | Entity-level/fake-player/command-driven | Real danger-distance detection + hybrid A*/embedding/Markov planner (partly stubbed); no reachability validation anywhere; three uncoordinated recovery loops instead of one arbiter. |
| minecraft-mcp-fundamentallabs | 5.5 | MIT | Entity-level/fake-player (+2 command-driven tools) | Competent mineflayer skill library (tool-capability pre-check, weighted flee vector, waypoint chunking) but no in-process reflex loop or planner at all — sequencing is 100% delegated to the external LLM caller. |
| voyager | 5.5 | MIT | Entity-level/fake-player/command-driven (mineflayer) | Landmark planning-layer contribution (automatic curriculum + semantically-retrieved skill library + independent state-grounded critic) but almost nothing for reflexes/stall-detection/reachability; damage events are captured and explicitly never used. |
| open-player | 5.0 | MIT | Entity-level/fake-player/command-driven | Detailed but opt-in, combat-only reflex tool; one orchestrator (mining) has a clean typed stop-reason enum, everything else is fixed-timeout only. |
| gemini-minecraft | 4.0 | MIT | Command-driven | No bot body at all (commands only) — but a genuinely portable plan/compile/validate/auto-repair pipeline pattern for turning an LLM's structured plan into verified world actions. |
| steve | 3.5 | MIT claimed (no LICENSE file present — treated cautiously) | Entity-level/fake-player | Real bespoke logic (procedural structures, multi-agent spatial-partition building) but safety is sidestepped via blanket entity invulnerability rather than engineered; no reachability checks anywhere. |
| craftagent | 3.0 | LGPL-3.0 | Entity-level/fake-player/command-driven | Teleport-based navigation (no journey exists to stall); flat LLM-cycle orchestration with real persistent memory/coordination scaffolding but essentially no physical-world safety/recovery mechanisms. |
| pendulum | 1.5 | LGPL-3.0 | **Visible-input-level** (for one specific mechanism only) | No intelligence layer of its own (JS-scripting + GUI bridge; Baritone integration is reflection-forwarding to an external dependency) — but its input-simulation shim is a clean, independently-useful visible-input actuation pattern. |
| fabric-claude-plugin | 1.0 | MIT | N/A | Dismissed: pure Claude-Code-skill + Python terrain-authoring toolkit driving an out-of-scope external mod's MCP server; no in-repo game-side control loop at all. |
| minecraft-mcp-yuniko | 1.0 | Apache-2.0 | Entity-level/fake-player/command-driven | Dismissed: thin mineflayer MCP tool wrapper, zero custom decision logic. |
| mcp-mcp | 0.5 | — (dismissed before license check; pure bridge) | Command-driven (mcpi protocol) | Dismissed: parameter-validated subprocess wrapper around the old Minecraft Pi Edition protocol, zero decision logic. |

## Detailed per-repo findings

### minecraft-numen (9.0/10 overall — LGPL-3.0 core, CONCEPTS-ONLY; companion API is MIT)

Architecture orientation: `docs/architecture-mind-model.md` documents a per-tick bidding system —
every autonomous mechanism is a "TaskChain" that bids a priority each tick, the highest bidder gets
exclusive control of the body, and the LLM itself is just the lowest-priority bidder (priority 0)
that any instinct can preempt and later return control to.

- **(a) Safety reflexes** — `core/task/reflex/CoreReflexes.java` registers four survival chains
  plus one pure policy: `MLGChain.java` (fall self-rescue, priority 10, highest of all),
  `BreathChain.java` (drowning prevention with a bounded BFS through connected water to find a
  breathable opening under a sealed ceiling, priority 6), `MobDefenseChain.java` (threat
  fight/flee, priority 5), `UnstuckChain.java` (geometry-stuck recovery, priority 2). Decision math
  is centralized in a pure, Minecraft-free class, `core/task/survival/SurvivalDecisions.java`.
  Caveat: `core/task/SurvivalConfig.java` shows this whole layer ships **disabled by default**
  today — fully built and unit-tested, not proven live in shipped default behavior.
- **(b) Stall/stuck detection** — two layers, both smarter than tick-counting.
  `core/task/survival/UnstuckDetector.java` is a ring buffer of (x, z, "was locomotion attempted")
  samples that only reports stuck if movement was attempted for most of a full window while
  position barely changed (deliberately doesn't fire on legitimate idling).
  `core/pathing/exec/PlayerNav.java` measures progress in the *goal's own heuristic units* rather
  than Euclidean distance (explicitly because raw distance misjudges vertical-only/non-Euclidean
  goals), tracks consecutive non-progressing replans separately from an outer absolute replan
  ceiling, and escalates to a typed `BOXED_IN` failure instead of grinding indefinitely.
- **(c) Target election/reachability** — `core/task/base/TargetSet.java` is a generic "candidates
  minus ones we've given up on" holder shared by ore-mining and mob-hunting selection.
  `core/task/MineCompanionTask.java` runs a single search across a composite goal covering every
  known candidate at once (so it only commits once a path is actually found), blacklists the
  nearest candidate on path failure and retries, and separately tracks a "reachable by distance but
  no interaction ray ever lines up" condition that also triggers blacklisting after a bounded tick
  count.
- **(d) Recovery architecture** — genuinely unified on two levels. `core/task/base/RecoveryLadder.java`
  is a generic, Minecraft-free ordered list of fallback strategies for one bounded goal, where each
  rung declares which failure categories it's willing to catch and a retry cap. Above that sits the
  whole-body priority-bid scheduler from the architecture doc.
- **(e) Failure taxonomy + success verification** — `core/task/FailureType.java` is a 14-value enum
  explicitly partitioned into "in-ladder" causes worth retrying with a different tactic for the
  *same* goal (occluded sightline, boxed in, no path, out of reach, hazard) versus "kick back to the
  LLM" causes needing a strategic decision (no material, wrong tool, target lost, mined out, entity
  blocking the cell). `core/task/base/Precondition.java` gates task start the same way. Success
  verification counts actual inventory deltas, not whether a dig call "returned ok."
- **(f) Planning** — a from-scratch, Minecraft-decoupled search engine.
  `core/pathing/engine/SearchBudget.java` implements a two-phase node-expansion budget (cheap
  "primary" + larger distance-scaled "failure" budget) enabling partial-path commitment.
  `core/pathing/engine/HLearningTable.java` is an RTAA*-style learned-heuristic cache (max-merge
  only, so it can only get more pessimistic, never wrongly optimistic) that stops the search from
  re-discovering the same expensive dead end on every replan segment.

Control method: **entity-level/fake-player** (a server-side fake `ServerPlayer`-derived entity;
directly sets entity fields rather than driving client input) — not directly portable, but every
mechanism above is a software-architecture idea fully separable from that actuation substrate.

Per-category: safety reflexes 8.5, stall detection 9.5, target election/reachability 9.0, recovery
architecture 9.0, failure taxonomy/verification 9.0, planning 9.0. Docked from a higher overall
score for the reflex layer shipping disabled-by-default, the LGPL reimplementation tax, and the
actuation-substrate mismatch.

### baritone (8.5/10 — LGPL-3.0, CONCEPTS-ONLY)

- **(a)** No standalone reflex process; safety is baked into per-tick cost/pause logic —
  `pathing/path/PathExecutor.java`'s pause-safety check refuses to stop somewhere
  unwalkable/unbreathable, movement cost is re-verified every tick
  (`pathing/movement/CalculationContext.java`, `MovementHelper.java`), and dangerous
  blocks/mob/spawner proximity are folded into path *cost* at plan time
  (`utils/pathing/Avoidance.java`) rather than intercepted as a live event.
- **(b)** Strong and explicit: `PathExecutor` tracks consecutive ticks measurably off the planned
  route (cancels past a threshold), tracks ticks-on-current-movement against its cost estimate plus
  a timeout margin, and re-validates every movement's cost every tick, cancelling the instant a
  movement becomes provably impossible — escalates to a typed cancellation, not a fixed grind.
- **(c)** `process/GetToBlockProcess.java` / `MineProcess.java` search all known instances of a
  target; on calculation failure, blacklist the closest instance plus adjacent neighbors and
  immediately retry the next-closest candidate.
- **(d)** `utils/PathingControlManager.java` re-sorts every active "process" (mine/follow/
  explore/build/custom-goal) by a numeric `priority()` every tick and hands control to the highest,
  with an explicit `DEFER` command to voluntarily yield — unified arbitration for *who's in
  control*, with bespoke recovery *within* each process.
- **(e)** Success verified structurally (goal-containment check against actual position). A small
  typed result enum exists at the calculation level (`api/utils/PathCalculationResult.java`:
  SUCCESS_TO_GOAL/SUCCESS_SEGMENT/FAILURE/CANCELLATION/EXCEPTION) but it's control-flow-typed, not a
  rich cause vocabulary.
- **(f)** Textbook, highly refined A* (`pathing/calc/AStarPathFinder.java`) over a per-movement cost
  model (traverse/ascend/descend/diagonal/parkour/pillar/fall), with previous-path cost bias and
  incremental lookahead.

Control method: **visible-input-level** — `utils/InputOverrideHandler.java` simulates held
movement/sprint/jump keys, `utils/BlockBreakHelper.java` simulates held-down breaking,
`behavior/LookBehavior.java` simulates mouse-look — driving a real local client the same way a
human would. The single most directly portable control paradigm in the whole survey.

### player-engine ("Automatone"/AltoClef fork) (8.5/10 — LGPL-3.0, CONCEPTS-ONLY)

Dual-natured: the bulk of the actual decision logic is a rebranded AltoClef running on a Baritone
fork; a newer, smaller layer generalizes control from the real client player to arbitrary
server-side entities.

- **(a)** Always-on, priority-scored "chains" (`chains/WorldSurvivalChain.java`: lava escape,
  self-extinguish, drowning avoidance, nether-portal-stuck handling; `chains/MLGBucketFallChain.java`:
  detects fall speed past a threshold with no water/climbing and reactively places+reclaims a water
  bucket). Both unconditionally active.
- **(b)** `chains/UnstuckChain.java` keeps a rolling position history (up to 500 samples), checks XZ
  movement delta across a 100-tick window, plus separate detectors for powder snow, end-portal-
  frame standing, and an eating-glitch counter — each escalates to a *specific typed recovery task*
  rather than grinding a fixed budget.
- **(c)** `trackers/blacklisting/AbstractObjectBlacklist.java` is a generic per-target failure-budget
  tracker: resets the failure count when real progress is shown (closer distance or a better tool),
  marks a target unreachable once failures exceed an allowed count.
- **(d)** `tasksystem/TaskRunner.java#tick()` iterates every registered chain, calls `getPriority()`
  on each, ticks only the single highest-priority chain each tick — reflexes and normal tasks
  compete on the same numeric scale rather than an if/else ladder.
- **(e)** Real typed enums at the pathing/movement layer only (`PathCalculationResult`,
  `MovementStatus` distinguishing UNREACHABLE from generic FAILED); above that,
  `tasksystem/Task.java#fail(String reason)` is a logged free-text string that doesn't propagate the
  rich vocabulary further up the tree.
- **(f)** A* (Baritone) plus a hierarchical task/subtask tree and a global mutable-settings stack
  that lets tasks temporarily override movement costs/avoidance and cleanly restore them.

Control method: primarily **visible-input-level** (`control/InputControls.java` forces simulated
key/mouse states, consumed by the normal client pipeline) — but the repo's own headline layer
(`baritone/api/entity/*`) re-implements server-side player-action-packet handling generically for
arbitrary mobs with no real client, which is entity-level puppeting. Which flag applies depends on
which half of the repo is reused.

### mindcraft (8.0/10 — MIT, safe to reference)

- **(a)** `src/agent/modes.js`: a priority-ordered `modes_list`
  (self_preservation → unstuck → cowardice → self_defense → hunting → item_collecting →
  torch_placing → elbow_room → idle_staring → cheat[off by default]), each with `on`/`active`/
  `paused`/`interrupts`. `ModeController.update()` runs every tick, walks the list in priority
  order, stops at the first active mode. `self_preservation` (always on, interrupts everything)
  handles fall-hazard water-standing auto-jump, imminent-fall-block moveAway, lava/fire
  (bucket→water-path→moveAway), and recent-damage-at-low-health moveAway.
- **(b)** Three independent layers: a 200ms position-delta door-opening interval, a dig-progress
  abort check, and a global `unstuck` mode tracking position-delta/dig-target over ~20s with its
  own nested 10s crash-timeout; plus `action_manager.js` detects actions firing <20ms apart and
  kills the agent process past 5 in a row ("infinite action loop"). None escalate to a typed
  failure object — recovery is "run a canned skill, then re-prompt the LLM in natural language."
- **(c)** `library/world.js` `isClearPath` gates idle-time reflexes with a real pre-commit
  pathfinder check (100ms budget). Goal-directed travel uses a graceful-degradation ladder
  (non-destructive path → destructive path → attempt anyway) and verifies success via actual final
  distance. `npc/item_goal.js` gates resource goals on the target type actually being observed
  nearby, escalating to an explicit explore fallback after two strikes.
- **(d)** The reflex tier is genuinely unified and priority-arbitrated; once a skill call throws,
  recovery is catch-and-stringify-to-the-LLM — two-tier design.
- **(e)** Weak/free-text (`{success, message, interrupted, timedout}`), but real success
  verification exists at specific sites (inventory-count deltas, distance re-measurement).
- **(f)** The most sophisticated planner in the survey: `npc/item_goal.js` builds a recursive AND/OR
  dependency graph per item (craft/collect/smelt/hunt, each requiring its own tools/table/furnace/
  fuel), with cycle detection, static infeasible-branch pruning, and cost-based method selection
  that re-ranks alternatives by accumulated failure count. `npc/build_goal.js` does diff-based
  construction against stored JSON templates. `coder.js` lets the LLM write/run sandboxed JS calling
  the skills library directly (code-as-action-space).

Control method: **entity-level/fake-player** (mineflayer + pathfinder/pvp/collectblock/auto-eat) —
concepts-only for direct porting, but MIT means the *description* here can be more liberal and the
code can be referenced with attribution if ever useful.

### Remaining repos (condensed — full per-mechanism detail preserved in the batch transcripts this report was synthesized from)

- **ai-player** (6.5, MIT, entity-level/fake-player/command-driven): real danger-distance detector
  + hybrid A*/embedding/Markov planner (RL cost term stubbed "reserved for future"); zero
  reachability validation anywhere (`BlockSearcher.java` is an empty stub); three uncoordinated
  recovery loops instead of one arbiter; genuinely good state-based `ToolVerifiers` success
  verification; capped-retry-then-terminal-failure pattern in `PathTracer.java` (`MAX_RETRIES=5`).
- **minecraft-mcp-fundamentallabs** (5.5, MIT, entity-level/fake-player): no reflex loop, no
  in-process planner — all sequencing delegated to the external LLM turn-by-turn. `mineBlock.ts`
  pre-filters candidates by accessibility (`blockHasNearbyAir`) and tool capability (`canBotMine`)
  before committing, with a persisted fail-counter escalating to an explore-fallback after 5
  consecutive "nothing found." `navigateToLocation.ts` chunks long-distance goals into intermediate
  waypoints.
- **voyager** (5.5, MIT, entity-level/fake-player/command-driven via mineflayer): no real-time
  reflex loop at all — operates in discrete generate-code/execute/observe/replan rounds; damage
  events are captured and explicitly marked unused (`# FIXME: damage_messages is not used`).
  Standout planning layer: `curriculum.py` proposes next goals from progress/inventory/failed-task
  history; `skill.py` persists successful code as named skills in a vector DB, retrieved by semantic
  similarity for compositional reuse; `critic.py` is an independent LLM call judging success against
  actual rendered world state rather than trusting the actor's self-report.
- **open-player** (5.0, MIT, entity-level/fake-player/command-driven): `defense_mode.js` is a
  detailed reactive combat controller (strategy selection, projectile-dodge, shield/totem
  management) but opt-in and combat-only; `mining_orchestrator.js` has a clean typed `stopReason`
  enum (`count_reached`/`no_more_blocks`/`timeout`/`too_many_unreachable`/`unknown_block`/`no_tool`/
  `tool_broke`) scoped to that one tool only; everything else is fixed-timeout with no
  progress signal.
- **gemini-minecraft** (4.0, MIT, command-driven, no bot body): `VoxelBuildPlanner.java` decouples
  "LLM emits a structured plan" from "deterministic compiler validates, auto-repairs (support-column
  fixes, whole-plan shifts), and only then executes" — a portable plan/compile/validate/repair
  pattern independent of its (non-portable) command-based execution.
- **steve** (3.5, MIT claimed but no LICENSE file present — treated cautiously, concepts described
  only): safety sidestepped via blanket entity invulnerability rather than engineered; no
  reachability validation anywhere; a well-documented formal state machine
  (`AgentStateMachine.java`) appears only loosely wired to the actual tick loop; genuinely good
  procedural structure-generation and lock-free multi-agent spatial-partition build coordination.
- **craftagent** (3.0, LGPL-3.0, CONCEPTS-ONLY): teleport-based navigation means there is no journey
  that can stall; flat periodic-LLM-cycle orchestration with real persistent
  memory/mail/coordination scaffolding, but essentially none of the a-e mechanisms this survey
  looked for.
- **pendulum** (1.5, LGPL-3.0, CONCEPTS-ONLY): no intelligence layer of its own (JS-scripting + GUI
  bridge; its Baritone integration is reflection-based command forwarding to an external,
  unvendored dependency) — but `util/PendulumInput.java` + `script/PlayerSimulator.java` implement a
  clean, real client-side input-abstraction layer that the running client reads exactly like human
  input, the only other concretely visible-input-level actuation example in the whole survey
  (alongside baritone/player-engine).
- **fabric-claude-plugin** (1.0, MIT), **minecraft-mcp-yuniko** (1.0, Apache-2.0), **mcp-mcp**
  (0.5, unreviewed/dismissed) — dismissed as pure bridges with no decision logic of their own.

## Ranked port list (mechanism x source x portability x expected competence gain x effort)

Ordered by expected value for Lodestone specifically, not by the source repo's own overall rating.

1. **Net-progress (dig-aware, trying-to-move-weighted) stall detection escalating to a typed STUCK
   failure, instead of the current per-stage fixed tick budgets.**
   Source: minecraft-numen `PlayerNav` (progress in goal-heuristic units, consecutive-non-progress
   counter separate from an absolute ceiling, typed `BOXED_IN` escalation) + baritone
   `PathExecutor` (multi-signal: ticks-off-route, per-movement cost-timeout, every-tick
   re-validation) + player-engine `UnstuckChain` (rolling position-history window).
   Portability: **high** — pure decision logic over position/attempt signals, independent of
   control substrate. Expected competence gain: **high** — this is the task's own explicitly named
   Phase 2 target and the most-repeated idea across every strong repo in the survey; Lodestone
   currently has no unified version of it (only scattered `stageTicks > N` throws). Effort:
   **medium** (a new pure decision-core class analogous to the already-committed
   `NeoForgeSuffocationReflex`, feeding the existing `stall:*` failure-cause vocabulary).
   **Priority: build this first, but only after the B2 baseline (below) confirms stalls are
   actually a leading failure cause here and not a distraction from a different dominant cause.**

2. **Candidate-target election validated by bounded reachability probing, blacklist-and-re-elect on
   later failure.** Source: minecraft-numen `TargetSet`/`MineCompanionTask`, baritone
   `GetToBlockProcess`/`MineProcess`, player-engine `AbstractObjectBlacklist` (progress-reset budget)
   — three independent repos converge on the same shape. **Already implemented** in
   `NeoForgeSurvivalTreeGoal`'s `electTrees`/`blacklistAndReElect` (committed this session, written
   before this survey completed). No further porting needed here; noted because it's a strong
   independent validation that the existing approach matches the best external precedent, not a
   coincidence to be suspicious of.

3. **A lightweight reflex-conflict/livelock guard**, not a full priority-bid scheduler refactor yet.
   Source (for the eventual full pattern, if the reflex count grows enough to need it): mindcraft
   `modes.js`, player-engine `TaskRunner`, minecraft-numen's `CoreReflexes` chains, baritone
   `PathingControlManager` — four independent repos converge on numeric-priority-bid arbitration.
   Portability: high (pure scheduling logic). Expected competence gain: **currently low-to-medium**
   — Lodestone has only three reflexes today (fire/lava escape, suffocation escape, water retreat)
   in a hand-ordered if/else chain that already approximates a sane priority order, so a full
   generic bid-scheduler would be premature abstraction for three cases. What *is* worth adding
   regardless: a simple "N reflex activations within M seconds" counter that escalates to a typed
   failure instead of letting reflexes silently oscillate and burn the duration budget (this was
   flagged as a real risk, not just a style preference, once the stall detector and reflex layer
   coexist). Effort: low for the livelock counter; defer the full arbitration refactor until a
   fourth or fifth reflex actually makes the if/else chain unwieldy.
   **Priority: add the livelock counter alongside item 1; defer the full scheduler.**

4. **Recovery-ladder unification with an explicit retry-vs-escalate partition on the failure
   taxonomy.** Source: minecraft-numen `FailureType` (14-value enum explicitly split into
   "retry with a different tactic for the same goal" vs "needs a strategic decision") +
   `RecoveryLadder` (generic ordered fallback rungs, each declaring which causes it catches and a
   retry cap). Lodestone's `GoalFailureKind`/`GoalRecoveryCoordinator`/`failureCause` vocabulary
   (committed this session) is already a reasonable machine-readable taxonomy in the same spirit.
   Portability: high. Expected competence gain: medium (mostly a clarity/maintainability win once
   there are enough recovery special-cases to actually need unifying). **Explicitly deferred per
   the task's own hard sequencing: do not build this — and do not add another bespoke recovery
   special-case block — until B1+B2 pass.**

5. **Plan/compile/validate/auto-repair pipeline for adaptive/deliberate declarative-plan
   synthesis.** Source: gemini-minecraft `VoxelBuildPlanner` (LLM proposes a structured plan;
   a deterministic compiler validates and auto-repairs it before any world action, backed by a
   bounded retry loop feeding error text back to the LLM). Portability: high (the pattern, not the
   command-based execution). Expected competence gain: **low for the current B1/B2/B3 gates**
   (Lodestone's adaptive/deliberate plan synthesis already bounds and validates plans before
   execution per `docs/minecraft-goals.md`); **worth revisiting** if B3's stone-toolset task or any
   future adaptive-synthesis task starts producing plans that fail validation often enough to need
   an auto-repair pass rather than a flat reject. Effort: medium-high. **Priority: not now — track
   as a candidate if adaptive/deliberate plan-synthesis rejection rate becomes a visible problem.**

6. **Budgeted two-phase A* + learned-heuristic (RTAA*-style) cache for repeated replanning.**
   Source: minecraft-numen `SearchBudget`/`HLearningTable`, baritone's incremental-lookahead A*.
   Portability: high. Expected competence gain: **unknown until the baseline shows whether repeated
   re-planning against the same terrain is actually a measurable cost** in the B1/B2 evidence;
   `NeoForgeSafePathPlanner` already has bounded-visit-budget search (including the new reduced-
   budget `probe` mode). Effort: high. **Priority: lowest — only pursue if baseline telemetry shows
   navigation replanning cost, not stall-detection or election, is the dominant remaining failure
   mode after items 1-3 land.**

Not ported, kept as confirmations that Lodestone's existing choices are already sound: state-based
success verification (ai-player `ToolVerifiers`, voyager `critic.py` — Lodestone's terminal readback
assertions and goal-success-stricter-than-invocation-success already do this); visible-input-level
actuation as the right control substrate for a no-commands/no-teleport survival scope (baritone,
player-engine's majority mode, pendulum's `PendulumInput` all independently converge on the same
actuation pattern Lodestone already uses).

## Note on how "expected competence gain" was ranked

Per the accompanying advisor consult for this task, ranking item 1 (stall detection) as the
top port candidate is provisional on the B2 baseline run (tracked as its own task) actually showing
stalls as a leading failure cause in the current codebase, rather than an assumption made before
looking at real evidence. If the baseline instead shows deaths, tree-election failures, or something
else dominating, this ranked list's *priority order* should be revisited before implementation
effort is spent — the mechanism catalog above stays valid regardless, but which one gets built
first should follow the evidence.
