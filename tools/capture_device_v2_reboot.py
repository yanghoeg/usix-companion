#!/usr/bin/env python3
"""Capture before/after a physical phone reboot, without replaying saved effects.

Run with the integration virtual environment on the phone. Restart the same
owning-user broker after reboot; do not reconfigure the Android WSS settings.
Only Companion itself is in scope. No model or UI/mail verification is claimed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

from capture_device_v2_scenarios import command
from usix_companion.cli import owner_token, packet
from usix_companion.client import absolute, call, private_json, save_private
from usix_companion.contract import Contracts


def boot_hash():
    value = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if not value:
        raise ValueError("A real kernel boot identifier is required")
    return hashlib.sha256(value.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--owner-token-file", default=str(Path.home() / ".usix/companion_token"))
    modes = parser.add_subparsers(dest="mode", required=True)
    before = modes.add_parser("before")
    before.add_argument("--loopback-profile", required=True, type=Path)
    before.add_argument("--wss-profile", required=True, type=Path)
    before.add_argument("--loopback-scenarios", required=True, type=Path)
    before.add_argument("--wss-scenarios", required=True, type=Path)
    after = modes.add_parser("after")
    after.add_argument("--baseline", required=True, type=Path)
    args = parser.parse_args()
    output = absolute(args.output)
    if output.exists():
        parser.error("Fresh evidence path required")
    contracts = Contracts()
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "mode": args.mode,
              "source": "physical Android APK through owning-user standalone integration",
              "steps": [], "passed": False, "physicalRebootVerified": False,
              "modelEvidence": False, "savedEffectsReplayed": 0}

    def record(name, value, kind=None, error=None):
        report["steps"].append({"name": name, "response": value})
        if kind and value.get("kind") != kind:
            raise AssertionError("Unexpected response kind")
        if error and (value.get("error", {}).get("code") != error or value.get("effect") != "none"):
            raise AssertionError("Expected rejection without effect")
        if value.get("kind") == "receipt":
            contracts.validate("receipt", value)
        if value.get("kind") == "event_page":
            for event in value["events"]:
                contracts.validate("event", event)
            if value["resyncRequired"]:
                raise AssertionError("Retention gap requires separate reconciliation")
        return value

    def request(name, profile, route, body=None, **expect):
        return record(name, call(profile["connection"], route, profile["bearer"], body or packet()), **expect)

    def profile(path, context=None):
        path = absolute(path)
        value = private_json(path)
        if value.get("packageId") != "dev.usix.companion" or not Path(value["workspace"]).is_dir():
            raise ValueError("Trusted Companion-only profile and captured workspace required")
        if context is not None and value["context"] != context:
            raise ValueError("Captured context changed")
        expires = datetime.fromisoformat(value["expiresAt"].replace("Z", "+00:00")).timestamp()
        if expires <= time.time() + 120:
            # Existing trusted renewal preserves context/history/budget. Never setup a new session.
            renewed = subprocess.run([sys.executable, "-m", "usix_companion.cli", "--profile", str(path),
                                      "renew", "--owner-token-file", args.owner_token_file],
                                     capture_output=True, text=True, timeout=80)
            result = json.loads(renewed.stdout)
            record("same_context_renewal", result, kind="renewed")
            updated = private_json(path)
            if renewed.returncode or updated["context"] != value["context"]:
                raise AssertionError("Same-context renewal failed")
            value = updated
        return value

    def check_saved(saved, current):
        name = saved["name"]
        health = request(name + "_health", current, "/v2/health", kind="observation")
        if health["context"] != saved["context"] or not all(health["health"][k] is True for k in
                                                           ("ok", "auth", "paired", "listener", "accessibility")):
            raise AssertionError("Authenticated physical health/context required")
        for expected in saved["receipts"]:
            observed = request(name + "_stored_receipt", current, "/v2/receipt",
                               packet(actionId=expected["actionId"]), kind="receipt")
            if observed != expected:
                raise AssertionError("Stored receipt changed")
        cursor = saved["acknowledgedCursor"]
        # Test the persisted lower bound BEFORE any acknowledgement can raise it.
        request(name + "_ack_backwards_denied", current, "/v2/events/ack",
                packet(cursor=cursor - 1), error="InvalidRequest")
        request(name + "_ack_original", current, "/v2/events/ack", packet(cursor=cursor), kind="acknowledged")
        sync = request(name + "_resync", current, "/v2/resync", kind="resync")
        if {r["actionId"]: r for r in sync["receipts"]} != {r["actionId"]: r for r in saved["receipts"]}:
            raise AssertionError("Receipt set changed; no replay")
        if sync["cursor"] < cursor:
            raise AssertionError("Event cursor moved backwards")
        return sync

    try:
        report["bootIdSha256"] = boot_hash()
        if args.mode == "before":
            saved_profiles = []
            current_profiles = []
            for name, profile_path, scenario_path, transport in (
                ("loopback", args.loopback_profile, args.loopback_scenarios, "loopback"),
                ("wss", args.wss_profile, args.wss_scenarios, "broker"),
            ):
                scenario = json.loads(absolute(scenario_path).read_text())
                if scenario.get("passed") is not True or scenario.get("acknowledgedCursor", 0) <= 0:
                    raise ValueError("Passing controlled scenario required")
                sync = next(s["response"] for s in scenario["steps"] if s["name"] == "resync")
                receipts = sync["receipts"]
                if len(receipts) != 2 or {r["actionId"] for r in receipts} != set(scenario["recoveryActionIds"]):
                    raise ValueError("Controlled recovery receipts required")
                captured = receipts[0]["context"]
                if any(r["context"] != captured or r["state"] != "Dispatched" for r in receipts):
                    raise ValueError("Consistent dispatched receipts required")
                current = profile(profile_path, captured)
                if current["connection"]["transport"] != transport:
                    raise ValueError("Explicit local/WSS profile required")
                saved = {"name": name, "profilePath": str(absolute(profile_path)), "context": captured,
                         "scenarioEvidence": str(absolute(scenario_path)), "receipts": receipts,
                         "acknowledgedCursor": scenario["acknowledgedCursor"]}
                check_saved(saved, current)
                saved["eventPage"] = request(name + "_events_before", current, "/v2/events",
                                             packet(cursor=0, limit=128), kind="event_page")
                saved_profiles.append(saved); current_profiles.append(current)
            if current_profiles[0]["context"]["deviceId"] != current_profiles[1]["context"]["deviceId"]:
                raise ValueError("Both profiles must select the same physical phone")
            if current_profiles[0]["context"]["sessionId"] == current_profiles[1]["context"]["sessionId"]:
                raise ValueError("Distinct sessions required for isolated boot recovery evidence")
            record("owner_pause_before_checkpoint", call(current_profiles[0]["connection"], "/v2/admin/pause",
                   owner_token(args.owner_token_file), packet()), kind="paused")
            active = request("wss_controller_before_reboot", current_profiles[1], "/v2/controller/acquire", kind="controller")
            current_profiles[1]["lease"] = active["lease"]
            save_private(args.wss_profile, current_profiles[1])
            page = request("events_at_reboot_checkpoint", current_profiles[0], "/v2/events",
                           packet(cursor=0, limit=128), kind="event_page")
            latest = page["events"][-1]
            if latest["type"] != "controller_changed" or latest["payload"]["lease"] != active["lease"]:
                raise AssertionError("Active lease checkpoint missing")
            report.update(profiles=saved_profiles, controllerBefore=active,
                          eventCursorBefore=page["nextCursor"], eventsAtCheckpoint=page["events"],
                          readyForPhysicalReboot=True, passed=True)
        else:
            baseline = json.loads(absolute(args.baseline).read_text())
            if baseline.get("mode") != "before" or baseline.get("readyForPhysicalReboot") is not True:
                raise ValueError("Successful pre-reboot checkpoint required")
            report["baseline"] = str(absolute(args.baseline))
            report["bootIdChanged"] = report["bootIdSha256"] != baseline["bootIdSha256"]
            # Fail before any network request/renewal if this is only an app/process restart.
            if not report["bootIdChanged"]:
                raise AssertionError("Physical kernel boot has not changed")
            local_saved, wss_saved = baseline["profiles"]
            if local_saved["context"]["sessionId"] == wss_saved["context"]["sessionId"]:
                raise ValueError("Distinct sessions required for isolated boot recovery evidence")
            local = profile(local_saved["profilePath"], local_saved["context"])
            # Renewing this different session cannot clear the pre-reboot WSS lease.
            page = request("boot_recovery_events_before_wss_renewal", local, "/v2/events",
                           packet(cursor=0, limit=128), kind="event_page")
            by_id = {e["eventId"]: e for e in page["events"]}
            if any(by_id.get(e["eventId"]) != e for e in baseline["eventsAtCheckpoint"]):
                raise AssertionError("Persisted event IDs/data changed")
            recovery = [e for e in page["events"] if e["cursor"] > baseline["eventCursorBefore"]
                        and e["type"] == "controller_changed" and e["payload"]["lease"] is None
                        and e["payload"]["runtimeId"] is None]
            if not recovery:
                raise AssertionError("Boot controller-clear event missing before WSS renewal")
            report["controllerClearedBeforeWssRenewal"] = True
            report["bootRecoveryEvents"] = recovery
            check_saved(local_saved, local)
            wss = profile(wss_saved["profilePath"], wss_saved["context"])
            check_saved(wss_saved, wss)
            wss_page = request("wss_persisted_events", wss, "/v2/events",
                               packet(cursor=0, limit=128), kind="event_page")
            wss_events = {e["eventId"]: e for e in wss_page["events"]}
            if any(wss_events.get(e["eventId"]) != e for e in wss_saved["eventPage"]["events"]):
                raise AssertionError("WSS event history changed")
            request("old_wss_controller_release_denied", wss, "/v2/controller/release", error="ControllerConflict")
            fresh = request("wss_new_controller_revision", wss, "/v2/controller/acquire", kind="controller")
            if fresh["lease"]["revision"] <= baseline["controllerBefore"]["lease"]["revision"]:
                raise AssertionError("Persisted controller revision did not increase")
            wss["lease"] = fresh["lease"]; save_private(wss_saved["profilePath"], wss)
            # A NEW controlled ID with a replaced lease must be rejected. Saved action IDs
            # above are only queried; their execute commands are never sent again.
            stale = command(wss, baseline["controllerBefore"]["lease"])
            request("pre_reboot_lease_execute_denied", wss, "/v2/execute", stale, error="ControllerConflict")
            request("rejected_probe_has_no_receipt", wss, "/v2/receipt",
                    packet(actionId=stale["actionId"]), error="ExpiredReference")
            check_saved(wss_saved, wss)
            request("new_controller_release", wss, "/v2/controller/release", kind="controller_released")
            wss["lease"] = None; save_private(wss_saved["profilePath"], wss)
            report.update(physicalRebootVerified=True, persistedReceiptsVerified=4,
                          savedEventHistoryPreserved=True, nativeWssSettingsRestored=True,
                          staleControllerRejectedWithoutEffect=True, passed=True)
    except Exception as failure:
        report["failure"] = {"class": type(failure).__name__,
                             "message": "Physical recovery check failed; inspect controlled steps. No saved effect was replayed."}
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x") as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "physicalRebootVerified": report["physicalRebootVerified"],
                      "steps": len(report["steps"]), "savedEffectsReplayed": 0, "output": str(output)}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
