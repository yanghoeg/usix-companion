#!/usr/bin/env python3
"""Controlled live v2 witness; model/tool correlation is verified separately."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

HEALTH_KEYS = ("ok", "auth", "paired", "listener", "accessibility")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, choices=("usix", "usix-termux"))
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--witness", required=True, type=Path)
    args = parser.parse_args()
    if not args.profile.is_absolute() or not args.witness.is_absolute():
        parser.error("explicit absolute profile/witness paths required")
    root = Path(__file__).resolve().parents[1]
    cli = root / ".venv-integration/bin/companion-v2"
    args.witness.parent.mkdir(parents=True, exist_ok=True)
    try:
        record_file = args.witness.open("x", encoding="utf-8")
    except FileExistsError:
        print('{"error":"Witness already exists; no stale evidence reused or call repeated"}')
        return 1
    start = time.monotonic()
    record = {"runtimeLabel": args.runtime, "operation": "v2.capabilities+health", "invocationCwd": str(Path.cwd().resolve()),
              "recordedAt": datetime.now(timezone.utc).isoformat(), "source": "real device through standalone CLI; model correlation is separate"}
    try:
        values = []
        for command in ("capabilities", "health"):
            result = subprocess.run([str(cli), "--profile", str(args.profile), command], capture_output=True, text=True, timeout=40)
            if result.returncode != 0:
                raise ValueError("Live CLI request failed")
            values.append(json.loads(result.stdout))
        capabilities, observation = values
        if capabilities.get("contractVersion") != "usix-companion.device/v2" or capabilities.get("kind") != "capabilities" or observation.get("kind") != "observation":
            raise ValueError("Expected v2 messages missing")
        health = observation["health"]
        if any(type(health.get(key)) is not bool for key in HEALTH_KEYS) or not all(health[key] for key in ("ok", "auth", "paired")):
            raise ValueError("Authenticated health not established")
        record.update(helperExitCode=0, health={key: health[key] for key in HEALTH_KEYS},
                      contextSha256=hashlib.sha256(json.dumps(observation["context"], sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                      supportedVersions=capabilities["supportedVersions"], capabilities=[{key: entry[key] for key in ("operation", "supported", "readiness")} for entry in capabilities["capabilities"]])
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired):
        record.update(helperExitCode=1, error="Live v2 capabilities/health witness not established")
    record["elapsedMs"] = round((time.monotonic() - start) * 1000)
    with record_file:
        record_file.write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(record, ensure_ascii=False))
    return record["helperExitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
