#!/usr/bin/env python3
"""Physical P3 qualification through the installed Companion CLI contract; fixture scope only.

Run directly for adapter evidence, or let an unchanged runtime invoke this exact
command for a fresh model/tool/device witness. This helper never opens private
apps, renews authority, reads mail, changes runtime source or replays effects.
"""
import argparse
import concurrent.futures
import hashlib
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "integration/src"))
from usix_companion.cli import packet
from usix_companion.client import call, private_json, save_private
from usix_companion.contract import Contracts, payload_hash, utc
from usix_companion.observations import scope

PACKAGE = "dev.usix.companion.fixture"


class Qualification:
    def __init__(self, profile_path):
        self.profile_path = profile_path
        self.profile = private_json(profile_path)
        if self.profile.get("packageId") != PACKAGE or self.profile.get("fixtureUi") is not True:
            raise ValueError("Explicit fixture-only setup grant required")
        workspace = Path(self.profile["workspace"]).resolve(strict=True)
        if not workspace.is_relative_to(ROOT / ".build-tools"):
            raise ValueError("Qualification needs a Companion-owned dedicated evaluation workspace")
        self.contracts = Contracts()
        self.checks = []
        self.calls = 0
        self.last_error_code = None

    def read(self, route, **data):
        self.calls += 1
        value = call(self.profile["connection"], route, self.profile["bearer"], packet(**data))
        if value.get("error"): self.last_error_code = value["error"].get("code")
        if value.get("kind") in ("snapshot", "visual", "receipt", "capabilities"):
            self.contracts.validate(value["kind"], value)
        if value.get("context") is not None and value["context"] != self.profile["context"]:
            raise ValueError("Device returned another captured context")
        if value.get("scope") is not None and value["scope"] != scope(self.profile):
            raise ValueError("Device returned another package/account scope")
        return value

    def check(self, name, condition, **evidence):
        self.checks.append({"check": name, "passed": bool(condition), **evidence})
        if not condition:
            raise ValueError("Controlled qualification check failed: " + name)

    def observe(self, selector=None, snapshot=None, offset=0, limit=128):
        value = self.read("/v2/observe", scope=scope(self.profile, snapshot), selector=selector, offset=offset, limit=limit)
        if value.get("kind") != "snapshot":
            raise ValueError("Foreground fixture observation unavailable: " + value.get("error", {}).get("code", "InvalidRequest"))
        return value

    def wait(self, text, milliseconds=5000):
        value = self.read("/v2/wait", scope=scope(self.profile), waitKind="text", selector={"text": text},
                          deadline=utc(int(time.time() * 1000) + milliseconds), cancellationId=str(uuid.uuid4()))
        if value.get("kind") != "snapshot":
            raise ValueError("Expected controlled state unavailable: " + value.get("error", {}).get("code", "InvalidRequest"))
        return value

    def action(self, operation, payload, snapshot=None, cancellation=None):
        body = packet(kind="command", context=self.profile["context"], actionId=str(uuid.uuid4()), operation=operation,
                      scope=scope(self.profile, snapshot), payload=payload, controllerLease=self.profile["lease"],
                      deadline=utc(int(time.time() * 1000) + 30000), cancellationId=cancellation,
                      authority={"kind": "grant", "ref": self.profile["grantRef"]})
        body["payloadHash"] = payload_hash(body)
        self.contracts.validate("command", body)
        self.calls += 1
        value = call(self.profile["connection"], "/v2/execute", self.profile["bearer"], body)
        if value.get("error"): self.last_error_code = value["error"].get("code")
        if value.get("kind") == "receipt": self.contracts.validate("receipt", value)
        return value, body

    def click(self, selector, goal=None):
        # A rejected stale snapshot with effect:none permits a new freshly admitted
        # attempt. Dispatched/possible effects are never retried here.
        for _ in range(3):
            observed = self.observe()
            payload = {"target": selector, **({"goal": goal} if goal else {})}
            receipt, command = self.action("ui.click", payload, observed["snapshotRef"])
            if receipt.get("state") == "Failed" and receipt.get("effect") == "none" and receipt.get("error", {}).get("code") == "StaleSnapshot":
                self.checks.append({"check": "fresh admission after known stale rejection", "passed": True, "actionId": command["actionId"], "effect": "none"})
                continue
            return receipt, command
        raise ValueError("Fixture keeps changing; no unambiguous dispatch")

    def changed(self, baseline):
        value = self.read("/v2/wait", scope=scope(self.profile, baseline), waitKind="changed", selector=None,
                          deadline=utc(int(time.time() * 1000) + 5000), cancellationId=str(uuid.uuid4()))
        if value.get("kind") != "snapshot": raise ValueError("Controlled state-change wait unavailable")
        return value

    def keyboard(self, visible, observed):
        for _ in range(4):
            if observed["inputWindowVisible"] == visible:
                self.check("keyboard visibility " + str(visible), True, generation=observed["generation"])
                return observed
            observed = self.changed(observed["snapshotRef"])
        self.check("keyboard visibility " + str(visible), False)

    def goal(self, expected, selector=None):
        return {"goalId": str(uuid.uuid4()), "kind": "node_text", "selector": selector or {"text": expected},
                "expectedText": expected, "accountSelector": {"text": "eval-account"} if self.profile.get("accountRef") else None}

    def verified(self, receipt, command, goal, expected):
        self.check("effect acknowledged for " + expected, receipt.get("state") in ("Dispatched", "NeedsVerification", "Verified"),
                   actionId=command["actionId"], state=receipt.get("state"), effect=receipt.get("effect"))
        self.wait(expected)
        value = self.read("/v2/verify", actionId=command["actionId"], goal=goal)
        self.check("observed UI state " + expected, value.get("state") == "Verified" and bool(value.get("evidence")) and
                   all(item.get("purpose") == "ui_state" and item.get("goalId") == goal["goalId"] for item in value["evidence"]),
                   receiptId=value.get("receiptId"), state=value.get("state"), evidence=value.get("evidence"), observationRef=value.get("observationRef"))
        return value

    def start(self):
        caps = self.read("/v2/capabilities")
        self.check("P3 supported by the real APK", any(c["operation"] == "ui.observe" and c["supported"] for c in caps.get("capabilities", [])))
        lease = self.read("/v2/controller/acquire")
        self.check("selected controller acquired", lease.get("kind") == "controller")
        self.profile["lease"] = lease["lease"]; save_private(self.profile_path, self.profile)
        opened, _ = self.action("app.open", {})
        self.check("fixture launch is dispatched", opened.get("state") == "Dispatched", state=opened.get("state"))
        window = self.read("/v2/wait", scope=scope(self.profile), waitKind="window", selector=None,
                           deadline=utc(int(time.time() * 1000) + 5000), cancellationId=str(uuid.uuid4()))
        self.check("foreground package retained", window.get("packageId") == PACKAGE, packageId=window.get("packageId"), accountRef=self.profile.get("accountRef"))

    def mode(self, mode):
        receipt, _ = self.click({"resourceId": PACKAGE + ":id/mode_" + mode})
        self.check(mode + " mode dispatched", receipt.get("state") == "Dispatched")
        self.wait("Fixture " + mode)
        observed = self.observe()
        self.check(mode + " rich metadata", observed["complete"] and observed["generation"] >= 1 and bool(observed["nodes"]) and
                   all("bounds" in node and "parentRef" in node and "className" in node for node in observed["nodes"]), totalNodes=observed["totalNodes"])
        duplicate, _ = self.action("ui.click", {"target": {"text": "Duplicate"}}, observed["snapshotRef"])
        self.check(mode + " duplicate labels rejected", duplicate.get("effect") == "none" and duplicate.get("error", {}).get("code") == "AmbiguousTarget", state=duplicate.get("state"))
        selector = {"resourceId": PACKAGE + ":id/apply"} if mode == "native" else {"resourceId": "compose_apply"} if mode == "compose" else {"text": "Apply webview", "role": "button"}
        goal = self.goal("Applied " + mode)
        receipt, command = self.click(selector, goal)
        verified = self.verified(receipt, command, goal, "Applied " + mode)
        duplicate = self.read("/v2/receipt", actionId=command["actionId"])
        self.check(mode + " durable verified receipt", duplicate == verified)
        altered = {**goal, "expectedText": "not the bound criterion"}
        conflict = self.read("/v2/verify", actionId=command["actionId"], goal=altered)
        self.check(mode + " criterion substitution rejected", conflict.get("error", {}).get("code") == "ActionConflict" and conflict.get("effect") == "none")
        async_target = {"resourceId": PACKAGE + ":id/async"} if mode == "native" else {"resourceId": "compose_async"} if mode == "compose" else {"text": "Async webview", "role": "button"}
        dispatched, _ = self.click(async_target)
        self.check(mode + " dispatch is not verification", dispatched.get("state") == "Dispatched" and dispatched.get("evidence") == [], observationRef=dispatched.get("observationRef"))
        changed, _ = self.action("ui.click", {"target": selector}, observed["snapshotRef"])
        self.check(mode + " stale node rejected", changed.get("effect") == "none" and changed.get("error", {}).get("code") == "StaleSnapshot")
        start = time.monotonic(); self.wait("Async ready " + mode)
        self.check(mode + " event-driven asynchronous observation", True, elapsedMs=round((time.monotonic() - start) * 1000))
        if mode == "native":
            self.native_checks()

    def native_checks(self):
        first = self.observe(limit=80)
        self.check("password field masked", "CONTROLLED_SECRET_123" not in json.dumps(first) and any(n["sensitive"] and n["text"] == "[redacted]" for n in first["nodes"]))
        if first["nextOffset"] is not None:
            second = self.observe(snapshot=first["snapshotRef"], offset=first["nextOffset"], limit=128)
            self.check("pagination exceeds 80 without truncation", second["snapshotRef"] == first["snapshotRef"] and second["offset"] == 80 and first["totalMatching"] > 80, totalMatching=first["totalMatching"])
        else:
            self.check("pagination exceeds 80 without truncation", False, totalMatching=first["totalMatching"])
        self.click({"resourceId": PACKAGE + ":id/edit_text"})
        observed = self.keyboard(True, self.observe())
        stale, _ = self.action("ui.tap", {"x": 30, "y": 30}, first["snapshotRef"])
        self.check("focus/keyboard invalidate old coordinates", stale.get("effect") == "none" and stale.get("error", {}).get("code") == "StaleSnapshot")
        goal = self.goal("한글 English 123", {"resourceId": PACKAGE + ":id/edit_text"})
        observed = self.observe()
        receipt, command = self.action("ui.set_text", {"target": {"resourceId": PACKAGE + ":id/edit_text"}, "text": goal["expectedText"], "goal": goal}, observed["snapshotRef"])
        self.verified(receipt, command, goal, goal["expectedText"])
        dispatched, command = self.action("ui.back", {}, self.observe()["snapshotRef"])
        self.check("keyboard back dispatched", dispatched.get("state") == "Dispatched")
        self.keyboard(False, self.observe())
        cancelled = self.read("/v2/cancel", actionId=command["actionId"])
        self.check("post-dispatch cancellation preserves effect", cancelled.get("state") == "Dispatched" and cancelled.get("cancellationRequested") is True)
        before = self.observe()
        self.click({"resourceId": PACKAGE + ":id/rotate"})
        rotated = self.changed(before["snapshotRef"])
        for _ in range(4):
            if rotated["rotation"] != before["rotation"]: break
            rotated = self.changed(rotated["snapshotRef"])
        self.check("physical rotation observed", rotated["rotation"] != before["rotation"], before=before["rotation"], after=rotated["rotation"])
        stale, _ = self.action("ui.tap", {"x": 30, "y": 30}, before["snapshotRef"])
        self.check("rotation rejects stale coordinates", stale.get("effect") == "none" and stale.get("error", {}).get("code") == "StaleSnapshot")
        self.click({"resourceId": PACKAGE + ":id/rotate"})
        self.wait("Fixture native")

    def safety(self):
        wrong = self.read("/v2/observe", scope={**scope(self.profile), "accountRef": str(uuid.uuid4())}, selector=None, offset=0, limit=128)
        self.check("untrusted data cannot select another account", wrong.get("error", {}).get("code") == "IdentityMismatch" and wrong.get("effect") == "none")
        wrong = self.read("/v2/observe", scope={**scope(self.profile), "packageId": "dev.other.app"}, selector=None, offset=0, limit=128)
        self.check("untrusted data cannot select another package", wrong.get("error", {}).get("code") == "IdentityMismatch" and wrong.get("effect") == "none")
        cancellation = str(uuid.uuid4())
        self.read("/v2/wait/cancel", cancellationId=cancellation)
        observed = self.observe()
        cancelled, _ = self.action("ui.click", {"target": {"resourceId": PACKAGE + ":id/mode_native"}}, observed["snapshotRef"], cancellation)
        self.check("cancelled before dispatch", cancelled.get("error", {}).get("code") == "Cancelled" and cancelled.get("effect") == "none")
        cancellation = str(uuid.uuid4())
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(self.read, "/v2/wait", scope=scope(self.profile), waitKind="text", selector={"text": "NEVER PRESENT CONTROLLED"},
                                      deadline=utc(int(time.time() * 1000) + 30000), cancellationId=cancellation)
            self.read("/v2/wait/cancel", cancellationId=cancellation)
            cancelled = pending.result(timeout=5)
        self.check("active wait cancellation is bounded", cancelled.get("error", {}).get("code") == "Cancelled" and cancelled.get("effect") == "none")
        deadline = self.read("/v2/wait", scope=scope(self.profile), waitKind="text", selector={"text": "NEVER PRESENT CONTROLLED"},
                             deadline=utc(int(time.time() * 1000) + 100), cancellationId=str(uuid.uuid4()))
        self.check("wait deadline is explicit", deadline.get("error", {}).get("code") == "DeadlineExceeded" and deadline.get("effect") == "none")
        outside, _ = self.action("ui.tap", {"x": 32768, "y": 32768}, self.observe()["snapshotRef"])
        self.check("coordinates outside the app rejected", outside.get("effect") == "none" and outside.get("error", {}).get("code") == "InvalidRequest")
        home, _ = self.action("ui.home", {}, self.observe()["snapshotRef"])
        self.check("home remains dispatch-only", home.get("state") == "Dispatched")
        missing = self.read("/v2/observe", scope=scope(self.profile), selector=None, offset=0, limit=128)
        if missing.get("kind") == "snapshot":
            changed = self.read("/v2/wait", scope=scope(self.profile, missing["snapshotRef"]), waitKind="changed", selector=None,
                                deadline=utc(int(time.time() * 1000) + 5000), cancellationId=str(uuid.uuid4()))
            missing = changed
        self.check("unavailable selected package exposes no other screen", missing.get("kind") == "error" and missing.get("effect") == "none")
        opened, _ = self.action("app.open", {})
        self.check("fixture re-open dispatched", opened.get("state") == "Dispatched")
        self.read("/v2/wait", scope=scope(self.profile), waitKind="window", selector=None,
                  deadline=utc(int(time.time() * 1000) + 5000), cancellationId=str(uuid.uuid4()))

    def visual(self):
        receipt, _ = self.click({"resourceId": PACKAGE + ":id/mode_canvas"})
        self.check("image-only fixture dispatched", receipt.get("state") == "Dispatched")
        for language, expected in (("latin", "Offline OCR 1234"), ("korean", "안녕하세요")):
            snapshot = self.observe()
            captured = self.read("/v2/capture", scope=scope(self.profile, snapshot["snapshotRef"]), language=language)
            texts = " ".join(block["text"] for block in captured.get("blocks", []))
            self.check("bundled " + language + " OCR on physical image", captured.get("kind") == "visual" and expected in texts,
                       contentHash=captured.get("contentHash"), width=captured.get("width"), height=captured.get("height"), recognizedControlledText=expected in texts)
        self.click({"resourceId": PACKAGE + ":id/mode_secure"})
        snapshot = self.observe()
        protected = self.read("/v2/capture", scope=scope(self.profile, snapshot["snapshotRef"]), language=None)
        self.check("protected capture rejected", protected.get("error", {}).get("code") == "PermissionRequired" and protected.get("effect") == "none")
        self.click({"resourceId": PACKAGE + ":id/mode_native"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--runtime", required=True, choices=["usix", "usix-termux", "direct"])
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--suite", choices=["ui", "all"], default="all")
    args = parser.parse_args()
    if args.output.exists() or not args.output.resolve().is_relative_to(ROOT):
        parser.error("Fresh Companion-owned evidence path required")
    start = time.monotonic(); evaluation = None
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "runtimeLabel": args.runtime, "operation": "P3.controlled-observation",
              "invocationCwd": str(Path.cwd()), "source": "physical authenticated Companion fixture; no mock device/model", "suite": args.suite, "runtimeSourceWrites": 0}
    try:
        evaluation = Qualification(args.profile.resolve(strict=True))
        report["contextSha256"] = hashlib.sha256(json.dumps(evaluation.profile["context"], sort_keys=True).encode()).hexdigest()
        evaluation.start()
        for mode in ("native", "compose", "webview"): evaluation.mode(mode)
        evaluation.safety()
        if args.suite == "all": evaluation.visual()
        report["passed"] = True
    except Exception:
        report.update(passed=False, error="Qualification incomplete; inspect controlled checks and current readiness. Credentials omitted.")
    finally:
        if evaluation:
            try: evaluation.read("/v2/controller/release")
            except Exception: pass
    report.update(checks=evaluation.checks if evaluation else [], deviceCalls=evaluation.calls if evaluation else 0,
                  lastDeviceErrorCode=evaluation.last_error_code if evaluation else None, elapsedMs=round((time.monotonic() - start) * 1000))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output: output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    # Runtime shell output stays small; the immutable full evidence file is hash-bound.
    summary = {key: report[key] for key in ("recordedAt", "runtimeLabel", "operation", "invocationCwd", "suite", "passed", "deviceCalls", "elapsedMs", "lastDeviceErrorCode")}
    summary.update(contextSha256=report.get("contextSha256"), checkCount=len(report["checks"]),
                   passedChecks=sum(check["passed"] for check in report["checks"]), evidenceSha256=hashlib.sha256(args.output.read_bytes()).hexdigest())
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__": raise SystemExit(main())
