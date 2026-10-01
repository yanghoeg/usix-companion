"""A process restart or missing boot identity cannot satisfy physical recovery."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import capture_device_v2_reboot as capture


class PhysicalRebootEvidenceTest(unittest.TestCase):
    def run_capture(self, output, baseline):
        with patch.object(sys, "argv", ["capture", "--output", str(output), "after", "--baseline", str(baseline)]), contextlib.redirect_stdout(io.StringIO()):
            return capture.main()

    def test_same_boot_refuses_all_device_calls_and_renewal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = root / "before.json"
            baseline.write_text(json.dumps({"mode": "before", "readyForPhysicalReboot": True, "bootIdSha256": "same"}))
            with patch.object(capture, "boot_hash", return_value="same"), patch.object(capture, "call") as call, patch.object(capture.subprocess, "run") as renew:
                self.assertEqual(self.run_capture(root / "after.json", baseline), 1)
                call.assert_not_called(); renew.assert_not_called()
            report = json.loads((root / "after.json").read_text())
            self.assertFalse(report["physicalRebootVerified"])
            self.assertFalse(report["bootIdChanged"])
            self.assertEqual(report["steps"], [])

    def test_unreadable_boot_identity_fails_without_private_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            secret = "PRIVATE_DIAGNOSTIC_MUST_NOT_APPEAR"
            with patch.object(capture, "boot_hash", side_effect=OSError(secret)), patch.object(capture, "call") as call:
                self.assertEqual(self.run_capture(root / "after.json", root / "unread.json"), 1)
                call.assert_not_called()
            raw = (root / "after.json").read_text()
            self.assertNotIn(secret, raw)
            self.assertFalse(json.loads(raw)["physicalRebootVerified"])

    def test_existing_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "after.json"
            output.write_text("previous evidence")
            with patch.object(capture, "boot_hash") as boot, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.run_capture(output, root / "before.json")
            boot.assert_not_called()
            self.assertEqual(output.read_text(), "previous evidence")


if __name__ == "__main__":
    unittest.main()
