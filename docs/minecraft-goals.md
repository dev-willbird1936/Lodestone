# Model-owned Minecraft goals

Minecraft goals are owned by the model in the agent session where `lodestone-goal` is called. The
MCP bridge supplies typed observations and primitive subactions. It does not select or execute a
built-in task for the agent.

This design targets Minecraft 1.21.1 with NeoForge.

## Entry point

Use the shared `lodestone-goal` skill. The calling model can complete the work itself or launch one
native goal worker using the lowest-latency tool-capable model that the current host exposes. Model
selection is capability based and never pins a vendor or model name. The parent session remains
responsible for coordination, recovery, and terminal verification.

There is no public `minecraft_goal`, `minecraft_goal_tasks`, or `minecraft_goal_benchmark` MCP tool.
The server does not launch a separate CLI or provider process to own the goal.

## Model ownership

The model plans every high-level step. This includes inspecting the title screen, loading or
creating a world, verifying the player, finding resources, moving, mining, collecting drops,
opening inventory or containers, crafting, equipping items, recovering from failure, and checking
the final predicate.

Native task shortcuts are forbidden. MCP discovery hides all `minecraft.goal.*` capabilities except
`minecraft.goal.navigation.safe-waypoint`. Direct capability get, invoke, and script batching reject
the same hidden IDs. The safe-waypoint exception is a bounded pathing primitive; it does not own a
user goal or perform crafting, gathering, combat, or world setup.

Deterministic native survival, creative, combat, crafting, tree, and Nether actors remain internal
regression fixtures. They can test the mod implementation, but agents cannot use them to complete a
goal.

## Public MCP helpers

- `lodestone_capabilities_list`, `lodestone_capability_get`, and
  `lodestone_capability_search` discover model-usable observations and primitive actions.
- `lodestone_capability_invoke` executes one typed primitive capability.
- `minecraft_subactions_execute` executes one bounded ordered script segment and stops on error.
- `minecraft_hook_create`, `minecraft_hook_poll`, and `minecraft_hook_remove` provide session-owned
  inventory conditions, such as detecting when flint enters inventory.

Hooks provide observations. They do not perform actions or authorize unsafe behavior.

## Modes

### Script

The model observes the state, then creates one ordered batch of primitive subactions only through
the next uncertainty boundary. It must stop and re-observe before any step that depends on an
unknown prior result.

For example, the model can observe a reachable tree, batch movement and bounded mining, then stop
to verify collected logs before it plans crafting. For flint, it can mine a bounded gravel batch,
then check an inventory hook or read inventory before it plans flint-and-steel crafting and portal
lighting.

Uncertain reachability, random drops, combat, UI transitions, missing pickups, changed inventory,
failed actions, and indeterminate outcomes always end the current script segment.

### Realtime

The model observes, executes one logical primitive subaction, reads the result and fresh relevant
state, and then chooses the next subaction. It can therefore react after each gravel break, movement
attempt, pickup, craft, threat observation, or UI change.

## Intelligence and safety

Both controls use only `low`, `medium`, or `high`. They are independent.

Intelligence controls planning depth:

- `low`: use the shortest viable plan and only required observations.
- `medium`: check prerequisites, tools, reachability, and likely recovery.
- `high`: consider alternate paths, explicit uncertainty boundaries, and extra terminal checks.

Safety controls observation and intervention:

- `low`: use baseline state and failure checks.
- `medium`: check health, falls, fluids, and nearby threats at material boundaries.
- `high`: check threats and player safety before every action or script batch; immediate danger
  preempts progress and safer routes are preferred.

Intelligence does not weaken safety. Commands remain denied unless the user explicitly permits
them.

## Completion

The worker's statement is not proof. The parent must use fresh MCP reads to verify the exact final
predicate. Relevant evidence can include inventory counts, equipped items, remaining target blocks,
entity state, player location or dimension, health, and current UI. Release held input and remove
condition hooks before returning.

Run `Run-Lodestone-Goal-Checks.bat` to verify the model-owned MCP boundary, goal helper services,
internal regression engine, Python orchestration fixtures, and the NeoForge 1.21.1 build.
