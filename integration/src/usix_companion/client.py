"""Bounded, non-retrying local HTTP / owning-user broker IPC client."""
import os
import socket
import stat
import tempfile
from pathlib import Path
from urllib import error, parse, request
from . import CONTRACT
from .contract import loads, dumps


class IntegrationFailure(Exception):
    def __init__(self, code, message, effect="none", action_id=None):
        self.value = {"contractVersion": CONTRACT, "kind": "error", "error": {"code": code, "message": message},
                      "effect": effect, "retry": {"decision": "reconcile" if effect == "possible" else "never",
                      "reason": "Read the action receipt; no automatic effect retry" if effect == "possible" else "Refresh trusted setup"}}
        if action_id:
            self.value["actionId"] = action_id


def absolute(value):
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ValueError("An explicit absolute path is required")
    return path


def private_json(path):
    path = absolute(path)
    details = path.lstat()
    if not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid() or details.st_mode & 0o077:
        raise ValueError("Configuration must be an owning-user regular file with mode 0600")
    return loads(path.read_bytes(), 1048576)


def save_private(path, value):
    path = absolute(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".companion-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(dumps(value) + b"\n")
            output.flush(); os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def call(connection, path, bearer, body):
    if len(dumps(body)) > 65536 or not path.startswith("/v2/"):
        raise ValueError("Invalid device request or oversized body")
    effect = "possible" if path == "/v2/execute" else "none"
    try:
        if connection["transport"] == "loopback":
            endpoint = parse.urlsplit(connection["endpoint"])
            if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or endpoint.username or endpoint.password or endpoint.path or endpoint.query or endpoint.fragment:
                raise ValueError("Loopback connection must name only http://127.0.0.1:port")
            opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
            req = request.Request(connection["endpoint"] + path, data=dumps(body), method="POST",
                                  headers={"Authorization": "Bearer " + bearer, "Content-Type": "application/json"})
            try:
                with opener.open(req, timeout=35) as response:
                    raw = response.read(1048577)
            except error.HTTPError as response:
                raw = response.read(1048577)
        elif connection["transport"] == "broker":
            with socket.socket(socket.AF_UNIX) as stream:
                stream.settimeout(35)
                stream.connect(str(absolute(connection["socket"])))
                envelope = {"deviceId": connection["deviceId"], "path": path, "bearer": bearer, "body": body}
                stream.sendall(dumps(envelope) + b"\n")
                raw = bytearray()
                while not raw.endswith(b"\n"):
                    part = stream.recv(min(65536, 1048577 - len(raw)))
                    if not part:
                        break
                    raw.extend(part)
                    if len(raw) > 1048576:
                        break
        else:
            raise ValueError("Unsupported connection transport")
        result = loads(bytes(raw), 1048576)
        if not isinstance(result, dict) or result.get("contractVersion") != CONTRACT:
            raise ValueError("Unexpected device response")
        return result
    except (OSError, error.URLError, TimeoutError, UnicodeError):
        raise IntegrationFailure("UnknownEffect" if effect == "possible" else "Offline", "Device response unavailable; inspect the saved receipt before another effect", effect, body.get("actionId")) from None
    except ValueError:
        if effect == "possible":
            raise IntegrationFailure("UnknownEffect", "Effect response invalid; inspect the saved receipt", effect, body.get("actionId")) from None
        raise
