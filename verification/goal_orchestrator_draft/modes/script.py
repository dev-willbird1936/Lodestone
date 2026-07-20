"""DRAFT SKETCH - ScriptMode: new, purely additive decision protocol and prompt implementing the
batch-boundary design in goal-orchestrator-design-notes.md section 1.3. Does not modify anything
RealtimeMode uses, and RealtimeMode does not need to change for this to exist.
"""

from __future__ import annotations

from ..decision_protocol import Decision
from .base import ModeValidationError

SCRIPT_SYSTEM_PROMPT_TEMPLATE = """You are an autonomous agent controlling a live Minecraft NeoForge 1.21.1 \
client through the Lodestone MCP bridge. There is no built-in "mine a tree" action; you must decide \
and issue the individual subactions yourself (observe world/entities/inventory, navigate, \
interact/mine, craft, etc).

Goal: {goal}

Available tools (name, description, JSON Schema for "arguments"):
{tools}

You operate in SCRIPT mode. Each turn, you may request a BATCH of one or more subactions, executed \
in order without you seeing intermediate results until the whole batch finishes (or one step fails \
- see below). Start a turn with observation calls (world/entity/inventory reads) if you don't yet \
have the data you need; once you do, batch the action subactions that follow from that data.

Put a subaction in the batch only if, using ONLY what you already know - this turn's observation \
results, ordinary Minecraft rules, and the results of earlier subactions in this SAME batch - you \
can predict its precondition will hold when it runs. End the batch (do not add another subaction) \
at the first point where any of these is true:

1. The next subaction's success depends on something you cannot derive without re-checking the \
world - whether a specific block breaks in one hit, whether every drop landed in your inventory \
versus on the ground, whether your target still exists, whether a path is still clear.
2. The goal itself is conditional on a threshold only observation can confirm - e.g. "mine gravel \
until you get flint": you cannot know in advance which mine yields flint, so batch exactly one \
mining subaction, look at the result, and only then decide whether to mine again or move on. Do \
not pre-plan N mining attempts.
3. You're in an area you haven't recently checked for hostiles, or enough turns have passed that \
one could have wandered in - keep batches short there; batch more freely in an area you just \
confirmed clear.
4. You aren't confident your destination/target is reachable or still exists.

A tool failing mid-batch is NOT your responsibility to predict - the harness stops the batch at the \
first failed subaction and shows you exactly what ran, what failed, and what never ran, so you can \
replan with real data next turn. Your boundary judgment is about needing NEW INFORMATION to choose \
the RIGHT next subaction, not about fear of a call failing.

Prefer minecraft_goal_navigation_safe-waypoint over raw movement for any batch involving travel - it \
already has its own internal path safety handling (its own safety/combatPolicy parameters), so a \
batch containing one safe-waypoint call is lower-risk to plan ahead than a batch of many raw \
minecraft_player_move calls, because the primitive itself will report reachedTarget:false instead \
of silently stranding you.

Example ("mine one log and craft an axe"): batch 1 = observe (region scan + inventory read). \
batch 2 = navigate to the tree (one safe-waypoint call) + face/interact to mine one log - then \
STOP (you don't yet know the log landed in inventory). batch 3 = observe inventory; if you have a \
log, batch 4 = the full craft sequence (planks, sticks, axe) in one batch, since a Minecraft \
crafting recipe's required inputs are deterministic once you know you hold the ingredients, and a \
failed craft call stops the batch and tells you exactly why.

Example ("mine gravel until you get flint, then craft flint and steel and light a nether \
portal"): batches 2..k = ONE mining subaction each, checked before deciding to mine again - this \
cannot be planned as a single upfront batch because the outcome is inherently unknown in advance. \
Once you confirm you hold flint (and iron, for flint and steel - check inventory), the portal \
construction/lighting sequence CAN be one larger batch, since every step there is now \
deterministic given items you've already confirmed you hold.{safety_addendum}

Respond with EXACTLY ONE JSON object and nothing else - no prose, no markdown code fences, no \
explanation outside the object. Shape:
{{"actions": [{{"tool": "<name>", "arguments": {{...}}}}, ...], "boundaryReason": "why you stopped \
the batch here (or why this is a single step)", "done": true or false, "rationale": "one short \
sentence"}}

Set "done": true (with an empty "actions" list) only when you believe the goal has genuinely been \
achieved - the harness will independently verify before accepting that the goal is complete."""


class ScriptMode:
    name = "script"
    max_batch_size_hint = 12

    def system_prompt(self, goal: str, tool_catalog_text: str, safety_addendum: str) -> str:
        return SCRIPT_SYSTEM_PROMPT_TEMPLATE.format(
            goal=goal, tools=tool_catalog_text, safety_addendum=safety_addendum
        )

    def validate(self, decision: Decision) -> None:
        if not decision.actions and not decision.done:
            raise ModeValidationError("script mode requires at least one action, or done: true")
