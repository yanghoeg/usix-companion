#!/usr/bin/env python3
"""Controlled P0 witness: one real v1 health call, no notification/screen data."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, choices=("usix", "usix-termux"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / ".build-tools" / (args.runtime + "-health-witness.json")
    output.parent.mkdir(exist_ok=True)
    # Reserve before dispatch; an accidental second tool call cannot hit the device.
    try:
        record_file = output.open("x", encoding="utf-8")
    except FileExistsError:
        print("Baseline witness already exists; no health request repeated.")
        return 1
    started = time.monotonic()
    record = {"runtimeLabel": args.runtime, "invocationCwd": str(Path.cwd().resolve()),
              "recordedAt": datetime.now(timezone.utc).isoformat(), "operation": "v1.health"}
    with record_file:
        try:
            result = subprocess.run([sys.executable, str(root / "tools/companion_http.py"), "health"],
                                    capture_output=True, text=True, timeout=15)
            record["helperExitCode"] = result.returncode
            value = json.loads(result.stdout)
            keys = ("ok", "auth", "paired", "listener", "accessibility")
            if not isinstance(value, dict) or any(not isinstance(value.get(k), bool) for k in keys):
                raise ValueError("unexpected health response")
            record["health"] = {k: value[k] for k in keys}
        except (ValueError, OSError, subprocess.TimeoutExpired) as error:
            record["error"] = type(error).__name__
        record["elapsedMs"] = round((time.monotonic() - started) * 1000)
        record_file.write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(record, ensure_ascii=False))
    return 0 if record.get("helperExitCode") == 0 and "health" in record else 1


if __name__ == "__main__":
    raise SystemExit(main())
