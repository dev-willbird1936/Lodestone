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
