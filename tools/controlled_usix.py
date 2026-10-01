"""Bounded real USIX TTY runner; preserve runtime admission and approve one exact read command.

No daemon endpoint, permission storage or runtime source is changed. Approval
uses the installed CLI's existing one-time TTY prompt and JSONL event binding.
"""
import errno
import json
import os
import pty
import re
import select
import signal
import subprocess
import time

ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def approval(event, command):
    if not isinstance(event, dict) or event.get("type") != "permission_request":
        return None
    exact = event.get("tool") == "bash" and event.get("args") == {"command": command} and isinstance(event.get("call_id"), str)
    return {"type": "approval_decision", "call_id": event.get("call_id"), "tool": event.get("tool"),
            "exactCommand": bool(exact), "decision": "once" if exact else "deny"}


def run(argv, cwd, command, seconds):
    master, slave = pty.openpty()
    process = subprocess.Popen(argv, cwd=cwd, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    chunks = []; partial = b""; decisions = []; answered = set(); start = time.monotonic(); terminal_closed = False
    try:
        while time.monotonic() - start < seconds:
            ready, _, _ = select.select([master], [], [], 0.2)
            if ready:
                try:
                    data = os.read(master, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        terminal_closed = True; break
                    raise
                if not data:
                    terminal_closed = True; break
                chunks.append(data); partial += data
                while b"\n" in partial:
                    line, partial = partial.split(b"\n", 1)
                    try: event = json.loads(ANSI.sub("", line.decode(errors="replace").strip()))
                    except ValueError: continue
                    decision = approval(event, command)
                    if decision is not None and decision["call_id"] not in answered:
                        answered.add(decision["call_id"]); decisions.append(decision)
                        os.write(master, b"y\n" if decision["decision"] == "once" else b"n\n")
            elif process.poll() is not None:
                break
        if terminal_closed and process.poll() is None:
            try: process.wait(timeout=1)
            except subprocess.TimeoutExpired: pass
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); process.wait()
            code = 124
        else:
            code = process.returncode
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM); process.wait(timeout=8)
        os.close(master)
    lines = [ANSI.sub("", line).strip() for line in b"".join(chunks).decode(errors="replace").splitlines()]
    return code, lines, decisions
