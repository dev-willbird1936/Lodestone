"""Unit tests for task #18's tool-catalog fix in goal-orchestrator-milestone1.py: screen-gated
capabilities (minecraft.ui.click, minecraft.ui.text.insert, minecraft.inventory.container.read,
minecraft.inventory.container.click) must be offered as tools regardless of their availability at
catalog-build time, since that snapshot is taken before any screen is open and would otherwise
permanently hide them even after a screen (e.g. a death screen) legitimately opens later - see
trace-3ca0c42b0534.jsonl, where the model died, found the Respawn button via
minecraft.ui.state.read, but had no working way to click it because minecraft.ui.click was never
offered as a tool at all.

Run with: python verification/test_goal_orchestrator_milestone1_tool_catalog.py
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


def capability(capability_id: str, availability: str) -> dict:
    return {
        "id": capability_id,
        "availability": availability,
        "version": "1.0",
        "documentation": f"Docs for {capability_id}.",
        "inputSchema": {"type": "object", "properties": {}},
    }


class ScreenGatedCatalogTest(unittest.TestCase):
    def test_screen_gated_capability_included_even_when_reported_unavailable(self):
        capabilities = [capability("minecraft.ui.click", "unavailable")]
        tools, dispatch = orchestrator.build_tool_catalog(capabilities)
        self.assertIn("minecraft_ui_click", dispatch)
        self.assertEqual(1, len(tools))

    def test_all_four_screen_gated_capabilities_are_covered(self):
        for capability_id in orchestrator.SCREEN_GATED_CAPABILITIES:
            with self.subTest(capability_id=capability_id):
                capabilities = [capability(capability_id, "unavailable")]
                _tools, dispatch = orchestrator.build_tool_catalog(capabilities)
                self.assertIn(orchestrator.capability_id_to_tool_name(capability_id), dispatch)

    def test_screen_gated_description_notes_it_needs_an_open_screen(self):
        capabilities = [capability("minecraft.ui.click", "unavailable")]
        tools, _dispatch = orchestrator.build_tool_catalog(capabilities)
        self.assertIn("screen", tools[0]["description"].lower())

    def test_ordinary_unavailable_capability_still_excluded(self):
        # The fix must not become a blanket "ignore availability" - only the named screen-gated
        # ids get the exception; anything else genuinely unavailable (e.g. not-implemented) stays
        # excluded exactly as before.
        capabilities = [capability("minecraft.player.move", "unavailable")]
        tools, dispatch = orchestrator.build_tool_catalog(capabilities)
        self.assertEqual([], tools)
        self.assertEqual({}, dispatch)

    def test_screen_gated_capability_still_included_when_genuinely_available(self):
        # Once a screen really is open, the live listing may correctly report "available" or
        # "restricted" - the fix must not regress that ordinary path.
        capabilities = [capability("minecraft.inventory.container.read", "available")]
        tools, dispatch = orchestrator.build_tool_catalog(capabilities)
        self.assertIn("minecraft_inventory_container_read", dispatch)
        self.assertEqual(1, len(tools))


if __name__ == "__main__":
    unittest.main()
