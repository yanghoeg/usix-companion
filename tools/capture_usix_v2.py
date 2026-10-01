#!/usr/bin/env python3
"""Genuine unchanged USIX → existing admitted tool → controlled device v2 witness."""
import argparse
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
from datetime import datetime, timezone

HEALTH_KEYS = ("ok", "auth", "paired", "listener", "accessibility")
DONE_KEYS = ("model", "tokens", "prompt_tokens", "tool_definition_tokens", "context_used", "context_window", "finish_reason")


def witness(value):
    if not isinstance(value, dict) or value.get("operation") != "v2.capabilities+health" or value.get("runtimeLabel") != "usix":
        return None
    if not isinstance(value.get("health"), dict) or type(value.get("helperExitCode")) is not int or value["helperExitCode"] != 0 or any(type(value["health"].get(key)) is not bool for key in HEALTH_KEYS):
        return None
    if not all(value["health"][key] for key in ("ok", "auth", "paired")):
        return None
    required = ("runtimeLabel", "operation", "invocationCwd", "recordedAt", "source", "helperExitCode", "health", "contextSha256", "supportedVersions", "capabilities", "elapsedMs")
    return {key: value[key] for key in required} if all(key in value for key in required) else None


def redact(lines, command):
    events = []; pending = []; ambiguous = False
    for line in lines:
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if not isinstance(value, dict):
            continue
        kind = value.get("type")
        event = {"type": kind, "seq": value.get("seq")}
        for key in ("call_id", "tool_call_id", "request_id"):
            if key in value:
                event[key] = value[key]
        if kind == "tool_start":
            exact = value.get("tool") == "bash" and value.get("args") == {"command": command}
            ambiguous |= bool(pending); pending.append(exact)
            event.update(tool=value.get("tool"), exactCommand=exact)
            if exact:
                event["args"] = value["args"]
        elif kind == "tool_result":
            exact = len(pending) == 1 and pending[0] and not ambiguous
            if pending:
                pending.pop(0)
            if not pending:
                ambiguous = False
            event.update(exactCommandResult=exact, success=value.get("success"), code=value.get("code"))
            if exact:
                try:
                    observed = witness(json.loads(value.get("output", "")))
                    if observed:
                        event["deviceWitness"] = observed
                except (ValueError, TypeError):
                    pass
        elif kind == "done":
            event.update({key: value[key] for key in DONE_KEYS if key in value})
        else:
            continue
        events.append(event)
    return events


def verified(events, observed, exit_code):
    return exit_code == 0 and observed is not None and any(e.get("type") == "done" and e.get("finish_reason") == "completed" for e in events) and any(
        e.get("type") == "tool_result" and e.get("exactCommandResult") and e.get("success") is True and e.get("deviceWitness") == observed for e in events)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cwd", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--witness", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=180)
    permission = parser.add_mutually_exclusive_group()
    permission.add_argument("--interactive", action="store_true", help="use the existing TTY one-time approval for this exact controlled read command")
    permission.add_argument("--yolo", action="store_true", help="explicit owner-authorized existing CLI flag; use only when the user permits this controlled read command")
    args = parser.parse_args()
    if args.witness.exists() or args.output.exists() or not 1 <= args.seconds <= 900:
        parser.error("fresh evidence paths and bounded runtime required")
    root = Path(__file__).resolve().parents[1]
    command = shlex.join(["python3", str(root / "tools/capture_device_v2.py"), "--runtime", "usix", "--profile", str(args.profile.resolve()), "--witness", str(args.witness.resolve())])
    prompt = ("P2 Companion 실제 모델·기기 v2 검증이다. 기존 bash 도구로 다음 정확한 명령만 한 번 실행하라:\n" + command +
              "\n이 명령은 신뢰된 통합 CLI로 v2 상태를 조회하고 Companion 검증 파일에 저장한다. 프로필은 통합 CLI가 읽게 하고 모델이 직접 토큰·프로필 파일을 읽거나 출력하지 마라. 다른 도구, 소스 변경, 메일·알림·화면 조회는 금지한다. 기존 런타임 승인 정책을 유지하라. "
              "기기 출력이 있어야 확인했다고 보고하고 실행하지 못하면 미확인으로 보고하라.")
    argv = ["usix", "-c", prompt, "--model", "code", "--effort", "xhigh", "--format", "jsonl", "--allowed-tools", "bash(" + command + ")"]
    if args.yolo:
        argv.append("--yolo")
    start = time.monotonic()
    decisions = []
    if args.interactive:
        from controlled_usix import run
        code, lines, decisions = run(argv, args.cwd.resolve(), command, args.seconds)
    else:
        raw = root / ".build-tools" / (args.output.stem + "-raw.jsonl")
        with raw.open("x") as stream:
            child = subprocess.Popen(argv, cwd=args.cwd.resolve(), stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = child.wait(timeout=args.seconds)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL); child.wait()
                code = 124
        lines = raw.read_text(errors="replace").splitlines(); raw.unlink()
    events = redact(lines, command)
    observed = witness(json.loads(args.witness.read_text())) if args.witness.exists() else None
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "source": "genuine unchanged installed USIX CLI and existing configured model deployment",
              "configuration": {"modelRoute": "code", "effort": "xhigh", "interactive": args.interactive, "yolo": args.yolo, "wallLimitSeconds": args.seconds},
              "permission": "explicit owner-authorized existing --yolo for the controlled read command; static/surface/deployment gates retained" if args.yolo else ("existing TTY one-time approval of the exact controlled read command; no yolo" if args.interactive else "process-scoped exact-command allow rule; existing runtime admission/deployment approval retained; no yolo"),
              "approvalDecisions": decisions,
              "elapsedMs": round((time.monotonic() - start) * 1000), "exitCode": code, "events": events, "deviceWitness": observed,
              "deviceV2ViaModelVerified": verified(events, observed, code), "redaction": "only controlled v2 health/capabilities, exact command, correlation and model counts; no credentials or reasoning"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["deviceV2ViaModelVerified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
