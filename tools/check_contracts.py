#!/usr/bin/env python3
"""Validate the offline schemas and their checked-in synthetic examples."""
from __future__ import annotations

import sys

from device_contract import Contracts, SCHEMAS, load_json, payload_hash


def main():
    contracts = Contracts()
    examples = SCHEMAS / "examples"
    mappings = {
        "command-mail-send": "command", "receipt-unknown-effect": "receipt",
        "receipt-verified": "receipt", "capabilities": "capabilities",
        "approval": "authority", "grant": "authority", "event-gap": "event",
    }
    failures = []
    for name, schema in mappings.items():
        value = load_json((examples / (name + ".json")).read_bytes())
        failures.extend(f"{name}: {error}" for error in contracts.errors(schema, value))
    command = load_json((examples / "command-mail-send.json").read_bytes())
    state = load_json((examples / "trusted-state.json").read_bytes())
    failures.extend(contracts.command_issues(command, state))
    vector = load_json((examples / "payload-hash-vector.json").read_bytes())
    if payload_hash(command) != vector["sha256"]:
        failures.append("payload hash differs from the golden vector")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"PASS: {len(contracts.schemas)} schemas, {len(mappings)} wire examples, hash/context checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
