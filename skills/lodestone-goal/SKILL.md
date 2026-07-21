---
name: lodestone-goal
description: Achieve Minecraft goals through a live Lodestone MCP bridge. Use for in-game objectives, survival, crafting, navigation, UI control, script/realtime execution, condition hooks, or coordinated Minecraft subgoals.
---

# Lodestone Goal

Own the goal until fresh world-state evidence confirms success or a real blocker exists.

## Inputs

Use `realtime`, `medium` intelligence, and `medium` safety when the user omits them. Accept only
`low`, `medium`, or `high` for both policy axes. Keep commands denied unless the user permits them.

## Delegate

Use the current agent host's native subagent facility when it exists. Start one goal worker with
the lowest-latency tool-capable model the host currently exposes. Prefer a latency-optimized small,
mini, flash, or haiku-like model. Do not pin a vendor or model name. Match reasoning effort to the
intelligence tier.

Give the worker the goal, mode, policies, controls, limits, live Lodestone MCP context, and this
workflow. The parent monitors recovery and verifies completion. If native subagents are not
available, run this workflow in the current agent. Never let workers control one player concurrently.

## Discover

Call `lodestone_capabilities_list`. Read the player, inventory, nearby blocks and entities, and UI
state needed for the next decision. Treat errors, stale revisions, unreachable paths, missing drops,
and changed inventory as evidence that requires replanning.

## Execute

In `script` mode, plan a bounded batch only through the next uncertainty boundary. Use
`minecraft_subactions_execute` when available. Stop before random drops, uncertain reachability,
combat, changed inventory, or UI transitions. Fail fast and re-observe after any failed or
indeterminate action.

In `realtime` mode, observe, execute one logical subaction, inspect its result and relevant fresh
state, then select the next subaction.

Intelligence controls planning depth:

- `low`: shortest viable plan and required observations.
- `medium`: prerequisites, tools, reachability, and likely recovery.
- `high`: alternate paths, explicit uncertainty boundaries, and extra terminal verification.

Safety controls observation and intervention:

- `low`: baseline state and failure checks.
- `medium`: health, falls, fluids, and threats at material boundaries.
- `high`: safety and threat checks before every action or batch; immediate danger preempts progress.

Use `minecraft_hook_create` and `minecraft_hook_poll` for inventory conditions such as acquiring
flint. Hooks observe state; they do not authorize actions.

## Finish

Do not accept the worker's statement alone. Verify the exact predicate with fresh MCP reads. Check
inventory for acquired items, blocks/entities for world changes, player state for location and
dimension, and UI state when relevant. Release held input and remove hooks before returning.
