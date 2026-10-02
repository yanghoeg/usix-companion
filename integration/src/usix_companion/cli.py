"""Explicit captured profiles; device credentials never appear in command arguments/output."""
import argparse
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from . import CONTRACT
from .client import IntegrationFailure, absolute, call, private_json, save_private
from .contract import Contracts, loads, payload_hash, utc
from . import observations


def packet(**fields):
    return {"contractVersion": CONTRACT, "requestId": str(uuid.uuid4()), **fields}


def owner_token(path):
    path = absolute(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError("Owner token must be a private owning-user file")
    token = path.read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", token):
        raise ValueError("Invalid owner pairing token")
    return token


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, help="explicit absolute profile path")
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("setup", help="trusted user setup; do not delegate profile creation to a model")
    setup.add_argument("--workspace", required=True)
    setup.add_argument("--runtime", required=True, choices=["usix", "usix-termux"])
    setup.add_argument("--package", default=None)
    setup.add_argument("--account-ref", type=lambda value: str(uuid.UUID(value)))
    setup.add_argument("--fixture-ui", action="store_true", help="controlled Companion fixture only; does not authorize general app effects")
    setup.add_argument("--owner-token-file", default=str(Path.home() / ".usix/companion_token"))
    connection = setup.add_mutually_exclusive_group()
    connection.add_argument("--endpoint", default="http://127.0.0.1:8760")
    connection.add_argument("--broker-socket")
    setup.add_argument("--device-id", help="required for the broker profile")
    renew = commands.add_parser("renew", help="trusted owner renewal of the same captured context and remaining budget")
    renew.add_argument("--owner-token-file", default=str(Path.home() / ".usix/companion_token"))
    for command in ("health", "capabilities", "acquire", "release", "resync"):
        commands.add_parser(command)
    for command in ("receipt", "cancel", "open"):
        action = commands.add_parser(command)
        action.add_argument("--action-id", required=True, type=lambda value: str(uuid.UUID(value)))
    execute = commands.add_parser("execute")
    execute.add_argument("--request-file", default="-", help="bounded JSON command, with the captured context")
    events = commands.add_parser("events")
    events.add_argument("--cursor", type=int, default=0)
    events.add_argument("--limit", type=int, default=128)
    ack = commands.add_parser("ack")
    ack.add_argument("--cursor", type=int, required=True)
    observations.add_commands(commands)
    args = parser.parse_args(argv)
    sent_effect = False
    sent_action_id = None
    try:
        profile_path = absolute(args.profile)
        if args.command == "setup":
            if profile_path.exists():
                raise ValueError("Profile already exists; use a new trusted setup path")
            workspace = absolute(args.workspace).resolve(strict=True)
            if not workspace.is_dir():
                raise ValueError("Captured workspace must be a directory")
            connection = {"transport": "broker", "socket": str(absolute(args.broker_socket)), "deviceId": str(uuid.UUID(args.device_id))} if args.broker_socket else {"transport": "loopback", "endpoint": args.endpoint}
            bearer = owner_token(args.owner_token_file)
            challenge = call(connection, "/v2/pair/challenge", bearer, packet())
            if challenge.get("kind") != "pairing_challenge":
                print(json.dumps(challenge, ensure_ascii=False)); return 1
            context = {"deviceId": challenge["deviceId"], "runtimeId": str(uuid.uuid4()), "sessionId": str(uuid.uuid4()),
                       "taskId": str(uuid.uuid4()), "taskRevision": 1, "workspaceId": str(uuid.uuid4())}
            paired = call(connection, "/v2/pair/complete", bearer, packet(challengeId=challenge["challengeId"], nonce=challenge["nonce"], context=context,
                          packageId=args.package, displayName="USIX" if args.runtime == "usix" else "USIX Termux", accountRef=args.account_ref, fixtureUi=args.fixture_ui))
            if paired.get("kind") != "paired":
                print(json.dumps(paired, ensure_ascii=False)); return 1
            if paired.get("context") != context:
                raise ValueError("Pairing response replaced the captured context")
            profile = {"profileVersion": 2, "connection": connection, "context": context, "workspace": str(workspace), "runtime": args.runtime,
                       "packageId": args.package, "accountRef": args.account_ref, "fixtureUi": args.fixture_ui, "bearer": paired["bearer"], "grantRef": paired["grantRef"], "expiresAt": paired["expiresAt"], "lease": None}
            save_private(profile_path, profile)
            result = {"contractVersion": CONTRACT, "kind": "setup_saved", "profile": str(profile_path), "workspace": str(workspace),
                      "runtime": args.runtime, "expiresAt": paired["expiresAt"], "notice": "Context uses trusted setup correlation IDs; runtime dispatch authority remains separate"}
        elif args.command == "renew":
            profile = private_json(profile_path)
            if profile.get("profileVersion") != 2 or not Path(profile["workspace"]).is_absolute() or not Path(profile["workspace"]).is_dir():
                raise ValueError("Captured workspace moved or disappeared; explicit rebinding is required")
            bearer = owner_token(args.owner_token_file)
            challenge = call(profile["connection"], "/v2/pair/challenge", bearer, packet())
            if challenge.get("kind") != "pairing_challenge":
                print(json.dumps(challenge, ensure_ascii=False)); return 1
            if challenge.get("deviceId") != profile["context"]["deviceId"]:
                raise ValueError("Renewal selected a different device")
            paired = call(profile["connection"], "/v2/pair/complete", bearer, packet(challengeId=challenge["challengeId"], nonce=challenge["nonce"],
                          context=profile["context"], packageId=profile["packageId"], displayName="USIX" if profile["runtime"] == "usix" else "USIX Termux",
                          accountRef=profile.get("accountRef"), fixtureUi=profile.get("fixtureUi", False)))
            if paired.get("kind") != "paired":
                print(json.dumps(paired, ensure_ascii=False)); return 1
            if paired.get("context") != profile["context"]:
                raise ValueError("Renewal response replaced the captured context")
            profile.update(bearer=paired["bearer"], grantRef=paired["grantRef"], expiresAt=paired["expiresAt"], lease=None)
            save_private(profile_path, profile)
            result = {"contractVersion": CONTRACT, "kind": "renewed", "profile": str(profile_path), "expiresAt": paired["expiresAt"],
                      "notice": "Captured context, receipts, event cursors and remaining action budget preserved; acquire a new controller lease"}
        else:
            profile = private_json(profile_path)
            if profile.get("profileVersion") != 2 or not Path(profile["workspace"]).is_absolute():
                raise ValueError("Invalid captured profile")
            if not Path(profile["workspace"]).is_dir():
                raise ValueError("Captured workspace moved or disappeared; explicit rebinding is required")
            paths = {"health": "/v2/health", "capabilities": "/v2/capabilities", "acquire": "/v2/controller/acquire", "release": "/v2/controller/release",
                     "resync": "/v2/resync", "receipt": "/v2/receipt", "cancel": "/v2/cancel", "events": "/v2/events", "ack": "/v2/events/ack"}
            body = packet()
            if args.command in ("receipt", "cancel"):
                body["actionId"] = args.action_id
            elif args.command == "events":
                body.update(cursor=args.cursor, limit=args.limit)
            elif args.command == "ack":
                body["cursor"] = args.cursor
            if args.command in observations.COMMANDS:
                route, body = observations.request(args, profile, packet)
                sent_effect = args.command == "ui"
                sent_action_id = body.get("actionId") if sent_effect else None
                result = call(profile["connection"], route, profile["bearer"], body)
            elif args.command in ("open", "execute"):
                if args.command == "execute":
                    if args.request_file == "-": raw = sys.stdin.buffer.read(65537)
                    else:
                        with absolute(args.request_file).open("rb") as source: raw = source.read(65537)
                    body = loads(raw)
                else:
                    body.update(kind="command", context=profile["context"], actionId=args.action_id, operation="app.open", payload={},
                                scope=observations.scope(profile),
                                controllerLease=profile["lease"], deadline=utc(int(time.time() * 1000) + 30_000), cancellationId=None,
                                authority={"kind": "grant", "ref": profile["grantRef"]})
                    body["payloadHash"] = payload_hash(body)
                Contracts().validate("command", body)
                if body["context"] != profile["context"]:
                    raise IntegrationFailure("IdentityMismatch", "Command cannot replace the captured setup context")
                sent_effect = True
                sent_action_id = body["actionId"]
                result = call(profile["connection"], "/v2/execute", profile["bearer"], body)
            else:
                result = call(profile["connection"], paths[args.command], profile["bearer"], body)
            if result.get("kind") == "controller" and args.command == "acquire":
                profile["lease"] = result["lease"]; save_private(profile_path, profile)
            if result.get("kind") == "controller_released":
                profile["lease"] = None; save_private(profile_path, profile)
            if result.get("kind") in ("receipt", "capabilities", "snapshot", "visual"):
                Contracts().validate(result["kind"], result)
            if result.get("kind") in ("receipt", "observation", "snapshot", "visual") and result.get("context") != profile["context"]:
                raise IntegrationFailure("IdentityMismatch", "Response is bound to a different captured context")
            if result.get("kind") in ("snapshot", "visual") and result.get("scope") != observations.scope(profile):
                raise IntegrationFailure("IdentityMismatch", "Observation replaced the captured package/account scope")
            if result.get("kind") == "visual":
                result = observations.summarize_visual(profile, result, args.output)
        print(json.dumps(result, ensure_ascii=False))
        return 1 if result.get("kind") == "error" else 3 if result.get("state") in ("UnknownEffect", "NeedsVerification", "Executing") else 0
    except IntegrationFailure as failure:
        print(json.dumps(failure.value, ensure_ascii=False)); return 1
    except (OSError, ValueError, KeyError, TypeError):
        if sent_effect:
            print(json.dumps(IntegrationFailure("UnknownEffect", "Effect response could not be validated; inspect the action receipt", "possible", sent_action_id).value)); return 1
        print(json.dumps({"contractVersion": CONTRACT, "kind": "error", "error": {"code": "InvalidRequest", "message": "Invalid or unavailable trusted profile/request; credentials omitted"}})); return 1
    except Exception as failure:
        if sent_effect:
            print(json.dumps(IntegrationFailure("UnknownEffect", "Effect receipt failed shared contract validation", "possible", sent_action_id).value)); return 1
        # Schema diagnostics can contain private arguments; expose only their class.
        print(json.dumps({"contractVersion": CONTRACT, "kind": "error", "error": {"code": "InvalidRequest", "message": "Shared contract validation failed"}})); return 1


if __name__ == "__main__":
    raise SystemExit(main())
