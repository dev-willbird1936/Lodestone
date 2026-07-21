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

The model in the session where this skill was called owns the goal and all high-level thinking. It
may use the current host's native subagent facility. When it delegates, start one goal worker with
the lowest-latency tool-capable model the host currently exposes. Prefer a latency-optimized small,
mini, flash, or haiku-like model. Do not pin a vendor or model name. Match reasoning effort to the
intelligence tier.

Give the worker the goal, mode, policies, controls, limits, live Lodestone MCP context, and this
workflow. The parent monitors recovery and verifies completion. If native subagents are not
available, run this workflow in the current agent. Never let workers control one player concurrently.

## No native task shortcuts

Never call `minecraft_goal`, `minecraft_goal_tasks`, or `minecraft_goal_benchmark`. Never discover,
get, invoke, or batch a capability under `minecraft.goal.*`, except
`minecraft.goal.navigation.safe-waypoint`, which is a bounded pathing primitive. In particular, do
not call survival, crafting, tree, combat, creative, or Nether task routines. They are internal
regression fixtures, not agent tools.

The current model must decompose every requested goal into observed state and primitive subactions.
Loading or creating a world, dismissing menus, moving, mining, collecting drops, opening inventory,
crafting, equipping, and final verification are all part of that model-owned plan. Do not assume a
world is loaded and do not delegate those steps to a native routine.

## Discover

Call `lodestone_capabilities_list`. First read the current UI. If no world and player exist, use UI
subactions to load or create a world, then verify both. Read the player, inventory, nearby blocks and
entities, and UI state needed for the next decision. Treat errors, stale revisions, unreachable
paths, missing drops, and changed inventory as evidence that requires replanning.

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
