#!/usr/bin/env python3
"""Read-only runtime/source/binary identity capture; never build or modify a target."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def capture(root: Path, binary: str):
    root = root.resolve()
    revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    status = subprocess.check_output(["git", "--no-optional-locks", "-C", str(root), "status", "--porcelain=v1"], text=True).splitlines()
    files = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).split(b"\0")
    digest = hashlib.sha256()
    for name in sorted(x for x in files if x):
        path = root / name.decode("utf-8")
        digest.update(name + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b"missing")
    installed = shutil.which(binary)
    return {"revision": revision, "status": status, "trackedFileCount": sum(bool(x) for x in files),
            "trackedContentSha256": digest.hexdigest(), "installedBinary": str(Path(installed).resolve()) if installed else None,
            "installedBinarySha256": hashlib.sha256(Path(installed).read_bytes()).hexdigest() if installed else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--usix", required=True, type=Path)
    parser.add_argument("--termux", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "usix": capture(args.usix, "usix"),
              "usix-termux": capture(args.termux, "usix-termux")}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
