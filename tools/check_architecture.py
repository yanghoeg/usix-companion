#!/usr/bin/env python3
"""Source boundaries complement Gradle's declared and resolved dependency checks."""
from __future__ import annotations

import argparse
from pathlib import Path
import re

MODULES = {
    "core/domain": ("domain", set()),
    "core/application": ("application", {"domain"}),
    "protocol": ("protocol", set()),
    "adapters/android": ("adapters.android", {"domain", "application"}),
    "adapters/persistence": ("adapters.persistence", {"domain", "application"}),
    "adapters/transport": ("adapters.transport", {"domain", "application", "protocol"}),
    "feature/control": ("feature.control", {"domain", "application"}),
    "testing/fixtures": ("testing", {"domain", "application"}),
    "app": ("", {"domain", "application", "protocol", "adapters.android", "adapters.persistence", "adapters.transport", "feature.control"}),
}
PREFIX = "dev.usix.companion."
IMPORT = re.compile(r"^\s*import\s+([\w.*]+)", re.MULTILINE)
AMBIENT = re.compile(
    r"\b(?:System|Thread|Runtime|ProcessBuilder|File|Files|Paths|Path|FileSystem|Socket|URL|URI|"
    r"Date|Calendar|Timer|TimeSource|measureTime|measureTimedValue|Random|SecureRandom|GlobalScope|Dispatchers|ClassLoader|classLoader)\b|"
    r"\b(?:currentTimeMillis|nanoTime|getenv|getProperty|randomUUID|readText|writeText|readBytes|writeBytes|measureTime|measureTimedValue)\s*\(|"
    r"\bClass\s*\.\s*forName\s*\(|"
    r"\b(?:Instant|Clock|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime)\s*\.\s*(?:now|system\w*)\s*\("
)
PURE_JAVA = {"java.math.BigDecimal", "java.math.BigInteger", "java.util.Locale", "java.util.Collections"}


def code_only(text: str) -> str:
    """Mask comments/literal text but retain executable Kotlin string templates."""
    def scan(index: int, template: bool = False) -> tuple[str, int]:
        output: list[str] = []
        depth = 0
        while index < len(text):
            if template and text[index] == "}" and depth == 0:
                return "".join(output), index + 1
            if text.startswith("//", index):
                end = text.find("\n", index)
                index = len(text) if end < 0 else end
                output.append(" ")
            elif text.startswith("/*", index):
                level = 1
                index += 2
                while index < len(text) and level:
                    if text.startswith("/*", index):
                        level += 1
                        index += 2
                    elif text.startswith("*/", index):
                        level -= 1
                        index += 2
                    else:
                        if text[index] == "\n":
                            output.append("\n")
                        index += 1
                output.append(" ")
            elif text[index] in {"\"", "'"}:
                delimiter = '"""' if text.startswith('"""', index) else text[index]
                index += len(delimiter)
                output.append(" ")
                while index < len(text) and not text.startswith(delimiter, index):
                    if delimiter != '"""' and text[index] == "\\":
                        index += 2
                    elif delimiter != "'" and text.startswith("${", index):
                        expression, index = scan(index + 2, template=True)
                        output.extend((" ", expression, " "))
                    else:
                        if text[index] == "\n":
                            output.append("\n")
                        index += 1
                index += len(delimiter)
                output.append(" ")
            else:
                if template:
                    depth += int(text[index] == "{") - int(text[index] == "}")
                output.append(text[index])
                index += 1
        return "".join(output), index
    return scan(0)[0]


def violations(module: str, text: str) -> list[str]:
    own, allowed = MODULES[module]
    code = code_only(text)
    errors: list[str] = []
    for match in re.finditer(r"\bdev\.usix\.companion\.([\w.]+)", code):
        suffix = match.group(1)
        target = next((part for part, _ in MODULES.values() if part and (suffix == part or suffix.startswith(part + "."))), None)
        if target and target != own and target not in allowed:
            errors.append(f"outward reference: {match.group()}")
        elif not target and own:
            errors.append(f"reference to app composition root: {match.group()}")
    if module.startswith("core/"):
        for name in IMPORT.findall(code):
            permitted = (
                name.startswith(PREFIX) or
                (name.startswith("kotlin.") and not name.startswith(("kotlin.io.", "kotlin.system.", "kotlin.concurrent.", "kotlin.random.", "kotlin.reflect."))) or
                (module == "core/application" and name.startswith("kotlinx.coroutines.")) or name in PURE_JAVA
            )
            if not permitted:
                errors.append(f"forbidden core import: {name}")
        for match in AMBIENT.finditer(code):
            errors.append(f"ambient core API: {match.group()}")
        if re.search(r"\b(?:android|androidx|org\.json|java\.(?:io|nio|net)|javax\.inject|dagger)\s*\.", code):
            errors.append("forbidden fully qualified core API")
    if module == "protocol" and re.search(r"\b(?:android|androidx)\s*\.", code):
        errors.append("Android API in the JVM wire module")
    return list(dict.fromkeys(errors))


def check(root: Path) -> list[str]:
    errors = []
    for module in MODULES:
        directory = root / module / "src"
        for path in sorted(directory.rglob("*")) if directory.exists() else []:
            if path.suffix in {".kt", ".java"} and "test" not in path.relative_to(directory).parts[0].lower():
                errors.extend(f"{path.relative_to(root)}: {error}" for error in violations(module, path.read_text()))
    tests = root / "core/application/src/test"
    for path in tests.rglob("*.kt") if tests.exists() else []:
        if re.search(r"getDeclaredMethod|isAccessible|UiController|NotifStore|BridgeServer", code_only(path.read_text())):
            errors.append(f"{path.relative_to(root)}: application tests must use injected ports/fakes")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = check(args.root)
    if errors:
        print("\n".join(errors))
        return 1
    print("Source imports and APIs obey module boundaries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
