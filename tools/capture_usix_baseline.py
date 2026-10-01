#!/usr/bin/env python3
"""Bounded genuine USIX health evaluation; retain only redacted tool evidence."""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


HEALTH_KEYS = ("ok", "auth", "paired", "listener", "accessibility")
WITNESS_KEYS = ("runtimeLabel", "invocationCwd", "recordedAt", "operation", "helperExitCode", "health", "elapsedMs")
DONE_KEYS = ("model", "tokens", "prompt_tokens", "tool_definition_tokens", "context_used",
             "context_window", "finish_reason", "coding", "verify_profile")


def health_witness(value):
    """Accept only the controlled caller's public health fields."""
    if not isinstance(value, dict) or value.get("runtimeLabel") != "usix" or value.get("operation") != "v1.health":
        return None
    health = value.get("health")
    if not isinstance(health, dict) or any(type(health.get(k)) is not bool for k in HEALTH_KEYS):
        return None
    result = {k: value[k] for k in WITNESS_KEYS if k in value}
    result["health"] = {k: health[k] for k in HEALTH_KEYS}
    return result


def redact_events(lines, command):
    """Discard reasoning/unknown output; correlate only a serial exact-command call."""
    events = []
    pending = []
    ambiguous = False
    for line in lines:
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict):
            continue
        kind = event.get("type")
        item = {"type": kind, "seq": event.get("seq")}
        if kind == "tool_start":
            expected = event.get("tool") == "bash" and event.get("args") == {"command": command}
            ambiguous |= bool(pending)
            pending.append(expected)
            item["tool"] = event.get("tool")
            if expected:
                item["args"] = event["args"]
            else:
                item["argumentsOmitted"] = True
        elif kind == "tool_result":
            expected = len(pending) == 1 and pending[0] and not ambiguous
            if pending:
                pending.pop(0)
            if not pending:
                ambiguous = False
            item.update({k: event[k] for k in ("code", "success") if k in event})
            item["exactCommandResult"] = expected
            if expected:
                try:
                    witness = health_witness(json.loads(event.get("output", "")))
                except (ValueError, TypeError):
                    witness = None
                if witness:
                    item["deviceWitness"] = witness
            item["outputOmitted"] = "deviceWitness" not in item
        elif kind == "done":
            item.update({k: event[k] for k in DONE_KEYS if k in event})
        else:
            continue
        events.append(item)
    return events


def verified_trace(events, witness, exit_code):
    device = health_witness(witness)
    if exit_code != 0 or not device or device.get("helperExitCode") != 0:
        return False
    if not all(device["health"][k] for k in ("ok", "auth", "paired")):
        return False
    return any(e.get("type") == "done" and e.get("finish_reason") == "completed" for e in events) and any(
        e.get("type") == "tool_result" and e.get("exactCommandResult") is True
        and e.get("success") is True and e.get("deviceWitness") == device for e in events
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cwd", required=True, type=Path, help="unchanged runtime launch directory")
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--yolo", action="store_true", help="use only when the user authorized this existing runtime flag")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 900:
        parser.error("seconds must be between 1 and 900")
    root = Path(__file__).resolve().parents[1]
    witness = root / ".build-tools/usix-health-witness.json"
    if witness.exists():
        parser.error("existing health witness must be reviewed; refusing to reuse stale evidence")
    if args.output.exists():
        parser.error("output already exists; preserve previous evaluation evidence")
    command = f"python3 {root}/tools/capture_health_baseline.py --runtime usix"
    prompt = (
        "P0 Companion 검증이다. 실제 기기 health만 한 번 확인하라. "
        f"기존 bash 도구로 정확히 다음 명령을 실행하라: `{command}`. "
        "다른 명령이나 fetch_url은 호출하지 마라. 승인 정책을 유지하고, "
        "기기 응답을 못 얻으면 실행 불가라고 보고하라. 소스 파일은 변경하지 마라."
    )
    argv = ["usix", "-c", prompt, "--model", "code", "--effort", "xhigh", "--format", "jsonl",
            "--allowed-tools", f"bash({command})"]
    if args.yolo:
        argv.append("--yolo")
    scratch = root / ".build-tools"
    scratch.mkdir(exist_ok=True)
    raw = scratch / (args.output.stem + "-raw.jsonl")
    started = time.monotonic()
    recorded = datetime.now(timezone.utc).isoformat()
    with raw.open("x", encoding="utf-8") as log:
        child = subprocess.Popen(argv, cwd=args.cwd.resolve(), stdin=subprocess.DEVNULL,
                                 stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = child.wait(timeout=args.seconds)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            code = 124
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                child.wait()
    raw_bytes = raw.stat().st_size
    events = redact_events(raw.read_text(encoding="utf-8", errors="replace").splitlines(), command)
    device = health_witness(json.loads(witness.read_text(encoding="utf-8"))) if witness.exists() else None
    verified = verified_trace(events, device, code)
    report = {
        "source": "unchanged USIX CLI against the existing configured deployment",
        "redaction": "only expected health-tool output and model counts; no credentials, account/session IDs or reasoning",
        "recordedAt": recorded, "invocationCwd": str(args.cwd.resolve()),
        "configuration": {"modelRoute": "code", "effort": "xhigh", "wallLimitSeconds": args.seconds,
                          "permission": ("user-authorized --yolo; existing static/surface/deployment gates retained; health-only task"
                                         if args.yolo else "process-scoped exact-command allow rule; runtime admission and deployment policy retained")},
        "elapsedMs": round((time.monotonic() - started) * 1000), "exitCode": code,
        "rawOutputBytes": raw_bytes, "modelOrToolEventsObserved": bool(events),
        "events": events, "deviceWitness": device, "deviceHealthViaModelVerified": verified,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    raw.unlink()
    print(json.dumps(report, ensure_ascii=False))
    return 0 if verified else 1


if __name__ == "__main__":
    raise SystemExit(main())
