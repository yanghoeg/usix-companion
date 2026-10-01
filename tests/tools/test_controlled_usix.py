"""Synthetic approval-boundary tests; these do not supply real model evidence."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[2] / "tools"
spec = importlib.util.spec_from_file_location("controlled_usix", TOOLS / "controlled_usix.py")
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class ControlledUsixTest(unittest.TestCase):
    def test_only_exact_existing_bash_request_gets_one_time_approval(self):
        command = "python3 /controlled/read_health.py"
        good = {"type": "permission_request", "tool": "bash", "call_id": "synthetic-call", "args": {"command": command}}
        self.assertEqual("once", module.approval(good, command)["decision"])
        for changed in ({**good, "tool": "write_file"}, {**good, "args": {"command": command + " && other"}},
                        {**good, "args": {"command": command, "extra": "unexpected"}}, {**good, "call_id": None}):
            self.assertEqual("deny", module.approval(changed, command)["decision"])
        self.assertIsNone(module.approval({"type": "chunk", "text": json.dumps(good)}, command))

    def test_real_tty_input_uses_once_and_denies_other_command(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic_cli.py"
            for actual, answer in (("controlled-health", "y"), ("other-command", "n")):
                event = {"type": "permission_request", "tool": "bash", "call_id": "synthetic-call", "args": {"command": actual}}
                path.write_text("import json,sys\nassert sys.stdin.isatty()\nprint(" + repr(json.dumps(event)) + ",flush=True)\nassert input().strip()==" + repr(answer) + "\nprint('{\"type\":\"done\"}',flush=True)\n")
                code, lines, decisions = module.run([sys.executable, str(path)], directory, "controlled-health", 5)
                self.assertEqual(0, code); self.assertEqual(1, len(decisions))
                self.assertEqual("once" if answer == "y" else "deny", decisions[0]["decision"])
                self.assertTrue(any('"type":"done"' in line for line in lines))


if __name__ == "__main__":
    unittest.main()
