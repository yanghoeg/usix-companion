"""Bounded observation/action arguments; exported captures stay in the captured workspace."""
import base64
import hashlib
import os
import struct
import time
import uuid
from pathlib import Path
from .client import IntegrationFailure
from .contract import Contracts, loads, payload_hash, utc

UI_OPERATIONS = ("ui.click", "ui.select", "ui.set_text", "ui.scroll", "ui.long_press", "ui.tap", "ui.swipe", "ui.back", "ui.home")
COMMANDS = {"observe", "wait", "cancel-wait", "capture", "ui", "verify"}


def add_commands(commands):
    uid = lambda value: str(uuid.UUID(value))
    observe = commands.add_parser("observe")
    observe.add_argument("--snapshot-ref", type=uid)
    observe.add_argument("--selector", default="null")
    observe.add_argument("--offset", type=int, default=0)
    observe.add_argument("--limit", type=int, default=128)
    wait = commands.add_parser("wait")
    wait.add_argument("--kind", choices=["node", "text", "window", "changed"], default="node")
    wait.add_argument("--selector", default="null")
    wait.add_argument("--baseline", type=uid)
    wait.add_argument("--timeout-ms", type=int, default=30000)
    wait.add_argument("--cancellation-id", type=uid, required=True)
    cancel = commands.add_parser("cancel-wait")
    cancel.add_argument("--cancellation-id", type=uid, required=True)
    capture = commands.add_parser("capture")
    capture.add_argument("--snapshot-ref", type=uid, required=True)
    capture.add_argument("--language", choices=["latin", "korean"])
    capture.add_argument("--output", help="new PNG path within the captured workspace; omitted means OCR/metadata only")
    ui = commands.add_parser("ui")
    ui.add_argument("--operation", choices=UI_OPERATIONS, required=True)
    ui.add_argument("--action-id", type=uid, required=True)
    ui.add_argument("--snapshot-ref", type=uid, required=True)
    ui.add_argument("--payload", default="{}")
    ui.add_argument("--cancellation-id", type=uid)
    verify = commands.add_parser("verify")
    verify.add_argument("--action-id", type=uid, required=True)
    verify.add_argument("--goal", required=True)


def scope(profile, snapshot=None):
    return {"packageId": profile["packageId"], "accountRef": profile.get("accountRef"), "resourceRefs": [], "snapshotRef": snapshot}


def request(args, profile, packet):
    body = packet()
    if args.command == "observe":
        body.update(scope=scope(profile, args.snapshot_ref), selector=loads(args.selector), offset=args.offset, limit=args.limit)
    elif args.command == "wait":
        if not 1 <= args.timeout_ms <= 30000:
            raise ValueError("Wait must have a deadline within 30 seconds")
        body.update(scope=scope(profile, args.baseline), waitKind=args.kind, selector=loads(args.selector),
                    deadline=utc(int(time.time() * 1000) + args.timeout_ms), cancellationId=args.cancellation_id)
    elif args.command == "cancel-wait":
        body.update(cancellationId=args.cancellation_id)
    elif args.command == "capture":
        body.update(scope=scope(profile, args.snapshot_ref), language=args.language)
        if args.output:
            export_path(profile, args.output)
    elif args.command == "verify":
        body.update(actionId=args.action_id, goal=loads(args.goal))
    elif args.command == "ui":
        body.update(kind="command", context=profile["context"], actionId=args.action_id, operation=args.operation,
                    scope=scope(profile, args.snapshot_ref), payload=loads(args.payload), controllerLease=profile["lease"],
                    deadline=utc(int(time.time() * 1000) + 30000), cancellationId=args.cancellation_id,
                    authority={"kind": "grant", "ref": profile["grantRef"]})
        body["payloadHash"] = payload_hash(body)
    Contracts().validate("command" if args.command == "ui" else "observation-query", body)
    paths = {"observe": "/v2/observe", "wait": "/v2/wait", "cancel-wait": "/v2/wait/cancel", "capture": "/v2/capture", "verify": "/v2/verify", "ui": "/v2/execute"}
    return paths[args.command], body


def export_path(profile, requested):
    workspace = Path(profile["workspace"]).resolve(strict=True)
    path = Path(requested)
    target = (path if path.is_absolute() else workspace / path).resolve()
    if not target.is_relative_to(workspace) or target == workspace or target.exists():
        raise ValueError("Capture export needs a new file inside the captured workspace")
    return target


def summarize_visual(profile, result, requested):
    Contracts().validate("visual", result)
    expected = scope(profile)
    if result["context"] != profile["context"] or result["scope"] != expected:
        raise IntegrationFailure("IdentityMismatch", "Capture belongs to a different package/account/context")
    png = base64.b64decode("".join(result["pngChunks"]), validate=True)
    if len(png) > 524288 or not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png) < 24 or struct.unpack(">II", png[16:24]) != (result["width"], result["height"]):
        raise ValueError("Invalid or oversized PNG capture")
    if "sha256:" + hashlib.sha256(png).hexdigest() != result["contentHash"]:
        raise ValueError("Capture content hash differs from the device response")
    target = None
    if requested:
        target = export_path(profile, requested)
        target.parent.mkdir(parents=True, exist_ok=True)
        with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
            output.write(png)
    return {**{key: value for key, value in result.items() if key != "pngChunks"}, "kind": "visual_summary", "imagePath": str(target) if target else None}
