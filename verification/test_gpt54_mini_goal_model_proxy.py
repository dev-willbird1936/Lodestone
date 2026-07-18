"""Unit tests for the persistent per-goal-run conversation logic added to
gpt54-mini-goal-model-proxy.py: the runId-keyed decision history (persistence, cap, reset-on-
failure), the messages[-1]-discard bug fix, and the "fallback": true degraded-decision flag. These
cover everything testable without a live NeoForge client or a real `codex exec` invocation - the
subprocess call itself is monkeypatched.

Run with: python verification/test_gpt54_mini_goal_model_proxy.py
"""

import http.client
import importlib.util
import json
import os
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock

_MODULE_PATH = os.path.join(os.path.dirname(__file__), "gpt54-mini-goal-model-proxy.py")
_SPEC = importlib.util.spec_from_file_location("gpt54_mini_goal_model_proxy", _MODULE_PATH)
bridge = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(bridge)


class RenderMessagesTest(unittest.TestCase):
    def test_empty_messages_render_to_empty_string(self):
        self.assertEqual("", bridge.render_messages([]))

    def test_single_message_renders_bare_content(self):
        self.assertEqual("hello world", bridge.render_messages([{"role": "user", "content": "hello world"}]))

    def test_multiple_messages_all_survive_rendering(self):
        # Regression test for the original bug: messages[-1] silently discarded every earlier
        # message in the array. Every message's content must now appear in the rendered text.
        messages = [
            {"role": "user", "content": "first turn"},
            {"role": "assistant", "content": "first answer"},
            {"role": "user", "content": "second turn"},
        ]
        rendered = bridge.render_messages(messages)
        self.assertIn("first turn", rendered)
        self.assertIn("first answer", rendered)
        self.assertIn("second turn", rendered)


class SessionHistoryTest(unittest.TestCase):
    def setUp(self):
        bridge.SESSIONS.clear()

    def test_unknown_run_id_has_no_history_block(self):
        self.assertEqual("", bridge.session_history_block("never-seen"))

    def test_blank_run_id_has_no_history_block(self):
        self.assertEqual("", bridge.session_history_block(""))

    def test_recorded_turns_appear_in_order_in_the_history_block(self):
        bridge.record_session_turn("run-1", {"candidateIndex": 0, "rationale": "first choice"})
        bridge.record_session_turn("run-1", {"candidateIndex": 2, "rationale": "second choice"})

        block = bridge.session_history_block("run-1")

        self.assertIn("candidateIndex=0 rationale=first choice", block)
        self.assertIn("candidateIndex=2 rationale=second choice", block)
        self.assertLess(block.index("first choice"), block.index("second choice"))
        self.assertIn("NOT current world state", block)

    def test_history_never_stores_anything_beyond_candidate_index_and_rationale(self):
        # The persisted turn only ever keeps candidateIndex/rationale - never the caller's "state"
        # payload - so there is structurally nothing world-state-shaped in memory to go stale.
        bridge.record_session_turn("run-1", {
            "candidateIndex": 0, "rationale": "ok", "state": {"player": {"health": 20}},
        })
        stored = bridge.SESSIONS["run-1"][0]
        self.assertEqual({"candidateIndex", "rationale"}, set(stored.keys()))

    def test_history_is_capped_and_drops_oldest_first(self):
        for i in range(bridge.MAX_SESSION_TURNS + 5):
            bridge.record_session_turn("run-cap", {"candidateIndex": 0, "rationale": "turn-%d" % i})

        history = bridge.SESSIONS["run-cap"]

        self.assertEqual(bridge.MAX_SESSION_TURNS, len(history))
        self.assertEqual("turn-5", history[0]["rationale"])
        self.assertEqual("turn-%d" % (bridge.MAX_SESSION_TURNS + 4), history[-1]["rationale"])

    def test_reset_session_clears_history(self):
        bridge.record_session_turn("run-1", {"candidateIndex": 0, "rationale": "x"})
        bridge.reset_session("run-1")
        self.assertNotIn("run-1", bridge.SESSIONS)
        self.assertEqual("", bridge.session_history_block("run-1"))


class ChooseTest(unittest.TestCase):
    def setUp(self):
        bridge.SESSIONS.clear()

    def test_successful_call_records_a_turn_and_later_calls_see_it_as_prior_history(self):
        captured_prompts = []

        def fake_run(command, cwd, input, stdout, stderr, text, timeout, check):
            captured_prompts.append(input)
            decision = {"candidateIndex": 0, "rationale": "chose gather-resource"}
            return mock.Mock(stdout=json.dumps(decision), returncode=0)

        with mock.patch.object(bridge.subprocess, "run", side_effect=fake_run):
            first = bridge.choose([{"role": "user", "content": "goal state A"}], "low", "run-continuity")
            second = bridge.choose([{"role": "user", "content": "goal state B"}], "low", "run-continuity")

        self.assertEqual(0, first["candidateIndex"])
        self.assertNotIn("fallback", first)
        # The first call had no prior history yet - only this call's own fresh state.
        self.assertNotIn("Your own prior decisions", captured_prompts[0])
        self.assertIn("goal state A", captured_prompts[0])
        # The second call must see the first call's own rationale as prior context, and still
        # carry this call's own fresh state - never the first call's now-stale state.
        self.assertIn("chose gather-resource", captured_prompts[1])
        self.assertIn("goal state B", captured_prompts[1])
        self.assertNotIn("goal state A", captured_prompts[1])
        self.assertEqual(0, second["candidateIndex"])

    def test_model_error_returns_fallback_flag_and_resets_the_session(self):
        bridge.record_session_turn("run-error", {"candidateIndex": 0, "rationale": "earlier turn"})

        def failing_run(command, cwd, input, stdout, stderr, text, timeout, check):
            return mock.Mock(stdout="not json at all", returncode=1)

        with mock.patch.object(bridge.subprocess, "run", side_effect=failing_run):
            decision = bridge.choose([{"role": "user", "content": "goal state"}], "low", "run-error")

        self.assertEqual(0, decision["candidateIndex"])
        self.assertTrue(decision["fallback"])
        self.assertNotIn("run-error", bridge.SESSIONS,
                          "a failed call must not leave a poisoned history behind for the next call")

    def test_malformed_candidate_index_also_triggers_fallback(self):
        def bad_shape_run(command, cwd, input, stdout, stderr, text, timeout, check):
            return mock.Mock(stdout=json.dumps({"candidateIndex": "not-an-int", "rationale": "oops"}),
                              returncode=0)

        with mock.patch.object(bridge.subprocess, "run", side_effect=bad_shape_run):
            decision = bridge.choose([{"role": "user", "content": "goal state"}], "low", "run-bad-shape")

        self.assertTrue(decision["fallback"])

    def test_genuine_decision_has_no_fallback_key(self):
        def fake_run(command, cwd, input, stdout, stderr, text, timeout, check):
            return mock.Mock(stdout=json.dumps({"candidateIndex": 1, "rationale": "ok"}), returncode=0)

        with mock.patch.object(bridge.subprocess, "run", side_effect=fake_run):
            decision = bridge.choose([{"role": "user", "content": "goal state"}], "low", "run-genuine")

        self.assertNotIn("fallback", decision)


class EndSessionHttpTest(unittest.TestCase):
    def setUp(self):
        bridge.SESSIONS.clear()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), bridge.Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()

    def test_end_session_request_clears_history_and_acknowledges(self):
        bridge.record_session_turn("run-http", {"candidateIndex": 0, "rationale": "x"})

        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        body = json.dumps({"runId": "run-http", "endSession": True})
        connection.request("POST", "/v1/chat/completions", body=body,
                            headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        payload = json.loads(response.read().decode("utf-8"))
        connection.close()

        self.assertEqual(200, response.status)
        self.assertEqual({"ok": True}, payload)
        self.assertNotIn("run-http", bridge.SESSIONS)


if __name__ == "__main__":
    unittest.main()
