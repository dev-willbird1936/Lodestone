# Agent-driven compound survival goal — attempt log

Standing goal: a low-latency model worker (haiku), running the `lodestone-goal` skill workflow
over the live Lodestone MCP bridge, must complete end-to-end with no goal-level hard scripts:

1. Open a world (create a fresh Survival world through the UI)
2. Mine a tree (logs in inventory)
3. Make an axe (wooden axe crafted)
4. Create a house out of dirt (walls + roof, verified by block reads)
5. Mine the house with the axe (house demolished while holding the axe)
6. Kill a mob (entity confirmed gone)

Harness: `verification/agent-goal-attempt-start.ps1` / `-stop.ps1` (isolated port 37891,
isolated run dir, KeepFocus forced, OBS window-capture recording per attempt).
Worker transport: `verification/agent-goal-rpc.ps1` (raw MCP JSON-RPC over loopback).
Forbidden to the worker: every `minecraft.goal.*` capability except
`minecraft.goal.navigation.safe-waypoint`, all `minecraft_goal*` tools, all commands.

| # | Date (UTC) | Worker model | Outcome | Video | Diagnosis / fix |
|---|-----------|--------------|---------|-------|-----------------|
| 1 | 2026-07-23 16:14-16:45Z | haiku (general-purpose subagent) | FAIL at stage 3. World created + tree mined (3 oak_log, 5 dirt verified by parent read). Axe blocked: worker could not open the inventory screen, concluded crafting impossible, stopped. House/demolition/mob never reached. Player died to a night hostile after the worker finished (idle at night). | agent-goal-attempt-a1.mp4 (50s tail only) | Live-reproduced: `minecraft.input.key.set key.inventory` returns ok but never opens the screen (state set, no press-click registration); `minecraft.ui.key {key:69}` works but is undiscoverable tribal knowledge. Fix dispatched: new `open_inventory`/`close_screen` hard-script agent tools. Recording separately broken: OBS unclean-shutdown Safe Mode modal blocked startup 42 min (`--disable-shutdown-check` does not cover it) -> start script now clears the sentinel, starts OBS before the game, and gates on recording-file growth; game window now resized to 1920x1080 (was captured at ~854x480 in a corner). |
| 2 | 2026-07-23 ~17:56-18:30Z | (run by a concurrent session; outcome not logged by its driver) | UNKNOWN. Run dir + video exist; no report was recorded. | agent-goal-attempt-a2.mp4 | - |
| 3 | 2026-07-23 18:40-21:45Z | haiku (general-purpose subagent, realtime one-call-per-step via agent-goal-rpc.ps1) | FAIL at stage 3 after 4 player deaths. PASS: fresh world created through UI; 10 logs mined across runs; planks + crafting table crafted via revision-guarded container clicks; table placed in world. FAIL: axe never completed - deaths and item despawns kept resetting materials; house/demolition/mob never reached. Tags a3 (aborted early: OBS wrote to the a2 filename; separately the client was closed externally mid-boot-window) and a4 (main run, unrecorded - operator removed OBS from the loop mid-session). | none (recording skipped) | Root causes found and fixed live: (1) OBS resolves --profile/--collection by display name, all copies said "Untitled" -> start script now rewrites both names (09fea7d). (2) Vanilla continueAttack requires a grabbed mouse, so an unfocused window turns held mining into no-op taps -> adapter now forces the grab flag during block-targeted hard scripts (5d05fe6). (3) One-shot RPC sessions exhausted the gateway session table ("MCP session ID is required after initialize" storms) -> rpc helper now closes its session per call (fde806b). (4) place_target_block x,y,z is the DESTINATION cell, not the support, and TARGET_CHANGED can false-fail when placing into a replaceable plant - semantics doc + false-fail fix queued. (5) Structural: model-per-primitive realtime control cannot outpace the game loop - drops despawn and mobs kill during deliberation. Verdict: promote player-reflex behavior into first-class bounded competency capabilities (goto / collect_drops / chop_tree / craft / attack_entity / survive_night / respawn_and_recover) + player-alert events; implementation dispatched. |
