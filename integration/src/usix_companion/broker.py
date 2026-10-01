"""Supervised routing only: TLS ingress + owning-user Unix IPC, no model/scheduler."""
import argparse
import asyncio
import hashlib
import hmac
import json
import os
import secrets
import signal
import socket
import ssl
import stat
import struct
import uuid
from pathlib import Path
from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.http11 import Response
from . import CONTRACT
from .client import absolute, private_json, save_private
from .contract import loads, dumps


def failure(code, message, possible=False, action_id=None):
    result = {"contractVersion": CONTRACT, "kind": "error", "error": {"code": code, "message": message},
              "effect": "possible" if possible else "none", "retry": {"decision": "reconcile" if possible else "never",
              "reason": "Read the action receipt before another effect" if possible else "Wait for the paired device"}}
    if action_id:
        result["actionId"] = action_id
    return result


class Broker:
    def __init__(self, config):
        self.config = config
        self.device = None
        self.pending = {}
        self.sequence = 0
        self.stop = asyncio.Event()
        self.ipc_slots = asyncio.Semaphore(16)

    async def authenticate(self, connection, request):
        token = request.headers.get("Authorization", "")
        valid = (request.path == "/v2/device" and token.startswith("Bearer ")
                 and request.headers.get("X-Companion-Device-Id") == self.config["deviceId"]
                 and hmac.compare_digest(hashlib.sha256(token[7:].encode()).hexdigest(), self.config["deviceBearerHash"]))
        if not valid:
            return Response(401, "Unauthorized", Headers({"Content-Type": "application/json"}), dumps(failure("IdentityMismatch", "Paired device channel required")))
        return None

    async def device_connection(self, connection):
        try:
            hello = loads(await asyncio.wait_for(connection.recv(), 10))
            if (set(hello) != {"contractVersion", "kind", "deviceId", "connectionId"} or hello["contractVersion"] != CONTRACT
                    or hello["kind"] != "hello" or hello["deviceId"] != self.config["deviceId"]):
                await connection.close(1008, "Invalid device identity"); return
            uuid.UUID(hello["connectionId"])
            previous = self.device
            self.device = connection
            self.sequence = 0
            self._disconnect_pending()
            if previous is not None:
                await previous.close(1012, "Device channel replaced")
            async for raw in connection:
                if self.device is not connection:
                    break
                message = loads(raw, 1048576)
                if (set(message) != {"contractVersion", "kind", "channelRequestId", "status", "body"}
                        or message["contractVersion"] != CONTRACT or message["kind"] != "response"
                        or not isinstance(message["body"], dict) or message["body"].get("contractVersion") != CONTRACT):
                    await connection.close(1008, "Invalid device response"); break
                pending = self.pending.pop(message["channelRequestId"], None)
                if pending and not pending.done():
                    pending.set_result(message["body"])
        except (Exception, asyncio.CancelledError):
            pass
        finally:
            if self.device is connection:
                self.device = None; self._disconnect_pending()

    def _disconnect_pending(self):
        for pending in self.pending.values():
            if not pending.done():
                pending.set_exception(ConnectionError("Device disconnected"))
        self.pending.clear()

    async def route(self, request):
        if set(request) != {"deviceId", "path", "bearer", "body"} or request["deviceId"] != self.config["deviceId"]:
            return failure("IdentityMismatch", "Explicit selected device required")
        path = request["path"]
        if (not isinstance(path, str) or not path.startswith("/v2/") or path == "/v2/admin/remote"
                or not isinstance(request["bearer"], str) or not 16 <= len(request["bearer"]) <= 128
                or not isinstance(request["body"], dict) or len(dumps(request["body"])) > 65536):
            return failure("InvalidRequest", "Invalid broker request")
        possible = path == "/v2/execute"
        action_id = request["body"].get("actionId")
        if self.device is None:
            return failure("Offline", "Paired device is offline; no request dispatched")
        if len(self.pending) >= 16:
            return failure("Busy", "Device request queue is full; no request dispatched")
        channel = str(uuid.uuid4())
        pending = asyncio.get_running_loop().create_future()
        self.pending[channel] = pending
        self.sequence += 1
        connection = self.device
        try:
            await connection.send(dumps({"contractVersion": CONTRACT, "kind": "request", "channelRequestId": channel,
                                         "sequence": self.sequence, "path": path, "bearer": request["bearer"], "body": request["body"]}).decode())
            return await asyncio.wait_for(pending, 31)
        except (ConnectionError, OSError, asyncio.TimeoutError, Exception):
            return failure("UnknownEffect" if possible else "Offline", "Device acknowledgement unavailable", possible, action_id)
        finally:
            self.pending.pop(channel, None)

    async def ipc_connection(self, reader, writer):
        try:
            stream = writer.get_extra_info("socket")
            if not hasattr(socket, "SO_PEERCRED"):
                raise PermissionError("Owning-user IPC unavailable on this platform")
            _, uid, _ = struct.unpack("3i", stream.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if uid != os.getuid():
                raise PermissionError("Wrong IPC owner")
            async with self.ipc_slots:
                raw = await asyncio.wait_for(reader.readuntil(b"\n"), 10)
                result = await self.route(loads(raw, 131072))
                writer.write(dumps(result) + b"\n")
                await asyncio.wait_for(writer.drain(), 5)
        except (Exception, asyncio.CancelledError):
            # Never echo arbitrary requests, tokens, device payloads or stack traces.
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    async def run(self):
        ipc = absolute(self.config["socket"])
        parent = ipc.parent
        parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if parent.stat().st_uid != os.getuid() or parent.stat().st_mode & 0o077:
            raise ValueError("Broker IPC directory must be private to the owning user")
        if ipc.exists() or ipc.is_symlink():
            details = ipc.lstat()
            if not stat.S_ISSOCK(details.st_mode) or details.st_uid != os.getuid():
                raise ValueError("Refusing to replace a non-owned IPC socket")
            probe = socket.socket(socket.AF_UNIX)
            try:
                probe.connect(str(ipc))
            except ConnectionRefusedError:
                ipc.unlink()
            else:
                raise ValueError("Broker socket is already serving")
            finally:
                probe.close()
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(str(absolute(self.config["certificate"])), str(absolute(self.config["key"])))
        loop = asyncio.get_running_loop()
        for signum in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(signum, self.stop.set)
        server = await asyncio.start_unix_server(self.ipc_connection, path=str(ipc), limit=131072)
        os.chmod(ipc, 0o600)
        try:
            async with server, serve(self.device_connection, self.config["listen"], self.config["port"], ssl=tls,
                                    process_request=self.authenticate, max_size=1048576, max_queue=16,
                                    open_timeout=10, close_timeout=5, ping_interval=15, ping_timeout=15):
                print(json.dumps({"kind": "broker_ready", "deviceId": self.config["deviceId"], "socket": str(ipc)}), flush=True)
                await self.stop.wait()
        finally:
            self._disconnect_pending()
            if ipc.exists():
                ipc.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    start = subcommands.add_parser("serve")
    start.add_argument("--config", required=True)
    init = subcommands.add_parser("init")
    init.add_argument("--config", required=True)
    init.add_argument("--phone-config", required=True)
    init.add_argument("--device-id", required=True, type=lambda value: str(uuid.UUID(value)))
    init.add_argument("--certificate", required=True)
    init.add_argument("--key", required=True)
    init.add_argument("--host", required=True, help="certificate hostname used by the phone")
    init.add_argument("--listen", default="127.0.0.1", help="explicit TLS ingress binding")
    init.add_argument("--port", type=int, default=8443)
    init.add_argument("--socket", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            if not 1 <= args.port <= 65535 or absolute(args.config).exists() or absolute(args.phone_config).exists():
                raise ValueError("Invalid port or existing configuration")
            certificate = absolute(args.certificate).resolve(strict=True)
            key = absolute(args.key).resolve(strict=True)
            bearer = secrets.token_hex(32)
            save_private(args.config, {"configVersion": 2, "deviceId": args.device_id, "deviceBearerHash": hashlib.sha256(bearer.encode()).hexdigest(),
                                      "listen": args.listen, "port": args.port, "certificate": str(certificate), "key": str(key), "socket": str(absolute(args.socket))})
            save_private(args.phone_config, {"deviceId": args.device_id, "url": "wss://" + args.host + ":" + str(args.port) + "/v2/device",
                                            "deviceBearer": bearer, "trustedCertificatePem": certificate.read_text()})
            print(json.dumps({"kind": "broker_configured", "config": str(absolute(args.config)), "phoneConfig": str(absolute(args.phone_config)), "credentialsOmitted": True}))
        else:
            config = private_json(args.config)
            if config.get("configVersion") != 2:
                raise ValueError("Unsupported broker config")
            asyncio.run(Broker(config).run())
        return 0
    except (Exception, KeyboardInterrupt):
        print(json.dumps(failure("InvalidRequest", "Broker configuration/start failed; private details omitted")))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
