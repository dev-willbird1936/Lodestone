"""Unit tests for the two CLI_SYSTEM_PROMPT_TEMPLATE additions that followed trace-f0679eb8edb3.jsonl:
the oak log genuinely broke (block read confirmed air at the mined position) but the drop item was
never collected, and the model still set "done": true on turn 39 while its own rationale said "the
log wasn't in inventory yet, so recheck instead" - a self-contradictory completion claim the harness's
independent verify_log_in_inventory() correctly rejected (status: GOAL_NOT_CONFIRMED).

1. Drop-collection guidance: breaking a block only drops an item entity - it takes walking to it
   (physical proximity) to actually add it to inventory, and that should be confirmed with a read.
2. Completion-discipline hard rule: "done": true may only be set when a same/prior-turn observation
   positively confirmed the goal condition - not when the most recent check showed it unmet.

These are plain substring checks against the live template text (matching the assertion style already
used in test_goal_orchestrator_milestone1_tool_catalog.py, e.g.
test_screen_gated_description_notes_it_needs_an_open_screen), plus a .format() round-trip regression
guard - free-text prompt edits can accidentally introduce a stray "{" or "}" that breaks str.format
at runtime without any test ever calling build_cli_prompt() with real args.

Run with: python verification/test_goal_orchestrator_milestone1_prompt_content.py
"""

import importlib.util
import os
import sys
import unittest

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "goal-orchestrator-milestone1.py")
_SPEC = importlib.util.spec_from_file_location("goal_orchestrator_milestone1", _MODULE_PATH)
orchestrator = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = orchestrator  # see test_goal_orchestrator_milestone1_history.py for why
_SPEC.loader.exec_module(orchestrator)


class CliPromptContentTest(unittest.TestCase):
    def test_mentions_drop_collection_guidance(self):
        template = orchestrator.CLI_SYSTEM_PROMPT_TEMPLATE
        self.assertIn("physical proximity", template)
        self.assertIn("minecraft_inventory_read", template)
        self.assertIn("minecraft_entity_nearby_read", template)

    def test_mentions_completion_discipline_hard_rule(self):
        template = orchestrator.CLI_SYSTEM_PROMPT_TEMPLATE
        self.assertIn("Hard rule", template)
        self.assertIn('do not set "done": true in that', template)
        # The turn-39 negative example should be embedded verbatim, not paraphrased away.
        self.assertIn("the log wasn't in inventory yet, so recheck instead", template)

    def test_template_still_formats_with_real_args(self):
        # Regression guard: a stray unescaped "{" or "}" in either new paragraph would raise here.
        rendered = orchestrator.CLI_SYSTEM_PROMPT_TEMPLATE.format(
            goal="mine one log", tools="- minecraft_world_block_read: ...\n"
        )
        self.assertIn("mine one log", rendered)


if __name__ == "__main__":
    unittest.main()
