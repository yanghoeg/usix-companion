"""Offline v2 conformance oracle. This does not authorize or dispatch live actions."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "contracts" / "device" / "v2"
MAX_BYTES = 65536
MAX_INTEGER = 9007199254740991


def _reject_number(value):
    raise ValueError(f"unsupported JSON number: {value}")


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _check_values(value, depth=0):
    if depth > 32:
        raise ValueError("JSON nesting exceeds 32")
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
    elif isinstance(value, bool) or value is None:
        return
    elif isinstance(value, int):
        if abs(value) > MAX_INTEGER:
            raise ValueError("integer outside interoperable range")
    elif isinstance(value, list):
        for item in value:
            _check_values(item, depth + 1)
    elif isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("JSON keys must be strings")
            _check_values(key, depth + 1)
            _check_values(item, depth + 1)
    else:
        raise ValueError("only integers, not floating point numbers, are supported")


def load_json(raw: bytes):
    if len(raw) > MAX_BYTES:
        raise ValueError("JSON body exceeds 64 KiB")
    value = json.loads(raw.decode("utf-8", errors="strict"),
                       object_pairs_hook=_unique_object,
                       parse_float=_reject_number, parse_constant=_reject_number)
    _check_values(value)
    return value


def payload_hash(command):
    # Context is separately immutable in the action journal and authority record.
    value = {key: command[key] for key in ("operation", "scope", "payload")}
    _check_values(value)
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True,
                     separators=(",", ":"), allow_nan=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(raw).hexdigest()


class Contracts:
    def __init__(self):
        self.schemas = {p.name.removesuffix(".schema.json"): load_json(p.read_bytes())
                        for p in sorted(SCHEMAS.glob("*.schema.json"))}
        if len(self.schemas) != 9:
            raise ValueError("expected nine v2 schemas")
        for schema in self.schemas.values():
            Draft202012Validator.check_schema(schema)
        registry = Registry().with_resources(
            (s["$id"], Resource.from_contents(s)) for s in self.schemas.values())
        formats = FormatChecker()

        @formats.checks("date-time", raises=(ValueError, TypeError))
        def valid_timestamp(value):
            if not isinstance(value, str):
                return True  # The schema's type constraint handles this case.
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return parsed.tzinfo is not None

        self.validators = {
            name: Draft202012Validator(schema, registry=registry,
                                      format_checker=formats)
            for name, schema in self.schemas.items()
        }

    def errors(self, name, value):
        try:
            _check_values(value)
        except (ValueError, UnicodeError) as error:
            return [str(error)]
        return [f"{'/'.join(map(str, e.absolute_path)) or '$'}: {e.message}"
                for e in self.validators[name].iter_errors(value)]

    def command_issues(self, command, state):
        """Reference expectations with injected, trusted fixture state and time."""
        if self.errors("command", command):
            return ["InvalidRequest"]
        issues = []
        if command["payloadHash"] != payload_hash(command):
            issues.append("InvalidRequest")
        if command["context"] != state["context"]:
            issues.append("IdentityMismatch")
        now = _time(state["now"])
        if _time(command["deadline"]) <= now:
            issues.append("DeadlineExceeded")
        if command["cancellationId"] in state.get("cancelled", []):
            issues.append("Cancelled")
        capabilities = state["capabilities"]
        capability = next((c for c in capabilities
                           if c["operation"] == command["operation"]), None)
        if capability is None or not capability["supported"]:
            issues.append("UnsupportedCapability")
            return sorted(set(issues))
        if capability["readiness"] != "ready":
            issues.append({
                "permission_required": "PermissionRequired",
                "accessibility_disconnected": "AccessibilityDisconnected",
                "device_locked": "DeviceLocked", "app_missing": "AppMissing",
                "offline": "Offline", "busy": "Busy",
            }[capability["readiness"]])
        scope = command["scope"]
        if capability["requiresAccount"] and scope["accountRef"] is None:
            issues.append("InvalidRequest")
        if capability["requiresSnapshot"]:
            snapshot = state.get("snapshot")
            if (not snapshot or scope["snapshotRef"] != snapshot["snapshotRef"]
                    or scope["packageId"] != snapshot["packageId"]
                    or _time(snapshot["expiresAt"]) <= now):
                issues.append("StaleSnapshot")
        if capability["requiresController"] or command["controllerLease"] is not None:
            lease = state.get("lease")
            if (not lease or command["controllerLease"] != {
                    "leaseId": lease["leaseId"], "revision": lease["revision"]}
                    or lease["runtimeId"] != command["context"]["runtimeId"]
                    or _time(lease["expiresAt"]) <= now):
                issues.append("ControllerConflict")
        for old in state.get("actions", []):
            if old["actionId"] == command["actionId"] and any(
                    old[key] != command[key] for key in
                    ("context", "operation", "scope", "payloadHash")):
                issues.append("ActionConflict")
        required = capability["authority"]
        authority = command["authority"]
        if required != "none":
            record = state.get("authorities", {}).get(authority["ref"])
            # A routine grant never substitutes for an exact consequential approval.
            if (record is None or authority["kind"] != required
                    or record["kind"] != required or record["authorityId"] != authority["ref"]):
                issues.append("ApprovalRequired")
            elif self.errors("authority", record):
                issues.append("InvalidRequest")
            else:
                if record["context"] != command["context"]:
                    issues.append("IdentityMismatch")
                if (_time(record["expiresAt"]) <= now
                        or _time(record["issuedAt"]) > now or record["status"] == "expired"):
                    issues.append("AuthorityExpired")
                if record["status"] == "revoked":
                    issues.append("AuthorityRevoked")
                if record["status"] not in ("active", "approved", "expired", "revoked"):
                    issues.append("ApprovalRequired")
                if required == "approval":
                    if any(record[key] != command[key] for key in
                           ("operation", "actionId", "payloadHash", "scope")):
                        issues.append("ApprovalRequired")
                else:
                    if (command["operation"] not in record["operations"]
                            or scope["packageId"] != record["scope"]["packageId"]
                            or scope["accountRef"] != record["scope"]["accountRef"]
                            or not set(scope["resourceRefs"]).issubset(record["scope"]["resourceRefs"])
                            or state.get("actionsUsed", 0) >= record["maxActions"]):
                        issues.append("ApprovalRequired")
        return sorted(set(issues))


def _time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def transition_issues(old, new, contracts):
    """Journal transitions; transport receipt loss never grants retry permission."""
    if contracts.errors("receipt", old) or contracts.errors("receipt", new):
        return ["InvalidRequest"]
    if old == new:
        return []  # Identical duplicate returns the durable receipt without dispatch.
    if any(old[k] != new[k] for k in
           ("requestId", "receiptId", "context", "actionId", "payloadHash")):
        return ["ActionConflict"]
    allowed = {
        "Accepted": {"Executing", "Cancelled", "Failed"},
        "Executing": {"Dispatched", "UnknownEffect", "Failed", "Cancelled"},
        "Dispatched": {"Verified", "NeedsVerification"},
        "UnknownEffect": {"Verified", "NeedsVerification"},
        "NeedsVerification": {"Verified"},
        "Verified": set(), "Failed": set(), "Cancelled": set(),
    }
    if (new["revision"] <= old["revision"] or _time(new["updatedAt"]) < _time(old["updatedAt"])
            or new["state"] not in allowed[old["state"]]
            or (old["cancellationRequested"] and not new["cancellationRequested"])):
        return ["InvalidTransition"]
    return []
