#!/usr/bin/env python3
"""Bind a genuine unchanged runtime's exact tool call to fresh physical P3 evidence.

No runtime source/configuration is written. Credential profiles are read only by
the controlled fixture helper. USIX --yolo requires a new explicit user approval.
"""
import argparse
import errno
import hashlib
import json
import os
from pathlib import Path
import pty
import re
import select
import shlex
import signal
import struct
import subprocess
import termios
import time
from datetime import datetime, timezone
import fcntl

ROOT = Path(__file__).resolve().parents[1]
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
SUMMARY_KEYS = ("recordedAt", "runtimeLabel", "operation", "invocationCwd", "suite", "passed", "deviceCalls", "elapsedMs", "lastDeviceErrorCode")
REQUIRED_CHECKS = {"observed UI state Applied " + mode for mode in ("native", "compose", "webview")} | {
    "bundled latin OCR on physical image", "bundled korean OCR on physical image", "protected capture rejected",
    "untrusted data cannot select another account", "untrusted data cannot select another package",
    "active wait cancellation is bounded", "rotation rejects stale coordinates", "password field masked"}


def summary(path, runtime, cwd, started, finished):
    if not path.is_file():
        return None
    raw = path.read_bytes()
    value = json.loads(raw)
    recorded = datetime.fromisoformat(value["recordedAt"]).timestamp()
    checks = value.get("checks", [])
    if (value.get("runtimeLabel") != runtime or value.get("operation") != "P3.controlled-observation" or
        value.get("invocationCwd") != str(cwd) or value.get("suite") != "all" or value.get("passed") is not True or
        not started <= recorded <= finished or not checks or not all(check.get("passed") is True for check in checks) or
        not REQUIRED_CHECKS.issubset({check.get("check") for check in checks})):
        return None
    result = {key: value[key] for key in SUMMARY_KEYS}
    result.update(contextSha256=value["contextSha256"], checkCount=len(checks), passedChecks=len(checks),
                  evidenceSha256=hashlib.sha256(raw).hexdigest())
    return result


def redact_usix(lines, command):
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
        if kind == "tool_start":
            exact = value.get("tool") == "bash" and value.get("args") == {"command": command}
            ambiguous |= bool(pending); pending.append(exact)
            event.update(tool=value.get("tool"), exactCommand=exact)
            if exact: event["args"] = value["args"]
        elif kind == "tool_result":
            exact = len(pending) == 1 and pending[0] and not ambiguous
            if pending: pending.pop(0)
            if not pending: ambiguous = False
            event.update(exactCommandResult=exact, success=value.get("success"), code=value.get("code"))
            if exact:
                try:
                    output = json.loads(value.get("output", ""))
                    if isinstance(output, dict) and output.get("operation") == "P3.controlled-observation":
                        event["deviceWitness"] = output
                except (ValueError, TypeError): pass
        elif kind == "done":
            event.update({key: value[key] for key in ("model", "tokens", "prompt_tokens", "finish_reason") if key in value})
        else:
            continue
        for key in ("call_id", "tool_call_id", "request_id"):
            if key in value: event[key] = value[key]
        events.append(event)
    return events


def verified_usix(events, observed, code):
    return code == 0 and observed is not None and any(e.get("type") == "done" and e.get("finish_reason") == "completed" for e in events) and any(
        e.get("type") == "tool_result" and e.get("exactCommandResult") and e.get("success") is True and e.get("deviceWitness") == observed for e in events)


def termux_approval(line, command):
    match = re.search(r"approval needed: (\w+) (\{.*\})\s+\[y/N\]", line)
    if not match: return None
    try: arguments = json.loads(match.group(2))
    except ValueError: arguments = None
    exact = match.group(1) == "shell" and arguments == {"command": command}
    return {"tool": match.group(1), "exactCommand": exact, "decision": "once" if exact else "deny",
            **({"args": arguments} if exact else {})}


def run_termux(cwd, command, seconds):
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 200, 0, 0))
    env = dict(os.environ, TERM="xterm-256color", USIX_BACKEND="llama")
    process = subprocess.Popen(["usix-termux"], cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    prompt = ("P3 controlled Companion fixture validation. Use the existing shell tool exactly once with this exact command: " + command +
              ". Wait for approval. The helper only controls the dedicated test app and writes a credential-free evidence file in Companion. "
              "Do not read profiles, tokens, private apps, mail, or edit sources. After the tool returns, print a JSON object copying exactly its passed, checkCount and evidenceSha256 fields. If it fails, report failure.")
    chunks = []; decisions = []; sent = False; exited = False; prompt_count = None; seen_approvals = set(); start = time.monotonic()
    try:
        while time.monotonic() - start < seconds and process.poll() is None:
            ready, _, _ = select.select([master], [], [], 0.2)
            if not ready: continue
            try: data = os.read(master, 65536)
            except OSError as error:
                if error.errno == errno.EIO: break
                raise
            if not data: break
            chunks.append(data)
            if b"\x1b[6n" in data: os.write(master, b"\x1b[1;1R")
            plain = ANSI.sub("", b"".join(chunks).decode(errors="replace"))
            if not sent and "How can I help?" in plain:
                os.write(master, b"\x1b[200~" + prompt.encode() + b"\x1b[201~\r"); sent = True
            if sent and prompt_count is None and "Thinking…" in plain:
                prompt_count = plain.count("How can I help?")
            for match in re.finditer(r"approval needed: \w+ \{.*?\}\s+\[y/N\]", plain):
                identity = match.start()
                if identity in seen_approvals: continue
                seen_approvals.add(identity)
                decision = termux_approval(match.group(), command)
                if decision:
                    decision["recordedAt"] = datetime.now(timezone.utc).isoformat(); decisions.append(decision)
                    os.write(master, b"y\n" if decision["decision"] == "once" else b"n\n")
            # The second input box establishes that the turn ended. Exit through
            # the ordinary TUI command, never a injected manual !shell command.
            if prompt_count is not None and not exited and plain.count("How can I help?") > prompt_count:
                os.write(master, b"exit\r"); exited = True
        if process.poll() is None:
            try: process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM); process.wait(timeout=8)
        return process.returncode, ANSI.sub("", b"".join(chunks).decode(errors="replace")), decisions, exited
    finally:
        if process.poll() is None: os.killpg(process.pid, signal.SIGTERM); process.wait(timeout=8)
        os.close(master)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, choices=["usix", "usix-termux"])
    parser.add_argument("--cwd", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--physical", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--yolo", action="store_true", help="USIX only; requires a new explicit user approval for this exact fixture command")
    args = parser.parse_args()
    cwd = args.cwd.resolve(strict=True)
    if (args.physical.exists() or args.output.exists() or not 1 <= args.seconds <= 2400 or
        args.yolo and args.runtime != "usix" or not args.physical.resolve().is_relative_to(ROOT) or not args.output.resolve().is_relative_to(ROOT)):
        parser.error("Fresh Companion evidence paths and bounded authorized runtime required")
    wrapper = ROOT / ".build-tools" / (args.output.stem + "-fixed-helper.py")
    wrapper.write_text("from pathlib import Path\nimport runpy, sys\nhelper = Path(" + repr(str(ROOT / "tools/capture_device_observations.py")) + ")\n" +
        "sys.argv = [str(helper), '--runtime', " + repr(args.runtime) + ", '--profile', " + repr(str(args.profile.resolve())) +
        ", '--output', " + repr(str(args.physical.resolve())) + ", '--suite', 'all']\nrunpy.run_path(str(helper), run_name='__main__')\n")
    command = shlex.join(["python3", str(wrapper)])
    started = time.time(); monotonic = time.monotonic()
    if args.runtime == "usix":
        from controlled_usix import run
        prompt = ("P3 Companion 테스트 앱 검증이다. 기존 bash 도구로 다음 정확한 명령만 한 번 실행하라:\n" + command +
                  "\n테스트 앱의 native/Compose/WebView 화면·OCR·취소만 검사한다. 프로필·토큰은 helper만 읽게 하며 소스·개인 앱·메일을 읽거나 변경하지 마라. "
                  "기존 승인을 기다리고 도구 결과에 근거해 성공/실패를 보고하라.")
        argv = ["usix", "-c", prompt, "--model", "code", "--effort", "xhigh", "--format", "jsonl", "--allowed-tools", "bash(" + command + ")"]
        if args.yolo: argv.append("--yolo")
        code, lines, decisions = run(argv, cwd, command, args.seconds)
        events = redact_usix(lines, command)
        observed = summary(args.physical, args.runtime, cwd, started, time.time())
        passed = verified_usix(events, observed, code)
        detail = {"events": events}
    else:
        code, plain, decisions, exited = run_termux(cwd, command, args.seconds)
        observed = summary(args.physical, args.runtime, cwd, started, time.time())
        reported = None
        for match in re.finditer(r'\{[^\r\n]*"evidenceSha256"[^\r\n]*\}', plain):
            try: reported = json.loads(match.group())
            except ValueError: pass
        expected = {key: observed[key] for key in ("passed", "checkCount", "evidenceSha256")} if observed else None
        passed = code == 0 and exited and expected is not None and reported == expected and sum(d["decision"] == "once" for d in decisions) == 1
        detail = {"normalTuiExit": exited and code == 0, "publicModelSummary": reported, "modelReportMatchesPhysicalEvidence": expected is not None and reported == expected,
                  "configuration": {"backend": "existing llama-server on loopback:8080", "model": "existing Qwen3.5-2B-Q5_K_M", "interactive": True}}
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "runtime": args.runtime, "invocationCwd": str(cwd),
              "source": "genuine unchanged installed runtime and existing model; exact admitted tool invokes physical controlled fixture",
              "command": command, "permission": "new explicit user-approved USIX --yolo" if args.yolo else "existing TTY one-time exact-command approval",
              "approvalDecisions": decisions, "physicalEvidence": str(args.physical.resolve().relative_to(ROOT)), "deviceWitness": observed,
              "exitCode": code, "elapsedMs": round((time.monotonic() - monotonic) * 1000), "deviceObservationsViaModelVerified": passed,
              "redaction": "only exact command, approvals, controlled fixture summary, correlation and model counts; no credentials or hidden reasoning", **detail}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output: output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("runtime", "physicalEvidence", "exitCode", "elapsedMs", "deviceObservationsViaModelVerified")}))
    return 0 if passed else 1


if __name__ == "__main__": raise SystemExit(main())
