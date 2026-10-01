"""False-success and privacy boundaries for genuine runtime trace capture."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from capture_usix_baseline import redact_events, verified_trace


COMMAND = "python3 /companion/tools/capture_health_baseline.py --runtime usix"
WITNESS = {
    "runtimeLabel": "usix", "invocationCwd": "/runtime", "recordedAt": "2026-10-01T00:00:00+00:00",
    "operation": "v1.health", "helperExitCode": 0,
    "health": {"ok": True, "auth": True, "paired": True, "listener": True, "accessibility": False},
    "elapsedMs": 100,
}
START = {"type": "tool_start", "tool": "bash", "args": {"command": COMMAND}}
RESULT = {"type": "tool_result", "success": True, "output": json.dumps(WITNESS)}
DONE = {"type": "done", "model": "actual-configured-model", "finish_reason": "completed"}


def events(*values):
    return redact_events(map(json.dumps, values), COMMAND)


class RuntimeEvidenceTest(unittest.TestCase):
    def test_serial_real_health_accepts_unavailable_accessibility(self):
        self.assertTrue(verified_trace(events(START, RESULT, DONE), WITNESS, 0))

    def test_claim_or_unrelated_tool_cannot_satisfy_device_gate(self):
        self.assertFalse(verified_trace(events(RESULT, DONE), WITNESS, 0))
        other = {**START, "args": {"command": "echo fabricated health"}}
        self.assertFalse(verified_trace(events(other, RESULT, DONE), WITNESS, 0))

    def test_interleaved_tool_results_are_not_assumed_to_correlate(self):
        other = {"type": "tool_start", "tool": "read_file", "args": {"path": "/private"}}
        self.assertFalse(verified_trace(events(START, other, RESULT, RESULT, DONE), WITNESS, 0))

    def test_timeout_incomplete_turn_or_disagreeing_witness_cannot_pass(self):
        trace = events(START, RESULT, DONE)
        self.assertFalse(verified_trace(trace, WITNESS, 124))
        self.assertFalse(verified_trace(events(START, RESULT), WITNESS, 0))
        changed = {**WITNESS, "health": {**WITNESS["health"], "paired": False}}
        self.assertFalse(verified_trace(trace, changed, 0))

    def test_reasoning_private_arguments_and_error_output_are_discarded(self):
        secret = "PRIVATE_VALUE_MUST_NOT_APPEAR"
        trace = events(
            {"type": "chunk", "text": secret},
            {"type": "diagnostic", "text": secret},
            {"type": "tool_start", "tool": "read_file", "args": {"path": secret}},
            {"type": "tool_result", "success": True, "output": secret},
            START, {"type": "tool_result", "success": False, "output": secret}, DONE,
        )
        self.assertNotIn(secret, json.dumps(trace))
        self.assertFalse(verified_trace(trace, WITNESS, 0))


if __name__ == "__main__":
    unittest.main()
