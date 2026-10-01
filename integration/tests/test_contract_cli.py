import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from usix_companion import CONTRACT
from usix_companion.cli import main
from usix_companion.client import private_json, save_private
from usix_companion.contract import Contracts, loads, payload_hash

ROOT = Path(__file__).resolve().parents[2]


class ContractCliTest(unittest.TestCase):
    def test_standalone_package_validates_shared_golden_examples(self):
        contracts = Contracts()
        for filename, schema in (("command-mail-send", "command"), ("receipt-unknown-effect", "receipt"),
                                 ("receipt-verified", "receipt"), ("capabilities", "capabilities"), ("event-gap", "event")):
            value = loads((ROOT / "contracts/device/v2/examples" / (filename + ".json")).read_bytes())
            contracts.validate(schema, value)
        command = loads((ROOT / "contracts/device/v2/examples/command-mail-send.json").read_bytes())
        vector = loads((ROOT / "contracts/device/v2/examples/payload-hash-vector.json").read_bytes())
        self.assertEqual(vector["sha256"], payload_hash(command))

    def test_strict_json_rejects_duplicate_float_surrogate_and_depth(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":1.0}', b'{"x":NaN}', b'{"x":9007199254740992}', b'{"x":"\\ud800"}',
                    (b'{"x":' * 34) + b'null' + b'}' * 34):
            with self.subTest(raw=raw[:20]), self.assertRaises((ValueError, UnicodeError)):
                loads(raw)

    def test_profile_is_private_atomic_and_cwd_independent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workspace = root / "workspace"; workspace.mkdir()
            profile_path = root / "profile.json"
            context = {"synthetic": "captured-context"}
            profile = {"profileVersion": 2, "workspace": str(workspace), "context": context, "connection": {"transport": "loopback", "endpoint": "http://127.0.0.1:8760"}, "bearer": "synthetic-private-bearer"}
            save_private(profile_path, profile)
            self.assertEqual(0o600, profile_path.stat().st_mode & 0o777)
            observed = []
            def call(connection, path, bearer, body):
                observed.append((connection, path, bearer, body))
                return {"contractVersion": CONTRACT, "kind": "observation", "context": context, "health": {"ok": True}}
            old = Path.cwd()
            try:
                for cwd in (root, workspace):
                    os.chdir(cwd)
                    output = io.StringIO()
                    with patch("usix_companion.cli.call", side_effect=call), contextlib.redirect_stdout(output):
                        self.assertEqual(0, main(["--profile", str(profile_path), "health"]))
                    self.assertNotIn(profile["bearer"], output.getvalue())
                    self.assertEqual(context, json.loads(output.getvalue())["context"])
            finally:
                os.chdir(old)
            self.assertEqual(2, len(observed)); self.assertEqual(profile, private_json(profile_path))

    def test_wrong_context_is_rejected_before_network(self):
        command = loads((ROOT / "contracts/device/v2/examples/command-mail-send.json").read_bytes())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); profile = root / "profile.json"; request = root / "request.json"
            save_private(profile, {"profileVersion": 2, "workspace": str(root), "context": {**command["context"], "taskRevision": 2}})
            request.write_text(json.dumps(command))
            output = io.StringIO()
            with patch("usix_companion.cli.call") as network, contextlib.redirect_stdout(output):
                self.assertEqual(1, main(["--profile", str(profile), "execute", "--request-file", str(request)]))
                network.assert_not_called()
            self.assertEqual("IdentityMismatch", json.loads(output.getvalue())["error"]["code"])

    def test_insecure_profile_and_symlink_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"; save_private(path, {})
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                private_json(path)
            path.chmod(0o600); linked = path.with_name("symlink.json"); linked.symlink_to(path)
            with self.assertRaises(ValueError):
                private_json(linked)

    def test_trusted_renewal_preserves_context_and_omits_rotated_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "profile.json"; owner = root / "owner"
            owner.write_text("synthetic-owner-bearer"); owner.chmod(0o600)
            context = {"deviceId": "00000000-0000-4000-8000-000000000001", "sessionId": "synthetic-session"}
            original = {"profileVersion": 2, "workspace": str(root), "context": context, "connection": {"transport": "loopback"},
                        "runtime": "usix-termux", "packageId": "dev.usix.companion", "bearer": "synthetic-old-bearer", "grantRef": "old", "lease": {"revision": 1}}
            save_private(path, original)
            requests = []
            def call(connection, route, bearer, body):
                requests.append((route, body))
                self.assertEqual("synthetic-owner-bearer", bearer)
                if route.endswith("challenge"):
                    return {"kind": "pairing_challenge", "deviceId": context["deviceId"], "challengeId": "synthetic", "nonce": "synthetic-nonce"}
                self.assertEqual(context, body["context"]); self.assertEqual(original["packageId"], body["packageId"])
                return {"kind": "paired", "context": context, "bearer": "synthetic-rotated-bearer", "grantRef": "renewed-grant", "expiresAt": "synthetic-time"}
            output = io.StringIO()
            with patch("usix_companion.cli.call", side_effect=call), contextlib.redirect_stdout(output):
                self.assertEqual(0, main(["--profile", str(path), "renew", "--owner-token-file", str(owner)]))
            updated = private_json(path)
            self.assertEqual(context, updated["context"]); self.assertEqual(str(root), updated["workspace"])
            self.assertIsNone(updated["lease"]); self.assertEqual("synthetic-rotated-bearer", updated["bearer"])
            self.assertNotIn(updated["bearer"], output.getvalue()); self.assertNotIn(owner.read_text(), output.getvalue())
            self.assertEqual(2, len(requests))


if __name__ == "__main__":
    unittest.main()
