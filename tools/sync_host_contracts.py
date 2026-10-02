#!/usr/bin/env python3
"""Copy/pin the Companion schemas for installable standalone host packaging."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    destination = root / "integration/src/usix_companion/contracts"
    errors = []
    if not args.check:
        destination.mkdir(parents=True, exist_ok=True)
    for source in sorted((root / "contracts/device/v2").glob("*.schema.json")):
        target = destination / source.name
        if args.check:
            if not target.exists() or source.read_bytes() != target.read_bytes():
                errors.append(source.name)
        else:
            target.write_bytes(source.read_bytes())
    if errors:
        raise SystemExit("Packaged contract drift: " + ", ".join(errors))
    print("Standalone host schemas match the Companion contract.")


if __name__ == "__main__":
    main()
