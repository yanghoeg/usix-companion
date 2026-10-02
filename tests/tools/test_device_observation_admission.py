"""Fixture evaluator safety boundaries; these do not qualify a real device."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

SPEC = importlib.util.spec_from_file_location("device_observations", Path(__file__).resolve().parents[2] / "tools/capture_device_observations.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FreshAdmissionTest(unittest.TestCase):
    def evaluator(self):
        value = MODULE.Qualification.__new__(MODULE.Qualification)
        value.checks = []
        return value

    def test_acknowledged_or_uncertain_effect_and_other_failures_are_never_replayed(self):
        for state, effect, code in (("Dispatched", "acknowledged", "StaleSnapshot"),
                                   ("UnknownEffect", "possible", "StaleSnapshot"),
                                   ("Failed", "none", "ExpiredReference")):
            with self.subTest(state=state):
                q = self.evaluator()
                q.observe = Mock(return_value={"snapshotRef": "first"})
                receipt = {"state": state, "effect": effect, "error": {"code": code}}
                q.action = Mock(return_value=(receipt, {"actionId": "one"}))
                self.assertEqual(q.fresh_action("ui.back", {})[0], receipt)
                q.action.assert_called_once()
                q.observe.assert_called_once()

    def test_known_none_stale_requires_new_snapshot_and_recomputed_coordinates(self):
        q = self.evaluator()
        q.observe = Mock(side_effect=[{"snapshotRef": "old", "x": 10}, {"snapshotRef": "new", "x": 200}])
        q.action = Mock(side_effect=[({"state": "Failed", "effect": "none", "error": {"code": "StaleSnapshot"}}, {"actionId": "rejected"}),
                                    ({"state": "Dispatched", "effect": "acknowledged"}, {"actionId": "fresh"})])
        receipt, command, observed = q.fresh_action("ui.tap", lambda snapshot: {"x": snapshot["x"], "y": 30})
        self.assertEqual(receipt["state"], "Dispatched")
        self.assertEqual(command["actionId"], "fresh")
        self.assertEqual(observed["snapshotRef"], "new")
        self.assertEqual([call.args for call in q.action.call_args_list],
                         [("ui.tap", {"x": 10, "y": 30}, "old"), ("ui.tap", {"x": 200, "y": 30}, "new")])
        self.assertEqual(q.checks[0]["actionId"], "rejected")
        self.assertEqual(q.checks[0]["effect"], "none")

    def test_failed_wait_stays_failed_when_late_diagnostic_matches_and_omits_raw_data(self):
        q = self.evaluator()
        q.profile = {"packageId": MODULE.PACKAGE, "accountRef": None}
        q.read = Mock(side_effect=[{"kind": "error", "error": {"code": "DeadlineExceeded"}, "effect": "none"},
                                  {"kind": "snapshot", "generation": 2, "rotation": 1, "complete": True, "totalNodes": 2,
                                   "bearer": "PRIVATE_CREDENTIAL", "nodes": [{"text": "Expected", "visible": True},
                                                                             {"text": "PRIVATE_SCREEN_TEXT", "visible": False}]}])
        with self.assertRaises(ValueError): q.wait("Expected", milliseconds=20)
        self.assertEqual([call.args[0] for call in q.read.call_args_list], ["/v2/wait", "/v2/observe"])
        self.assertEqual(q.last_error_code, "DeadlineExceeded")
        self.assertEqual(q.last_operation, "/v2/wait")
        self.assertEqual(q.wait_failure["expectedVisible"], 1)
        diagnostic = str(q.wait_failure)
        self.assertNotIn("PRIVATE_CREDENTIAL", diagnostic)
        self.assertNotIn("PRIVATE_SCREEN_TEXT", diagnostic)

    def test_window_deadline_remains_failed_after_late_recovery_without_effect_or_private_data(self):
        q = self.evaluator()
        q.profile = {"packageId": MODULE.PACKAGE, "accountRef": None}
        q.read = Mock(side_effect=[{"kind": "error", "error": {"code": "DeadlineExceeded"}, "effect": "none"},
                                  {"kind": "snapshot", "generation": 3, "rotation": 1, "complete": True, "totalNodes": 1,
                                   "bearer": "PRIVATE_CREDENTIAL", "nodes": [{"text": "PRIVATE_SCREEN_TEXT"}]}])
        q.action = Mock()
        with self.assertRaises(ValueError): q.window(milliseconds=20000)
        self.assertEqual([call.args[0] for call in q.read.call_args_list], ["/v2/wait", "/v2/observe"])
        q.action.assert_not_called()
        self.assertEqual(q.last_error_code, "DeadlineExceeded")
        self.assertEqual(q.last_operation, "/v2/wait")
        self.assertEqual(q.window_failure["deadlineMillis"], 20000)
        self.assertEqual(q.window_failure["kind"], "snapshot")
        self.assertNotIn("PRIVATE_CREDENTIAL", str(q.window_failure))
        self.assertNotIn("PRIVATE_SCREEN_TEXT", str(q.window_failure))
