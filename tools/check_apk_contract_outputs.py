#!/usr/bin/env python3
"""Validate actual Kotlin-router test output against the shared device schemas."""
import sys
from pathlib import Path
from device_contract import Contracts, load_json

root = Path(__file__).resolve().parents[1]
folder = root / "adapters/transport/build/conformance"
contracts = Contracts()
paths = list(folder.glob("*.json"))
if len(paths) < 3:
    raise SystemExit("Run :adapters:transport:test to produce APK-router wire outputs")
for path in paths:
    value = load_json(path.read_bytes())
    errors = contracts.errors(value["kind"], value)
    if errors:
        raise SystemExit(str(path.name) + ": " + "; ".join(errors))
print(f"{len(paths)} actual Kotlin-router outputs conform to the shared schemas.")
