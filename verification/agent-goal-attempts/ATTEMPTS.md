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
