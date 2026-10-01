#!/usr/bin/env python3
"""Controlled physical-device contract checks; only launch Companion itself.

Use the integration virtual environment. Trusted setup creates both private
profiles first. No private screen, notification, message or account is read.
Model invocation and process/reboot evidence are separate required gates.
"""
import argparse
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import socket
import time
from urllib.parse import urlsplit
import uuid

from usix_companion import CONTRACT
from usix_companion.cli import owner_token, packet
from usix_companion.client import absolute, call, private_json, save_private
from usix_companion.contract import Contracts, dumps, payload_hash, utc


def command(profile, lease, action_id=None):
    value = packet(kind="command", context=profile["context"], actionId=action_id or str(uuid.uuid4()),
                   operation="app.open", payload={}, scope={"packageId": "dev.usix.companion", "accountRef": None,
                   "resourceRefs": [], "snapshotRef": None}, controllerLease=lease, deadline=utc(int(time.time() * 1000) + 30_000),
                   cancellationId=None, authority={"kind": "grant", "ref": profile["grantRef"]})
    value["payloadHash"] = payload_hash(value)
    return value


def drop_response(profile, body):
    """Send once and deliberately read no acknowledgement; never replay here."""
    connection = profile["connection"]
    if connection["transport"] == "loopback":
        endpoint = urlsplit(connection["endpoint"])
        if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1":
            raise ValueError("Explicit loopback profile required")
        encoded = dumps(body)
        request = ("POST /v2/execute HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + profile["bearer"] +
                   "\r\nContent-Type: application/json\r\nContent-Length: " + str(len(encoded)) + "\r\nConnection: close\r\n\r\n").encode() + encoded
        stream = socket.create_connection((endpoint.hostname, endpoint.port or 80), timeout=5)
    elif connection["transport"] == "broker":
        request = dumps({"deviceId": connection["deviceId"], "path": "/v2/execute", "bearer": profile["bearer"], "body": body}) + b"\n"
        stream = socket.socket(socket.AF_UNIX); stream.settimeout(5); stream.connect(str(absolute(connection["socket"])))
    else:
        raise ValueError("Unsupported transport")
    with stream:
        stream.sendall(request)
        stream.shutdown(socket.SHUT_WR)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile-a", required=True, type=Path)
    parser.add_argument("--profile-b", required=True, type=Path)
    parser.add_argument("--owner-token-file", default=str(Path.home() / ".usix/companion_token"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = absolute(args.output)
    if output.exists():
        parser.error("fresh evidence path required")
    a, b = private_json(args.profile_a), private_json(args.profile_b)
    if any(p.get("packageId") != "dev.usix.companion" or not Path(p["workspace"]).is_dir() for p in (a, b)):
        parser.error("Both trusted profiles must select Companion itself and an existing workspace")
    if a["context"]["deviceId"] != b["context"]["deviceId"] or a["context"]["sessionId"] == b["context"]["sessionId"]:
        parser.error("Two distinct sessions on the same physical device required")
    contracts = Contracts()
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "source": "live Android APK through standalone integration",
              "transportA": a["connection"]["transport"], "transportB": b["connection"]["transport"], "steps": [],
              "modelEvidence": False, "processRestartOrRebootEvidence": False, "passed": False}

    def record(name, value, kind=None, error=None):
        report["steps"].append({"name": name, "response": value})
        if kind and value.get("kind") != kind:
            raise AssertionError(name + ": wrong response kind")
        if error and value.get("error", {}).get("code") != error:
            raise AssertionError(name + ": wrong rejection")
        if value.get("kind") in ("receipt", "capabilities"):
            contracts.validate(value["kind"], value)
        return value

    def request(name, profile, route, body=None, **expect):
        return record(name, call(profile["connection"], route, profile["bearer"], body or packet()), **expect)

    def pause():
        return record("owner_pause", call(a["connection"], "/v2/admin/pause", owner_token(args.owner_token_file), packet()), kind="paused")

    def acquire(name, profile, path):
        response = request(name, profile, "/v2/controller/acquire", kind="controller")
        profile["lease"] = response["lease"]; save_private(path, profile)
        return response["lease"]

    try:
        for name, profile in (("a", a), ("b", b)):
            request(name + "_capabilities", profile, "/v2/capabilities", kind="capabilities")
            health = request(name + "_health", profile, "/v2/health", kind="observation")
            if not all(health["health"][k] is True for k in ("ok", "auth", "paired")) or health["context"] != profile["context"]:
                raise AssertionError("Authenticated captured context health required")
        pause()
        first_lease = acquire("a_acquire", a, args.profile_a)
        request("b_controller_conflict", b, "/v2/controller/acquire", error="ControllerConflict")
        original = command(a, first_lease)
        first = request("app_open", a, "/v2/execute", original, kind="receipt")
        if first["state"] != "Dispatched": raise AssertionError("Companion launch was not dispatched")
        duplicate = copy.deepcopy(original); duplicate["requestId"] = str(uuid.uuid4())
        repeated = request("identical_duplicate", a, "/v2/execute", duplicate, kind="receipt")
        if repeated != first: raise AssertionError("Duplicate changed the stored receipt")
        for name, field, replacement in (("payload_conflict", "payload", {"synthetic": "changed"}),
                                          ("scope_conflict", "scope", {**original["scope"], "packageId": "com.termux"})):
            altered = copy.deepcopy(original); altered[field] = replacement; altered["payloadHash"] = payload_hash(altered)
            request(name, a, "/v2/execute", altered, error="ActionConflict")
        request("cross_session_receipt_denied", b, "/v2/receipt", packet(actionId=original["actionId"]), error="IdentityMismatch")
        pause()
        request("paused_lease_denied", a, "/v2/execute", command(a, first_lease), error="ControllerConflict")
        acquire("b_acquire_after_pause", b, args.profile_b)
        request("replaced_controller_denied", a, "/v2/execute", command(a, first_lease), error="ControllerConflict")
        request("b_release", b, "/v2/controller/release", kind="controller_released")
        b["lease"] = None; save_private(args.profile_b, b)
        new_lease = acquire("a_acquire_new_revision", a, args.profile_a)
        if new_lease["revision"] <= first_lease["revision"]: raise AssertionError("Lease revision did not increase")
        request("stale_same_session_lease_denied", a, "/v2/execute", command(a, first_lease), error="ControllerConflict")
        expired = command(a, new_lease); expired["deadline"] = utc(int(time.time() * 1000) - 1)
        request("deadline_denied", a, "/v2/execute", expired, error="DeadlineExceeded")
        unsupported = command(a, new_lease); unsupported["operation"] = "mail.send"; unsupported["payloadHash"] = payload_hash(unsupported)
        request("unsupported_effect_denied", a, "/v2/execute", unsupported, error="UnsupportedCapability")
        # Lost response is reconciled by reads before a deliberate deduplication check.
        lost = command(a, new_lease)
        drop_response(a, lost)
        report["steps"].append({"name": "client_acknowledgement_dropped", "actionId": lost["actionId"], "sends": 1, "effectBeforeReconciliation": "possible"})
        recovered = None
        for _ in range(15):
            value = call(a["connection"], "/v2/receipt", a["bearer"], packet(actionId=lost["actionId"]))
            if value.get("kind") == "receipt" and value.get("state") == "Dispatched":
                recovered = record("lost_ack_receipt_reconciled", value, kind="receipt"); break
            time.sleep(0.2)
        if recovered is None: raise AssertionError("Lost response receipt not recovered; never replay")
        result = request("reconciled_duplicate", a, "/v2/execute", lost, kind="receipt")
        if result != recovered: raise AssertionError("Reconciliation changed the stored receipt")
        cancelled = request("cancel_after_dispatch", a, "/v2/cancel", packet(actionId=original["actionId"]), kind="receipt")
        if cancelled["state"] != "Dispatched" or cancelled["cancellationRequested"] is not True:
            raise AssertionError("Cancellation erased an already dispatched effect")
        events = request("events", a, "/v2/events", packet(cursor=0, limit=128), kind="event_page")
        for event in events["events"]: contracts.validate("event", event)
        sync = request("resync", a, "/v2/resync", kind="resync")
        for receipt in sync["receipts"]: contracts.validate("receipt", receipt)
        request("acknowledge_delivered", a, "/v2/events/ack", packet(cursor=sync["cursor"]), kind="acknowledged")
        request("acknowledge_undelivered_denied", a, "/v2/events/ack", packet(cursor=sync["cursor"] + 1), error="InvalidRequest")
        request("a_release", a, "/v2/controller/release", kind="controller_released")
        a["lease"] = None; save_private(args.profile_a, a)
        report.update(passed=True, recoveryActionIds=[original["actionId"], lost["actionId"]], acknowledgedCursor=sync["cursor"])
    except Exception as failure:
        report["failure"] = {"class": type(failure).__name__, "message": "Live device scenario failed; inspect recorded controlled responses; no automatic effect replay"}
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x") as stream: stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "steps": len(report["steps"]), "output": str(output),
                      "modelEvidence": False, "processRestartOrRebootEvidence": False}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
