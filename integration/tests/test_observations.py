import base64
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from usix_companion import CONTRACT
from usix_companion.cli import main
from usix_companion.client import save_private
from usix_companion.contract import Contracts
from usix_companion.observations import scope, summarize_visual


def uid(): return str(uuid.uuid4())


class ObservationCliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.path = self.root / "profile.json"
        self.profile = {"profileVersion": 2, "workspace": str(self.root), "runtime": "usix-termux", "packageId": "dev.usix.companion.fixture",
                        "accountRef": uid(), "fixtureUi": True, "context": {**{key: uid() for key in ("deviceId", "runtimeId", "sessionId", "taskId", "workspaceId")}, "taskRevision": 1},
                        "connection": {"transport": "loopback", "endpoint": "http://127.0.0.1:8760"}, "bearer": "synthetic-private-bearer", "grantRef": uid(), "lease": {"leaseId": uid(), "revision": 1}}
        save_private(self.path, self.profile)

    def tearDown(self): self.directory.cleanup()

    def invoke(self, arguments, response=None):
        output = io.StringIO()
        with patch("usix_companion.cli.call", return_value=response or {"contractVersion": CONTRACT, "kind": "wait_cancelled", "effect": "none"}) as network, contextlib.redirect_stdout(output):
            code = main(["--profile", str(self.path), *arguments])
        return code, json.loads(output.getvalue()), network

    def test_unknown_selector_and_null_action_arguments_never_reach_device(self):
        for arguments in (["observe", "--selector", '{"textContains":"send"}'],
                          ["ui", "--operation", "ui.set_text", "--action-id", uid(), "--snapshot-ref", uid(), "--payload", '{"target":{"role":"textbox"},"text":null}'],
                          ["wait", "--timeout-ms", "30001", "--cancellation-id", uid()]):
            with self.subTest(arguments=arguments):
                code, result, network = self.invoke(arguments)
                self.assertEqual(1, code); self.assertEqual("InvalidRequest", result["error"]["code"]); network.assert_not_called()

    def test_wait_is_bound_to_captured_account_and_cancel_identity(self):
        cancellation = uid()
        code, _, network = self.invoke(["wait", "--kind", "text", "--selector", '{"text":"Controlled"}', "--cancellation-id", cancellation])
        self.assertEqual(0, code)
        _, route, _, body = network.call_args.args
        self.assertEqual("/v2/wait", route); self.assertEqual(scope(self.profile), body["scope"]); self.assertEqual(cancellation, body["cancellationId"])
        self.assertNotIn(self.profile["bearer"], json.dumps(body))

    def test_image_export_cannot_escape_workspace_or_overwrite_files(self):
        outside = self.root.parent / ("outside-" + uid() + ".png")
        for requested in (str(outside), "../escape.png"):
            code, _, network = self.invoke(["capture", "--snapshot-ref", uid(), "--output", requested])
            self.assertEqual(1, code); network.assert_not_called(); self.assertFalse(outside.exists())
        existing = self.root / "existing.png"; existing.write_bytes(b"preserved")
        code, _, network = self.invoke(["capture", "--snapshot-ref", uid(), "--output", str(existing)])
        self.assertEqual(1, code); network.assert_not_called(); self.assertEqual(b"preserved", existing.read_bytes())

    def visual(self):
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkN0AAAAASUVORK5CYII=")
        return {"contractVersion": CONTRACT, "kind": "visual", "requestId": uid(), "context": self.profile["context"], "scope": scope(self.profile),
                "snapshotRef": uid(), "mediaRef": uid(), "width": 1, "height": 1, "contentHash": "sha256:" + hashlib.sha256(png).hexdigest(),
                "language": "latin", "redacted": True, "untrusted": True, "encoding": "png_base64", "coordinateSpace": "image_pixels",
                "pngChunks": [base64.b64encode(png).decode()], "blocks": []}

    def test_capture_is_hash_checked_private_and_png_is_omitted_from_model_output(self):
        visual = self.visual(); Contracts().validate("visual", visual)
        code, result, _ = self.invoke(["capture", "--snapshot-ref", visual["snapshotRef"], "--output", "capture.png"], visual)
        self.assertEqual(0, code); self.assertNotIn("pngChunks", result); self.assertEqual("visual_summary", result["kind"])
        path = Path(result["imagePath"]); self.assertEqual(self.root / "capture.png", path); self.assertEqual(0o600, path.stat().st_mode & 0o777)
        bad = self.visual(); bad["contentHash"] = "sha256:" + "0" * 64
        with self.assertRaises(ValueError): summarize_visual(self.profile, bad, None)

    def test_capture_response_cannot_replace_the_selected_account(self):
        visual = self.visual(); visual["scope"]["accountRef"] = uid()
        code, result, _ = self.invoke(["capture", "--snapshot-ref", uid()], visual)
        self.assertEqual(1, code); self.assertEqual("IdentityMismatch", result["error"]["code"])


if __name__ == "__main__": unittest.main()
