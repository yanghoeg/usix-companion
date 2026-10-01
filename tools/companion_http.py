#!/usr/bin/env python3
"""Call the existing Companion HTTP bridge without exposing its bearer token."""

import argparse
import json
import re
import sys
from pathlib import Path
from urllib import error, parse, request


ROUTES = {
    "health": ("GET", "/health"),
    "notifications": ("GET", "/notifications"),
    "screen": ("GET", "/screen"),
    "open": ("POST", "/open"),
    "tap": ("POST", "/tap"),
    "type": ("POST", "/type"),
    "back": ("POST", "/back"),
    "scroll": ("POST", "/scroll"),
    "email-open": ("POST", "/email/open"),
    "email-compose": ("POST", "/email/compose"),
    "reply": ("POST", "/reply"),
}
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_REQUEST_BYTES = 64 * 1024


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def output(value):
    print(json.dumps(value, ensure_ascii=False))


def fail(code, message, **fields):
    output({"ok": False, "error": code, "message": message, **fields})
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=ROUTES)
    parser.add_argument("body", nargs="?", default="{}", help="JSON object; screen accepts package")
    parser.add_argument("--port", type=int, default=8760, help="local loopback port (default: 8760)")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        return fail("InvalidPort", "port must be between 1 and 65535")
    try:
        body = json.loads(args.body)
    except json.JSONDecodeError:
        return fail("InvalidArguments", "body must be a JSON object")
    if not isinstance(body, dict):
        return fail("InvalidArguments", "body must be a JSON object")

    method, path = ROUTES[args.action]
    if method == "GET":
        allowed = {"package"} if args.action == "screen" else set()
        if body.keys() - allowed:
            return fail("InvalidArguments", "unsupported query fields")
        if "package" in body:
            package = body["package"]
            if not isinstance(package, str) or not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package):
                return fail("InvalidArguments", "package must be an Android package name")
            path += "?" + parse.urlencode({"package": package})
        data = None
    else:
        try:
            data = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
        except ValueError:
            return fail("InvalidArguments", "body contains an invalid JSON number")
        if len(data) > MAX_REQUEST_BYTES:
            return fail("RequestTooLarge", "body exceeds the bridge's 64 KiB limit")

    token_path = Path.home() / ".usix" / "companion_token"
    try:
        token = token_path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        token = ""
    except (OSError, UnicodeError):
        return fail("TokenUnavailable", "cannot read ~/.usix/companion_token")
    if token and not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", token):
        return fail("TokenUnavailable", "invalid pairing token; pair again in the user terminal")
    if not token and args.action != "health":
        return fail("PairingRequired", "pair with the Companion app before this operation")

    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = request.Request(f"http://127.0.0.1:{args.port}{path}", data=data, headers=headers, method=method)
    # Keep credentials on loopback even when the caller has proxy environment settings.
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=10) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except error.HTTPError as exc:
        return fail("HttpError", "bridge rejected the request; check pairing, permissions and arguments", http_status=exc.code)
    except (error.URLError, OSError, TimeoutError):
        # Never retry: a POST may already have produced an external effect.
        return fail("TransportError", "bridge unavailable or response lost; inspect before retrying a mutation", effect="unknown" if method == "POST" else "none")
    if len(raw) > MAX_RESPONSE_BYTES:
        return fail("ResponseTooLarge", "bridge response exceeds 1 MiB", effect="unknown" if method == "POST" else "none")
    try:
        result = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return fail("InvalidResponse", "bridge did not return valid JSON", effect="unknown" if method == "POST" else "none")
    if not isinstance(result, (dict, list)):
        return fail("InvalidResponse", "bridge did not return a JSON object or list", effect="unknown" if method == "POST" else "none")
    output(result)
    return 1 if isinstance(result, dict) and result.get("ok") is False else 0


if __name__ == "__main__":
    sys.exit(main())
