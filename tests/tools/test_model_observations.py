"""Synthetic evidence-boundary tests; never substitute for physical/model exits."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timezone

SPEC = importlib.util.spec_from_file_location("model_observations", Path(__file__).resolve().parents[2] / "tools/capture_model_observations.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ModelObservationEvidenceTest(unittest.TestCase):
    def test_client_tool_execute_without_start_requires_matching_call_and_request(self):
        command = "python3 /companion/fixed-helper.py"
        witness = {"operation": "P3.controlled-observation", "passed": True, "evidenceSha256": "a" * 64}
        execute = {"type": "tool_execute", "tool": "bash", "args": {"command": command}, "call_id": "call-1", "request_id": "request-1"}
        result = {"type": "tool_result", "success": True, "output": json.dumps(witness), "call_id": "call-1", "request_id": "request-1"}
        done = {"type": "done", "finish_reason": "completed"}
        def verify(messages):
            return MODULE.verified_usix(MODULE.redact_usix([json.dumps(m) for m in messages], command), witness, 0)
        self.assertTrue(verify([execute, result, done]))
        self.assertTrue(verify([{**execute, "type": "tool_start"}, execute, result, done]))
        self.assertFalse(verify([execute, {**result, "call_id": "another-call"}, done]))
        self.assertFalse(verify([execute, {**result, "request_id": "another-request"}, done]))
        self.assertFalse(verify([{**execute, "type": "tool_start", "args": {"command": "another command"}}, execute, result, done]))

    def test_partial_or_old_device_report_is_never_a_passing_witness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "physical.json"
            now = datetime.now(timezone.utc).timestamp()
            value = {"recordedAt": datetime.fromtimestamp(now, timezone.utc).isoformat(), "runtimeLabel": "usix", "invocationCwd": str(root),
                     "operation": "P3.controlled-observation", "suite": "all", "passed": True, "deviceCalls": 100, "elapsedMs": 2000,
                     "lastDeviceErrorCode": None, "contextSha256": "a" * 64,
                     "checks": [{"check": name, "passed": True} for name in sorted(MODULE.REQUIRED_CHECKS)]}
            path.write_text(json.dumps(value))
            self.assertIsNotNone(MODULE.summary(path, "usix", root, now - 1, now + 1))
            self.assertIsNone(MODULE.summary(path, "usix", root, now + 1, now + 2))
            value["checks"].pop(); path.write_text(json.dumps(value))
            self.assertIsNone(MODULE.summary(path, "usix", root, now - 1, now + 1))

    def test_completed_model_claim_without_matching_successful_tool_is_rejected(self):
        witness = {"passed": True, "evidenceSha256": "a" * 64}
        done = {"type": "done", "finish_reason": "completed"}
        result = {"type": "tool_result", "exactCommandResult": True, "success": True, "deviceWitness": witness}
        self.assertTrue(MODULE.verified_usix([result, done], witness, 0))
        self.assertFalse(MODULE.verified_usix([done], witness, 0))
        self.assertFalse(MODULE.verified_usix([{**result, "success": False}, done], witness, 0))
        self.assertFalse(MODULE.verified_usix([result, done], {**witness, "evidenceSha256": "b" * 64}, 0))
        self.assertFalse(MODULE.verified_usix([result, done], witness, 1))

    def test_termux_approval_never_accepts_additional_commands_or_arguments(self):
        command = "python3 /companion/fixture.py"
        def prompt(tool, args): return "approval needed: " + tool + " " + json.dumps(args) + "  [y/N] "
        self.assertEqual("once", MODULE.termux_approval(prompt("shell", {"command": command}), command)["decision"])
        for tool, args in [("shell", {"command": command + " && other"}), ("shell", {"command": command, "extra": True}), ("other", {"command": command})]:
            self.assertEqual("deny", MODULE.termux_approval(prompt(tool, args), command)["decision"])


if __name__ == "__main__": unittest.main()
