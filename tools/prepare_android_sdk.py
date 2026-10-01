#!/usr/bin/env python3
"""Install the audited SDK archives into Companion's ignored local build cache."""
from __future__ import annotations

import hashlib
import shutil
import stat
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".build-tools"
SDK = CACHE / "android-sdk"
ARCHIVES = (
    ("platform-34-ext7_r03.zip", "platforms", "android-34",
     "16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3"),
    ("build-tools_r34-linux.zip", "build-tools", "34.0.0",
     "e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f"),
)


def install(name, group, version, expected):
    archive = CACHE / name
    if archive.is_symlink():
        raise ValueError("SDK archive must not be a symlink")
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=CACHE, delete=False) as f:
            downloaded = Path(f.name)
            try:
                with urllib.request.urlopen(
                        "https://dl.google.com/android/repository/" + name, timeout=60) as response:
                    total = 0
                    while block := response.read(1024 * 1024):
                        total += len(block)
                        if total > 80 * 1024 * 1024:
                            raise ValueError("SDK archive exceeds download limit")
                        f.write(block)
                f.flush()
                with downloaded.open("rb") as downloaded_file:
                    if hashlib.file_digest(downloaded_file, "sha256").hexdigest() != expected:
                        raise ValueError("SDK download checksum mismatch")
                downloaded.replace(archive)
            finally:
                downloaded.unlink(missing_ok=True)
    with archive.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != expected:
            raise ValueError("SDK archive checksum mismatch")
    folder = SDK / group
    if SDK.is_symlink() or folder.is_symlink():
        raise ValueError("SDK folders must not be symlinks")
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / version
    if target.is_symlink():
        raise ValueError("SDK target must not be a symlink")
    if not target.exists():
        with tempfile.TemporaryDirectory(dir=folder) as temporary, zipfile.ZipFile(archive) as z:
            roots = set()
            for info in z.infolist():
                path = PurePosixPath(info.filename)
                if path.is_absolute() or ".." in path.parts or not path.parts:
                    raise ValueError("unsafe SDK archive path")
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise ValueError("SDK symlinks are unsupported")
                roots.add(path.parts[0])
            if len(roots) != 1:
                raise ValueError("SDK archive must have one root folder")
            z.extractall(temporary)
            for info in z.infolist():
                extracted_file = Path(temporary) / info.filename
                if extracted_file.is_file():
                    extracted_file.chmod(0o755 if (info.external_attr >> 16) & 0o111 else 0o644)
            extracted = Path(temporary) / roots.pop()
            if not (extracted / "source.properties").is_file():
                raise ValueError("SDK package metadata missing")
            shutil.move(str(extracted), target)
    print(f"Verified {name}: {expected}; SDK package {group}/{version}")


def main():
    if CACHE.is_symlink():
        raise ValueError("build cache must not be a symlink")
    CACHE.mkdir(exist_ok=True)
    for archive in ARCHIVES:
        install(*archive)
    print(f"ANDROID_HOME={SDK}")
    print("Linux SDK executables do not run on Android; use Termux aapt2 there.")


if __name__ == "__main__":
    main()
