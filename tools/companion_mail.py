#!/usr/bin/env python3
"""Read an IMAP mailbox over verified TLS without opening an Android app."""

import argparse
import base64
from contextlib import contextmanager
from email import policy
from email.parser import BytesParser
from email.header import decode_header, make_header
from getpass import getpass
from html.parser import HTMLParser
import imaplib
import json
import os
import quopri
from pathlib import Path
import re
import ssl
import sys


MAX_MESSAGE_BYTES = 10 * 1024 * 1024
MAX_HEADER_BYTES = 64 * 1024
TIMEOUT_SECONDS = 15
HOSTS = {"gmail.com": "imap.gmail.com", "naver.com": "imap.naver.com",
         "bizmeka.com": "ezmail.bizmeka.com"}


class MailError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def config_path():
    return Path.home() / ".usix" / "mail_account.json"


def validate_config(value):
    if not isinstance(value, dict) or set(value) != {"host", "email", "password"}:
        raise MailError("InvalidConfiguration", "mail configuration must contain host, email and password")
    if not isinstance(value["host"], str) or not re.fullmatch(
            r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?", value["host"]):
        raise MailError("InvalidConfiguration", "a valid IMAP server hostname is required")
    if not isinstance(value["email"], str) or not re.fullmatch(r"[^\s@\x00-\x1f]+@[^\s@\x00-\x1f]+", value["email"]):
        raise MailError("InvalidConfiguration", "an email account address is required")
    if not isinstance(value["password"], str) or not value["password"] or any(
            c in value["password"] for c in "\r\n\x00"):
        raise MailError("InvalidConfiguration", "an IMAP password is required; enter it with setup")
    return value


def load_config():
    path = config_path()
    try:
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise MailError("PrivateConfigurationRequired", "mail_account.json must be a private file (mode 600)")
        return validate_config(json.loads(path.read_text(encoding="utf-8")))
    except FileNotFoundError:
        raise MailError("SetupRequired", "run companion_mail.py setup in your own Termux terminal") from None
    except (OSError, ValueError, UnicodeError):
        raise MailError("InvalidConfiguration", "cannot read the local mail configuration; run setup") from None


def save_config(value):
    validate_config(value)
    path = config_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # An existing account is never overwritten by an agent or a second setup.
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise MailError("AlreadyConfigured", "mail configuration already exists; preserve the existing account") from None
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.write("\n")


def require_ok(result, message):
    status, data = result
    if status != "OK":
        raise MailError("ImapRejected", message)
    return data


@contextmanager
def connection(config):
    client = None
    try:
        client = imaplib.IMAP4_SSL(config["host"], 993,
                                 ssl_context=ssl.create_default_context(), timeout=TIMEOUT_SECONDS)
        try:
            require_ok(client.login(config["email"], config["password"]), "IMAP authentication failed")
        except imaplib.IMAP4.error:
            raise MailError("AuthenticationFailed", "check the account's IMAP password and external-mail access") from None
        yield client
    finally:
        if client is not None:
            try:
                # CLOSE can expunge messages; LOGOUT ends the read-only session.
                client.logout()
            except (imaplib.IMAP4.error, OSError):
                pass


def select_inbox(client):
    data = require_ok(client.select("INBOX", readonly=True), "cannot open the inbox read-only")
    validity = client.response("UIDVALIDITY")[1]
    if not data or not isinstance(data[0], bytes) or not data[0].isdigit() or not validity:
        raise MailError("InvalidResponse", "the server did not identify the inbox")
    value = validity[0]
    if not isinstance(value, bytes) or not re.fullmatch(rb"[1-9][0-9]*", value):
        raise MailError("InvalidResponse", "the server did not identify the inbox UID validity")
    return int(data[0]), value.decode("ascii")


def identifier(value, name):
    if not isinstance(value, (str, int)) or isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]*", str(value)):
        raise MailError("InvalidArguments", name + " must be a positive numeric identifier")
    if int(value) > 4294967295:
        raise MailError("InvalidArguments", name + " exceeds the IMAP identifier range")
    return str(value)


def arguments(action, body):
    try:
        value = json.loads(body)
    except ValueError:
        raise MailError("InvalidArguments", "body must be a JSON object") from None
    allowed = {"inbox": {"limit", "unread", "before_uid"}, "read": {"uid", "uidvalidity"}}
    if not isinstance(value, dict) or value.keys() - allowed.get(action, set()):
        raise MailError("InvalidArguments", "unsupported arguments for this mail operation")
    if action == "inbox":
        limit = value.get("limit", 20)
        if type(limit) is not int or not 1 <= limit <= 100 or type(value.get("unread", False)) is not bool:
            raise MailError("InvalidArguments", "limit must be 1..100 and unread must be a boolean")
        if "before_uid" in value:
            value["before_uid"] = identifier(value["before_uid"], "before_uid")
    elif action == "read":
        for name in ("uid", "uidvalidity"):
            value[name] = identifier(value.get(name), name)
    return value


def literals(data, marker):
    """FETCH may include unsolicited records and attributes after the literal."""
    result = {}
    for index, item in enumerate(data or []):
        if not isinstance(item, tuple) or len(item) != 2 or not all(isinstance(p, bytes) for p in item):
            continue
        meta, raw = item
        if marker not in meta.upper():
            continue
        for tail in data[index + 1:]:
            if not isinstance(tail, bytes) or re.match(rb"\d+ \(", tail):
                break
            meta += b" " + tail
        uid = re.search(rb"\bUID ([1-9][0-9]*)\b", meta, re.IGNORECASE)
        if uid is None or uid[1].decode() in result:
            raise MailError("InvalidResponse", "a fetched message has no unique UID")
        result[uid[1].decode()] = (meta, raw)
    return result


def imap_value(raw, offset, depth=0):
    """Parse BODYSTRUCTURE's bounded IMAP lists, quoted strings and literals."""
    if depth > 64:
        raise MailError("InvalidResponse", "MIME structure is nested too deeply")
    while offset < len(raw) and raw[offset] in b" \t\r\n":
        offset += 1
    if offset >= len(raw):
        raise MailError("InvalidResponse", "incomplete MIME structure")
    if raw[offset] == ord("("):
        values = []
        offset += 1
        while True:
            while offset < len(raw) and raw[offset] in b" \t\r\n":
                offset += 1
            if offset >= len(raw):
                raise MailError("InvalidResponse", "incomplete MIME structure")
            if raw[offset] == ord(")"):
                return values, offset + 1
            value, offset = imap_value(raw, offset, depth + 1)
            values.append(value)
    if raw[offset] == ord('"'):
        value = bytearray()
        offset += 1
        while offset < len(raw):
            char = raw[offset]
            offset += 1
            if char == ord('"'):
                return bytes(value), offset
            if char == ord("\\"):
                if offset >= len(raw):
                    break
                char = raw[offset]
                offset += 1
            value.append(char)
        raise MailError("InvalidResponse", "incomplete quoted MIME parameter")
    if raw[offset] == ord("{"):
        literal = re.match(rb"\{(\d+)\+?\}\r\n", raw[offset:])
        if literal is None:
            raise MailError("InvalidResponse", "invalid MIME parameter literal")
        start = offset + literal.end()
        end = start + int(literal[1])
        if end > len(raw):
            raise MailError("InvalidResponse", "incomplete MIME parameter literal")
        return raw[start:end], end
    atom = re.match(rb"[^ ()\t\r\n]+", raw[offset:])
    if atom is None:
        raise MailError("InvalidResponse", "invalid MIME structure atom")
    value = atom[0]
    return (None if value.upper() == b"NIL" else int(value) if value.isdigit() else value), offset + atom.end()


def fetch_attributes(client, uid, fields):
    data = require_ok(client.uid("fetch", uid, fields), "cannot inspect the requested message")
    fragments = []
    for item in data or []:
        if isinstance(item, bytes):
            fragments.append(item)
        elif isinstance(item, tuple) and len(item) == 2 and all(isinstance(p, bytes) for p in item):
            fragments.append(item[0] + b"\r\n" + item[1])
    raw = b"\r\n".join(fragments)
    if len(raw) > MAX_HEADER_BYTES:
        raise MailError("InvalidResponse", "message metadata exceeds 64 KiB")
    consumed = 0
    for frame in re.finditer(rb"(?:^|\r\n)\d+ \(", raw):
        if frame.start() < consumed:
            continue
        values, consumed = imap_value(raw, frame.end() - 1)
        if len(values) % 2 or any(not isinstance(values[i], bytes) for i in range(0, len(values), 2)):
            raise MailError("InvalidResponse", "invalid message metadata")
        attributes = {values[i].upper(): values[i + 1] for i in range(0, len(values), 2)}
        if str(attributes.get(b"UID")) == uid:
            return attributes
    raise MailError("MessageUnavailable", "the requested inbox message is no longer available")


def mime_parameters(value):
    if not isinstance(value, list):
        return {}
    return {value[i].upper(): value[i + 1] for i in range(0, len(value) - 1, 2)
            if isinstance(value[i], bytes)}


def body_sections(node, section=""):
    if not isinstance(node, list) or not node:
        raise MailError("InvalidResponse", "invalid MIME body structure")
    if isinstance(node[0], list):
        children = 0
        while children < len(node) and isinstance(node[children], list):
            children += 1
        if children == len(node) or not isinstance(node[children], bytes):
            raise MailError("InvalidResponse", "multipart MIME subtype is missing")
        subtype = node[children].lower()
        plans, attachments = [], []
        for index, child in enumerate(node[:children], 1):
            child_plan, child_attachments = body_sections(child, f"{section}.{index}" if section else str(index))
            plans.append(child_plan)
            attachments.extend(child_attachments)
        if subtype == b"alternative":
            selected = next((p for p in plans if any(part["type"] == "text/plain" for part in p)), None)
            return selected or next((p for p in plans if p), []), attachments
        if subtype in (b"related", b"signed"):
            return plans[0], attachments
        if subtype == b"encrypted":
            return [], attachments
        return [part for plan in plans for part in plan], attachments
    if len(node) < 7 or not isinstance(node[0], bytes) or not isinstance(node[1], bytes):
        raise MailError("InvalidResponse", "incomplete MIME part metadata")
    major, minor = node[0].lower(), node[1].lower()
    params = mime_parameters(node[2])
    disposition_index = 9 if major == b"text" else 11 if (major, minor) == (b"message", b"rfc822") else 8
    disposition = node[disposition_index] if len(node) > disposition_index else None
    disposition_name = disposition[0].lower() if isinstance(disposition, list) and disposition and isinstance(disposition[0], bytes) else b""
    disposition_params = mime_parameters(disposition[1]) if isinstance(disposition, list) and len(disposition) > 1 else {}
    filename = disposition_params.get(b"FILENAME") or params.get(b"NAME")
    content_type = (major + b"/" + minor).decode("ascii", "replace")
    if filename or disposition_name == b"attachment":
        name = str(make_header(decode_header(filename.decode("utf-8", "replace")))) if isinstance(filename, bytes) else None
        return [], [{"filename": name, "content_type": content_type}]
    if major != b"text" or minor not in (b"plain", b"html"):
        return [], []
    if not isinstance(node[5], bytes) or type(node[6]) is not int or node[6] < 0:
        raise MailError("InvalidResponse", "invalid MIME body encoding or size")
    charset = params.get(b"CHARSET", b"utf-8")
    if not isinstance(charset, bytes):
        raise MailError("InvalidResponse", "invalid MIME body charset")
    return [{"section": section or "TEXT", "type": content_type, "charset": charset.decode("ascii", "replace"),
             "encoding": node[5].upper(), "size": node[6]}], []


def headers(message):
    return {field.lower().replace("-", "_"): str(message.get(field, ""))
            for field in ("From", "To", "Subject", "Date", "Message-ID")}


def inbox(client, value):
    count, validity = select_inbox(client)
    criteria = ["UNSEEN" if value.get("unread", False) else "ALL"]
    before = value.get("before_uid")
    if before and int(before) > 1:
        criteria += ["UID", "1:" + str(int(before) - 1)]
    data = require_ok(client.uid("search", None, *criteria), "cannot search the inbox")
    if not data or not isinstance(data[0], bytes):
        raise MailError("InvalidResponse", "the server did not return inbox search results")
    uids = [identifier(p.decode("ascii"), "server UID") for p in data[0].split()]
    uids = sorted(set(uids), key=int, reverse=True)
    if before:
        uids = [uid for uid in uids if int(uid) < int(before)]
    selected = uids[:value.get("limit", 20)]
    messages, unavailable = [], []
    if selected:
        fields = f"(UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)]<0.{MAX_HEADER_BYTES + 1}>)"
        data = require_ok(client.uid("fetch", ",".join(selected), fields), "cannot fetch inbox headers")
        records = literals(data, b"BODY[HEADER.FIELDS")
        for uid in selected:
            if uid not in records:
                unavailable.append(uid)
                continue
            meta, raw = records[uid]
            if len(raw) > MAX_HEADER_BYTES:
                raise MailError("HeaderTooLarge", "a message header exceeds 64 KiB")
            flags = re.search(rb"\bFLAGS \(([^)]*)\)", meta, re.IGNORECASE)
            messages.append({"uid": uid, **headers(BytesParser(policy=policy.default).parsebytes(raw)),
                             "unread": b"\\SEEN" not in flags[1].upper().split() if flags else None})
    more = len(uids) > len(selected)
    return {"mailbox": "INBOX", "uidvalidity": validity, "mailbox_messages": count,
            "matching_messages": len(uids), "messages": messages, "has_more": more,
            "next_before_uid": selected[-1] if more else None,
            "complete": not unavailable, "unavailable_uids": unavailable}


class HtmlText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "head"):
            self.hidden += 1
        if tag in ("br", "p", "div", "tr", "li") and not self.hidden:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "head"):
            self.hidden = max(0, self.hidden - 1)
        if tag in ("p", "div", "tr", "li") and not self.hidden:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def message_content(raw):
    message = BytesParser(policy=policy.default).parsebytes(raw)
    body = message.get_body(preferencelist=("plain", "html"))
    text = ""
    if body is not None:
        payload = body.get_payload(decode=True) or b""
        try:
            text = payload.decode(body.get_content_charset() or "utf-8", errors="replace")
        except LookupError:
            text = payload.decode("utf-8", errors="replace")
        if body.get_content_type() == "text/html":
            parser = HtmlText()
            parser.feed(text)
            text = "".join(parser.parts).strip()
    attachments = [{"filename": part.get_filename(), "content_type": part.get_content_type()}
                   for part in message.walk() if part.is_attachment() or part.get_filename()]
    return {**headers(message), "body": text, "body_available": body is not None,
            "body_content_type": body.get_content_type() if body is not None else None,
            "attachments": attachments}


def read_large_message(client, uid):
    attributes = fetch_attributes(client, uid, "(UID BODYSTRUCTURE)")
    plans, attachments = body_sections(attributes.get(b"BODYSTRUCTURE"))
    if sum(part["size"] for part in plans) > MAX_MESSAGE_BYTES:
        raise MailError("MessageTooLarge", "message text exceeds the 10 MiB body read limit")
    fields = f"(UID BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)]<0.{MAX_HEADER_BYTES + 1}>)"
    data = require_ok(client.uid("fetch", uid, fields), "cannot fetch message headers")
    record = literals(data, b"BODY[HEADER.FIELDS").get(uid)
    if record is None:
        raise MailError("MessageUnavailable", "the requested message headers are no longer available")
    if len(record[1]) > MAX_HEADER_BYTES:
        raise MailError("HeaderTooLarge", "message headers exceed 64 KiB")
    result = headers(BytesParser(policy=policy.default).parsebytes(record[1]))
    texts = []
    for part in plans:
        section = part["section"]
        fields = f"(UID BODY.PEEK[{section}]<0.{part['size'] + 1}>)"
        data = require_ok(client.uid("fetch", uid, fields), "cannot fetch the message body section")
        record = literals(data, f"BODY[{section}]".encode()).get(uid)
        if record is None or len(record[1]) != part["size"]:
            raise MailError("IncompleteMessage", "the server did not return a complete message body section")
        raw = record[1]
        if part["encoding"] == b"BASE64":
            raw = base64.b64decode(raw)
        elif part["encoding"] == b"QUOTED-PRINTABLE":
            raw = quopri.decodestring(raw)
        elif part["encoding"] not in (b"7BIT", b"8BIT", b"BINARY"):
            raise MailError("UnsupportedMessage", "unsupported MIME body transfer encoding")
        try:
            text = raw.decode(part["charset"], errors="replace")
        except LookupError:
            text = raw.decode("utf-8", errors="replace")
        if part["type"] == "text/html":
            parser = HtmlText()
            parser.feed(text)
            text = "".join(parser.parts).strip()
        texts.append(text)
    return {**result, "body": "\n".join(texts), "body_available": bool(plans), "attachments": attachments,
            "body_content_type": plans[0]["type"] if len(plans) == 1 else "multipart/mixed" if plans else None}


def read_message(client, value):
    _, validity = select_inbox(client)
    if validity != value["uidvalidity"]:
        raise MailError("MailboxChanged", "inbox UID validity changed; list messages again before reading")
    attributes = fetch_attributes(client, value["uid"], "(UID RFC822.SIZE)")
    size = attributes.get(b"RFC822.SIZE")
    if type(size) is not int or size < 0:
        raise MailError("InvalidResponse", "the server did not report the message size")
    if size > MAX_MESSAGE_BYTES:
        return {"mailbox": "INBOX", "uid": value["uid"], "uidvalidity": validity,
                **read_large_message(client, value["uid"])}
    fields = f"(UID RFC822.SIZE BODY.PEEK[]<0.{MAX_MESSAGE_BYTES + 1}>)"
    data = require_ok(client.uid("fetch", value["uid"], fields), "cannot fetch the requested message")
    records = literals(data, b"BODY[]")
    if value["uid"] not in records:
        raise MailError("MessageUnavailable", "the requested inbox message is no longer available")
    meta, raw = records[value["uid"]]
    size = re.search(rb"\bRFC822.SIZE (\d+)\b", meta, re.IGNORECASE)
    if len(raw) > MAX_MESSAGE_BYTES or size and int(size[1]) > MAX_MESSAGE_BYTES:
        raise MailError("MessageTooLarge", "message including attachments exceeds the 10 MiB read limit")
    if size is None or int(size[1]) != len(raw):
        raise MailError("IncompleteMessage", "the server did not return the complete message")
    return {"mailbox": "INBOX", "uid": value["uid"], "uidvalidity": validity, **message_content(raw)}


def setup(args):
    if not sys.stdin.isatty():
        raise MailError("InteractiveSetupRequired", "run setup in your own Termux terminal to enter the password privately")
    if config_path().exists() or config_path().is_symlink():
        raise MailError("AlreadyConfigured", "mail configuration already exists; preserve the existing account")
    email = args.email or input("메일 주소: ").strip()
    host = args.host or HOSTS.get(email.rsplit("@", 1)[-1].lower()) or input("IMAP 서버: ").strip()
    config = validate_config({"email": email, "host": host, "password": getpass("IMAP 전용 비밀번호 (화면에 표시되지 않음): ")})
    with connection(config) as client:
        count, _ = select_inbox(client)
    save_config(config)
    return {"configured": True, "authenticated": True, "account": email, "mailbox_messages": count}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("setup", "status", "check", "inbox", "read"))
    parser.add_argument("body", nargs="?", default="{}", help="JSON object; never include credentials")
    parser.add_argument("--email", help="account address for interactive setup")
    parser.add_argument("--host", help="verified IMAP server hostname for interactive setup (TLS port 993)")
    args = parser.parse_args(argv)
    try:
        value = arguments(args.action, args.body)
        if args.action != "setup" and (args.email or args.host):
            raise MailError("InvalidArguments", "email and host options are only used for setup")
        if args.action == "setup":
            result = setup(args)
        else:
            try:
                config = load_config()
            except MailError as error:
                if args.action != "status" or error.code != "SetupRequired":
                    raise
                config = None
            if args.action == "status":
                result = {"configured": config is not None, "authenticated": False}
                if config:
                    result.update(account=config["email"], host=config["host"])
            else:
                with connection(config) as client:
                    if args.action == "check":
                        count, validity = select_inbox(client)
                        result = {"authenticated": True, "mailbox": "INBOX", "uidvalidity": validity,
                                  "mailbox_messages": count}
                    elif args.action == "inbox":
                        result = inbox(client, value)
                    else:
                        result = read_message(client, value)
                result["account"] = config["email"]
        print(json.dumps({"ok": True, "source": "imap", "read_only": True, **result}, ensure_ascii=False))
        return 0
    except MailError as error:
        code, message = error.code, str(error)
    except ssl.SSLCertVerificationError:
        code, message = "TlsVerificationFailed", "IMAP server certificate verification failed"
    except (OSError, imaplib.IMAP4.abort):
        code, message = "ConnectionFailed", "IMAP connection failed or timed out; no mailbox result was obtained"
    except imaplib.IMAP4.error:
        code, message = "ImapRejected", "IMAP request rejected; check external-mail access and account settings"
    except (ValueError, UnicodeError):
        code, message = "InvalidResponse", "cannot decode the mail server response"
    except (EOFError, KeyboardInterrupt):
        code, message = "SetupCancelled", "interactive setup was cancelled"
    # Never print raw server exceptions: they may echo credentials or mail data.
    print(json.dumps({"ok": False, "error": code, "message": message}, ensure_ascii=False))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
