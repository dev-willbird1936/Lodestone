# goal_orchestrator_draft — DRAFT SKETCH, not integrated

This package is a **draft sketch**, not a working replacement for
`verification/goal-orchestrator-milestone1.py`. Nothing here is imported by that file, and that
file has not been modified. See `verification/goal-orchestrator-design-notes.md` for the full
design report (script mode, safety tiers, and the rationale for this module split) — this package
is that report's section 3 made concrete enough to hand to an implementer.

## What's real vs. illustrative here

- `decision_protocol.py`, `safety.py`, `loop.py`, `modes/*`, `hooks/*`, `backends/*` contain real,
  syntactically valid, self-contained Python — no import of the live orchestrator file, so nothing
  here can accidentally break it just by existing.
- `backends/cli_backend.py` and `backends/api_backend.py` are **adapters only**: they show the
  shape a real integration would use, but take the actual subprocess/API-calling functions as
  injected callables/clients rather than reimplementing `call_claude_cli()` or the Messages API
  call — those already work in the real file and should be moved, not rewritten.
- `mcp_client.py`, `catalog.py`, and the bulk of `trace.py` are deliberately **not duplicated
  here** — per the design notes' module table, those would be moved verbatim from
  `goal-orchestrator-milestone1.py` (lines 199-306, 309-359, 489-568) at integration time.
  Duplicating ~250 lines of already-working, already-verified transport/catalog code into a draft
  sketch would only create a second copy that could silently drift from the real one.

## Directory map (mirrors the table in goal-orchestrator-design-notes.md section 3.2)

```
decision_protocol.py    unified {"actions": [...]} / legacy {"tool": ...} decision parsing
loop.py                 generalized turn loop: backend.decide() -> mode.validate() -> sequential
                         fail-fast batch execution -> hook points -> trace
safety.py               SafetyTier, prompt addenda, safe-waypoint safety-param flooring (both the
                         ordered `safety` floor and the high-tier-only `combatPolicy` default),
                         forced-observation / reactive-recheck hooks
modes/
  base.py               Mode protocol
  realtime.py           RealtimeMode - same contract as today's proven CLI_SYSTEM_PROMPT_TEMPLATE
  script.py             ScriptMode - batch-boundary prompt verbatim from design notes section 1.3,
                         including its two worked boundary examples (tree+axe, gravel-until-flint)
backends/
  base.py               ModelBackend protocol
  cli_backend.py         adapter over the real call_claude_cli()-style subprocess call
  api_backend.py         adapter over the real anthropic Messages API call
hooks/
  base.py               Hook protocol, HookSignal, TurnContext
  quarantine_recovery.py always-on CAPABILITY_QUARANTINED auto-reconcile, extracted from the real
                         run_loop_cli()'s inline handling (lines 785-812) into a Hook
  inventory_watch.py    polling-based "material X just appeared in inventory" hook (there is no
                         real inventory-change event on any adapter - see the hook's own docstring
                         and ../goal-orchestrator-hooks-draft.py's InventoryDeltaHook, the related
                         standalone-lane draft this hook's diffing logic is adapted from)
tests/                  unit tests using fakes.py's FakeMcpClient/FakeBackend/FakeTrace - no real
                         network/subprocess/Minecraft client access anywhere in this package
```

## Why the package is named `_draft`

Existing `verification/*.py` files are standalone hyphenated entry-point scripts
(`goal-orchestrator-milestone1.py`), which is fine for a script but not for something meant to be
`import`ed (Python identifiers can't contain hyphens). A real adoption would most likely drop the
`_draft` suffix and become the actual importable package once these pieces are wired to the real
`mcp_client`/`catalog`/`trace` code and live-verified — see design notes section 3.5.
