"""Read-only mail retrieval, truthful failures, MIME handling and credential privacy."""

import contextlib
from email.message import EmailMessage
from email import policy
import imaplib
import io
import json
import quopri
from pathlib import Path
import socketserver
import ssl
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import companion_mail as mail


CONFIG = {"email": "person@example.test", "host": "imap.example.test", "password": "PRIVATE_IMAP_SECRET"}


def message(subject="테스트 메일", text="안녕하세요. 메일 본문입니다."):
    value = EmailMessage(policy=policy.SMTP)
    value["From"] = "발신자 <sender@example.test>"
    value["To"] = CONFIG["email"]
    value["Subject"] = subject
    value.set_content(text)
    return value


class ImapFixture:
    def __init__(self, messages=None):
        self.messages = messages if messages is not None else {"1": message().as_bytes(), "7": message().as_bytes()}
        self.calls = []
        self.validity = b"123"
        self.login_error = None
        self.fetch_status = "OK"
        self.reported_size = None
        self.body_structure = None
        self.sections = {}
        self.unavailable = set()

    def login(self, email, password):
        self.calls.append(("login", email))
        if self.login_error:
            raise self.login_error
        return "OK", [b"authenticated"]

    def logout(self):
        self.calls.append(("logout",))
        return "BYE", []

    def select(self, mailbox, readonly=False):
        self.calls.append(("select", mailbox, readonly))
        return "OK", [str(len(self.messages)).encode()]

    def response(self, code):
        return code, [self.validity]

    def uid(self, operation, *args):
        self.calls.append((operation, *args))
        if operation == "search":
            return "OK", [" ".join(self.messages).encode()]
        if operation != "fetch":
            raise AssertionError("unexpected IMAP operation")
        if self.fetch_status != "OK":
            return self.fetch_status, [b"rejected"]
        if args[1] in ("(UID RFC822.SIZE)", "(UID BODYSTRUCTURE)"):
            uid = args[0]
            if uid not in self.messages or uid in self.unavailable:
                return "OK", [None]
            attribute = f"RFC822.SIZE {self.reported_size or len(self.messages[uid])}".encode() if "SIZE" in args[1] else b"BODYSTRUCTURE " + self.body_structure
            return "OK", [b"1 (UID " + uid.encode() + b" " + attribute + b")"]
        result = [b"99 (FLAGS (\\Seen))"]  # Unsolicited flag update, no literal.
        for uid in reversed(args[0].split(",")):
            raw = self.messages.get(uid)
            if raw is None or uid in self.unavailable:
                continue
            header_only = "HEADER.FIELDS" in args[1]
            payload = raw.split(b"\r\n\r\n")[0] + b"\r\n\r\n" if header_only else raw
            body = b"BODY[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)]" if header_only else b"BODY[]<0>"
            for section, section_payload in self.sections.items():
                if f"BODY.PEEK[{section}]" in args[1]:
                    payload = section_payload
                    body = f"BODY[{section}]<0>".encode()
            flags = "\\Seen" if uid == "1" else ""
            # Servers can put UID/FLAGS/SIZE after the literal, not just before it.
            result += [(b"1 (" + body + b" {" + str(len(payload)).encode() + b"}", payload),
                       f" UID {uid} FLAGS ({flags}) RFC822.SIZE {self.reported_size or len(raw)})".encode()]
        return "OK", result


class CompanionMailTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / ".usix/mail_account.json"
        self.path_patch = patch.object(mail, "config_path", return_value=self.path)
        self.path_patch.start()
        self.addCleanup(self.path_patch.stop)

    def run_main(self, argv, client=None):
        stream = io.StringIO()
        with patch.object(mail.imaplib, "IMAP4_SSL", return_value=client) as factory, contextlib.redirect_stdout(stream):
            status = mail.main(argv)
        return status, json.loads(stream.getvalue()), factory

    def test_unconfigured_mail_does_not_turn_into_empty_inbox_or_open_app(self):
        status, value, factory = self.run_main(["inbox"])
        self.assertEqual((1, "SetupRequired"), (status, value["error"]))
        self.assertNotIn("messages", value)
        factory.assert_not_called()
        status, value, factory = self.run_main(["status"])
        self.assertEqual(0, status)
        self.assertFalse(value["configured"])
        self.assertFalse(value["authenticated"])
        factory.assert_not_called()

    def test_private_configuration_never_overwrites_or_exposes_credentials(self):
        mail.save_config(CONFIG)
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        with self.assertRaisesRegex(mail.MailError, "already exists"):
            mail.save_config({**CONFIG, "password": "REPLACEMENT"})
        status, value, factory = self.run_main(["status"])
        self.assertEqual(0, status)
        self.assertNotIn(CONFIG["password"], json.dumps(value))
        self.assertFalse(value["authenticated"])
        factory.assert_not_called()
        self.path.chmod(0o644)
        status, value, _ = self.run_main(["check"])
        self.assertEqual((1, "PrivateConfigurationRequired"), (status, value["error"]))

    def test_inbox_pagination_and_unread_flags_use_uid_and_peek(self):
        mail.save_config(CONFIG)
        client = ImapFixture()
        status, value, factory = self.run_main(["inbox", '{"limit":1}'], client)
        self.assertEqual(0, status)
        self.assertEqual("7", value["messages"][0]["uid"])
        self.assertEqual("테스트 메일", value["messages"][0]["subject"])
        self.assertTrue(value["messages"][0]["unread"])
        self.assertEqual("7", value["next_before_uid"])
        self.assertTrue(value["has_more"])
        self.assertIn(("select", "INBOX", True), client.calls)
        self.assertIn("BODY.PEEK[HEADER.FIELDS", client.calls[-2][-1])
        tls = factory.call_args.kwargs["ssl_context"]
        self.assertTrue(tls.check_hostname)
        self.assertEqual(ssl.CERT_REQUIRED, tls.verify_mode)
        self.assertEqual(15, factory.call_args.kwargs["timeout"])
        status, value, _ = self.run_main(["inbox", '{"before_uid":"7","unread":true}'], client)
        self.assertEqual(0, status)
        self.assertEqual(["1"], [m["uid"] for m in value["messages"]])
        self.assertFalse(value["messages"][0]["unread"])
        self.assertIn(("search", None, "UNSEEN", "UID", "1:6"), client.calls)

    def test_mime_body_decodes_korean_prefers_plain_and_ignores_attachment_body(self):
        value = message()
        value.add_alternative("<p>duplicate HTML</p>", subtype="html")
        value.add_attachment(b"PRIVATE_ATTACHMENT_BODY", maintype="application", subtype="pdf", filename="보고서.pdf")
        raw = value.as_bytes()
        client = ImapFixture({"7": raw})
        mail.save_config(CONFIG)
        status, result, _ = self.run_main(["read", '{"uid":"7","uidvalidity":"123"}'], client)
        self.assertEqual(0, status)
        self.assertIn("안녕하세요. 메일 본문입니다.", result["body"])
        self.assertNotIn("duplicate HTML", result["body"])
        self.assertNotIn("PRIVATE_ATTACHMENT_BODY", json.dumps(result))
        self.assertEqual("보고서.pdf", result["attachments"][0]["filename"])
        self.assertTrue(result["body_available"])
        self.assertIn("BODY.PEEK[]<0.", client.calls[-2][-1])
        self.assertIn(("select", "INBOX", True), client.calls)

    def test_html_body_excludes_script_style_and_remote_resources(self):
        value = message()
        value.set_content('<head><style>hidden CSS</style></head><p>한글 &amp; 내용</p><script>hidden JS</script><img src="https://example.test/track">', subtype="html")
        result = mail.message_content(value.as_bytes())
        self.assertEqual("한글 & 내용", result["body"])
        self.assertNotIn("hidden", result["body"])
        self.assertNotIn("track", result["body"])

    def test_legacy_charset_and_unsupported_body_are_reported_truthfully(self):
        value = message()
        value.set_content("한글 레거시 본문", charset="euc-kr")
        self.assertIn("한글 레거시 본문", mail.message_content(value.as_bytes())["body"])
        value.set_content(b"encrypted bytes", maintype="application", subtype="pkcs7-mime")
        self.assertFalse(mail.message_content(value.as_bytes())["body_available"])

    def test_changed_uidvalidity_prevents_reading_a_different_message(self):
        client = ImapFixture()
        with self.assertRaisesRegex(mail.MailError, "validity changed"):
            mail.read_message(client, {"uid": "7", "uidvalidity": "999"})
        self.assertFalse(any(call[0] == "fetch" for call in client.calls))

    def test_missing_oversized_and_incomplete_bodies_never_report_read_success(self):
        client = ImapFixture()
        with self.assertRaises(mail.MailError) as error:
            mail.read_message(client, {"uid": "99", "uidvalidity": "123"})
        self.assertEqual("MessageUnavailable", error.exception.code)
        client.body_structure = f'("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "8BIT" {mail.MAX_MESSAGE_BYTES + 1} 1 NIL NIL)'.encode()
        for size, code in ((mail.MAX_MESSAGE_BYTES + 1, "MessageTooLarge"), (1, "IncompleteMessage")):
            client.reported_size = size
            with self.assertRaises(mail.MailError) as error:
                mail.read_message(client, {"uid": "7", "uidvalidity": "123"})
            self.assertEqual(code, error.exception.code)

    def test_disappeared_search_record_keeps_readable_headers_and_marks_partial_result(self):
        client = ImapFixture()
        client.unavailable.add("1")
        result = mail.inbox(client, {"limit": 20})
        self.assertEqual(["7"], [m["uid"] for m in result["messages"]])
        self.assertEqual(["1"], result["unavailable_uids"])
        self.assertFalse(result["complete"])
        self.assertEqual(2, result["matching_messages"])

    def test_large_attachments_do_not_block_body_and_are_never_downloaded(self):
        client = ImapFixture({"7": message().as_bytes()})
        text = quopri.encodestring("<p>큰 첨부가 있어도 본문 전체를 읽습니다.</p>".encode())
        client.reported_size = 20 * 1024 * 1024
        client.body_structure = (
            f'((("text" "html" ("charset" "UTF-8") NIL NIL "quoted-printable" {len(text)} 1 NIL NIL NIL NIL)'
            '("image" "png" NIL NIL NIL "base64" 1000 NIL ("inline") NIL NIL) "related" NIL NIL NIL NIL)'
            '("application" "pdf" ("name" "report.pdf") NIL NIL "base64" 20000000 NIL '
            '("attachment" ("filename" "report.pdf")) NIL NIL) "mixed" NIL NIL NIL NIL)'
        ).encode()
        client.sections = {"1.1": text}
        result = mail.read_message(client, {"uid": "7", "uidvalidity": "123"})
        self.assertEqual("큰 첨부가 있어도 본문 전체를 읽습니다.", result["body"])
        self.assertEqual([{"filename": "report.pdf", "content_type": "application/pdf"}], result["attachments"])
        fetches = [call[-1] for call in client.calls if call[0] == "fetch"]
        self.assertTrue(any("BODY.PEEK[1.1]" in fields for fields in fetches))
        self.assertFalse(any("BODY.PEEK[]" in fields or "BODY.PEEK[2]" in fields or "BODY.PEEK[1.2]" in fields for fields in fetches))

    def test_mime_structure_literals_and_alternatives_do_not_duplicate_body(self):
        raw = b'("TEXT" "PLAIN" ("CHARSET" {5}\r\nutf-8) NIL NIL "8BIT" 10 1 NIL NIL)'
        plain, end = mail.imap_value(raw, 0)
        self.assertEqual(len(raw), end)
        html, _ = mail.imap_value(raw.replace(b'"PLAIN"', b'"HTML"'), 0)
        plans, _ = mail.body_sections([html, plain, b"alternative", None])
        self.assertEqual(["2"], [p["section"] for p in plans])
        with self.assertRaises(mail.MailError):
            mail.imap_value(b'("unterminated', 0)

    def test_authentication_and_fetch_failures_are_redacted_not_empty_mail(self):
        mail.save_config(CONFIG)
        client = ImapFixture()
        client.login_error = imaplib.IMAP4.error(CONFIG["password"])
        status, value, _ = self.run_main(["inbox"], client)
        self.assertEqual((1, "AuthenticationFailed"), (status, value["error"]))
        self.assertNotIn(CONFIG["password"], json.dumps(value))
        self.assertEqual(("logout",), client.calls[-1])
        client.login_error = None
        client.fetch_status = "NO"
        status, value, _ = self.run_main(["inbox"], client)
        self.assertEqual((1, "ImapRejected"), (status, value["error"]))
        self.assertNotIn("messages", value)

    def test_invalid_arguments_and_noninteractive_setup_never_connect(self):
        invalid = [("inbox", '{"limit":true}'), ("inbox", '{"unread":"yes"}'),
                   ("inbox", '{"before_uid":"7 ALL"}'), ("read", '{"uid":"7"}'),
                   ("read", '{"uid":"7:*","uidvalidity":"123"}'),
                   ("inbox", '{"password":"SECRET"}')]
        for action, body in invalid:
            with self.subTest(body=body):
                status, value, factory = self.run_main([action, body])
                self.assertEqual((1, "InvalidArguments"), (status, value["error"]))
                factory.assert_not_called()
        with patch.object(sys.stdin, "isatty", return_value=False):
            status, value, factory = self.run_main(["setup"])
        self.assertEqual((1, "InteractiveSetupRequired"), (status, value["error"]))
        factory.assert_not_called()

    def test_setup_verifies_account_before_saving_and_keeps_password_hidden(self):
        client = ImapFixture()
        with patch.object(sys.stdin, "isatty", return_value=True), patch.object(mail, "getpass", return_value=CONFIG["password"]):
            status, value, _ = self.run_main(["setup", "--email", CONFIG["email"], "--host", CONFIG["host"]], client)
        self.assertEqual(0, status)
        self.assertTrue(value["authenticated"])
        self.assertNotIn(CONFIG["password"], json.dumps(value))
        self.assertEqual(CONFIG, mail.load_config())
        self.assertIn(("select", "INBOX", True), client.calls)


class RealImapProtocolTest(unittest.TestCase):
    def test_standard_library_serializes_examine_peek_and_no_mutations(self):
        commands = []
        raw = message().as_bytes()

        class Server(socketserver.StreamRequestHandler):
            def handle(self):
                self.wfile.write(b"* OK synthetic IMAP server\r\n")
                while line := self.rfile.readline():
                    tag, command = line.rstrip().split(b" ", 1)
                    commands.append(command)
                    if command == b"CAPABILITY":
                        self.wfile.write(b"* CAPABILITY IMAP4rev1\r\n" + tag + b" OK capability\r\n")
                    elif command.startswith(b"LOGIN "):
                        self.wfile.write(tag + b" OK login\r\n")
                    elif command == b"EXAMINE INBOX":
                        self.wfile.write(b"* 1 EXISTS\r\n* OK [UIDVALIDITY 123] valid\r\n" + tag + b" OK [READ-ONLY] examined\r\n")
                    elif command == b"UID FETCH 7 (UID RFC822.SIZE)":
                        self.wfile.write(f"* 1 FETCH (UID 7 RFC822.SIZE {len(raw)})\r\n".encode() + tag + b" OK size\r\n")
                    elif command.startswith(b"UID FETCH 7 ") and b"BODY.PEEK[]" in command:
                        self.wfile.write(f"* 1 FETCH (UID 7 RFC822.SIZE {len(raw)} BODY[]<0> {{{len(raw)}}}\r\n".encode()
                                         + raw + b")\r\n" + tag + b" OK fetched\r\n")
                    elif command == b"LOGOUT":
                        self.wfile.write(b"* BYE logout\r\n" + tag + b" OK logout\r\n")
                        return
                    else:
                        self.wfile.write(tag + b" BAD unexpected command\r\n")

        # The synthetic loopback transport tests actual imaplib wire commands.
        # Production TLS verification is asserted separately above.
        with socketserver.TCPServer(("127.0.0.1", 0), Server) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                client = imaplib.IMAP4(*server.server_address, timeout=5)
                with patch.object(mail.imaplib, "IMAP4_SSL", return_value=client):
                    with mail.connection(CONFIG) as connected:
                        result = mail.read_message(connected, {"uid": "7", "uidvalidity": "123"})
                self.assertIn("메일 본문", result["body"])
            finally:
                server.shutdown()
                thread.join(timeout=5)
        self.assertIn(b"EXAMINE INBOX", commands)
        self.assertTrue(any(b"BODY.PEEK[]" in command for command in commands))
        self.assertFalse(any(command.split(b" ", 1)[0] in (b"SELECT", b"STORE", b"CLOSE", b"EXPUNGE", b"APPEND") for command in commands))


if __name__ == "__main__":
    unittest.main()
