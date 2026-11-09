"""History query validation, loopback-only credentials and no UI fallback."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib import error, parse

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import companion_http as http


class HistoryCallerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        (self.home / ".usix").mkdir()
        (self.home / ".usix" / "companion_token").write_text("a" * 64)
        self.calls = []
        self.failure = None

    def call(self, action, body):
        def open_request(req, timeout):
            self.calls.append(req)
            if self.failure:
                raise self.failure
            return io.BytesIO(json.dumps({"ok": True, "records": [], "conversationComplete": False}).encode())

        class Opener:
            open = staticmethod(open_request)

        output = io.StringIO()
        with patch.object(http.Path, "home", return_value=self.home), patch.object(http.request, "build_opener", return_value=Opener()), \
                patch.object(sys, "argv", ["companion_http.py", action, json.dumps(body)]), contextlib.redirect_stdout(output):
            code = http.main()
        self.assertNotIn("a" * 64, output.getvalue())
        return code, json.loads(output.getvalue())

    def test_history_filters_use_authenticated_loopback_get_without_launch(self):
        body = {"package": "com.kakao.talk", "query": "회의 & 점심", "since": 1000, "until": 2000,
                "limit": 10, "before": "b" * 64}
        code, result = self.call("notifications-history", body)
        self.assertEqual(code, 0)
        self.assertFalse(result["conversationComplete"])
        self.assertEqual(len(self.calls), 1)
        request = self.calls[0]
        parsed = parse.urlsplit(request.full_url)
        self.assertEqual((parsed.hostname, parsed.port, parsed.path), ("127.0.0.1", 8760, "/notifications/history"))
        self.assertEqual(parse.parse_qs(parsed.query)["query"], [body["query"]])
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + "a" * 64)

    def test_invalid_scopes_numbers_cursors_and_unknown_fields_never_send_requests(self):
        base = {"package": "com.kakao.talk"}
        for invalid in ({}, {"package": "other.app"}, {**base, "since": True}, {**base, "until": 1.5},
                        {**base, "since": -1}, {**base, "since": 2, "until": 1}, {**base, "limit": 101},
                        {**base, "before": "guess"}, {**base, "query": "x" * 257}, {**base, "open": True}):
            with self.subTest(invalid=invalid):
                code, result = self.call("notifications-history", invalid)
                self.assertEqual(code, 1)
                self.assertEqual(result["error"], "InvalidArguments")
        self.assertEqual(self.calls, [])

    def test_unsupported_history_endpoint_stops_without_open_fallback(self):
        self.failure = error.HTTPError("http://127.0.0.1:8760/notifications/history", 404, "not found", {},
                                       io.BytesIO(b'{"ok":false,"error":"not found"}'))
        code, result = self.call("notifications-history", {"package": "com.kakao.talk"})
        self.assertEqual(code, 1)
        self.assertEqual(result["http_status"], 404)
        self.assertEqual(result["reason"], "not found")
        self.assertEqual(len(self.calls), 1)

    def test_explicit_clear_is_one_post_without_retries(self):
        code, _ = self.call("notifications-history-clear", {"package": "com.kakao.talk"})
        self.assertEqual(code, 0)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0].get_method(), "POST")
        self.assertEqual(parse.urlsplit(self.calls[0].full_url).path, "/notifications/history/clear")


if __name__ == "__main__":
    unittest.main()
