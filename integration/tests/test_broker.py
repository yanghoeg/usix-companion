import asyncio
import hashlib
from pathlib import Path
import socket
import ssl
import tempfile
import unittest
import uuid
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus
from usix_companion import CONTRACT
from usix_companion.broker import Broker
from usix_companion.contract import dumps, loads
from tls_fixture import create_tls


class BrokerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        certificate, key = create_tls(self.root)
        with socket.socket() as port:
            port.bind(("127.0.0.1", 0)); self.port = port.getsockname()[1]
        self.device_id = str(uuid.uuid4()); self.token = "synthetic-device-channel-bearer-value"
        self.broker = Broker({"deviceId": self.device_id, "deviceBearerHash": hashlib.sha256(self.token.encode()).hexdigest(),
                              "listen": "127.0.0.1", "port": self.port, "socket": str(self.root / "broker.sock"), "certificate": str(certificate), "key": str(key)})
        self.tls = ssl.create_default_context(cafile=str(certificate))
        self.task = asyncio.create_task(self.broker.run())
        for _ in range(100):
            if (self.root / "broker.sock").exists():
                await asyncio.sleep(0.05); break
            if self.task.done():
                await self.task
            await asyncio.sleep(0.05)
        self.connections = []

    async def asyncTearDown(self):
        for connection in self.connections:
            await connection.close()
        self.broker.stop.set()
        await asyncio.wait_for(self.task, 5)
        self.directory.cleanup()

    async def device(self):
        connection = await connect(f"wss://127.0.0.1:{self.port}/v2/device", ssl=self.tls,
                                   additional_headers={"Authorization": "Bearer " + self.token, "X-Companion-Device-Id": self.device_id})
        self.connections.append(connection)
        await connection.send(dumps({"contractVersion": CONTRACT, "kind": "hello", "deviceId": self.device_id, "connectionId": str(uuid.uuid4())}).decode())
        for _ in range(100):
            if self.broker.device is not None:
                break
            await asyncio.sleep(0.01)
        return connection

    def request(self, path="/v2/health"):
        return {"deviceId": self.device_id, "path": path, "bearer": "synthetic-session-bearer", "body": {"contractVersion": CONTRACT, "requestId": str(uuid.uuid4())}}

    async def test_tls_device_authentication_is_required(self):
        with self.assertRaises(InvalidStatus):
            await connect(f"wss://127.0.0.1:{self.port}/v2/device", ssl=self.tls,
                          additional_headers={"Authorization": "Bearer wrong", "X-Companion-Device-Id": self.device_id})
        self.assertIsNone(self.broker.device)

    async def test_real_tls_channel_and_owner_ipc_route_one_correlated_result(self):
        device = await self.device()
        reader, writer = await asyncio.open_unix_connection(str(self.root / "broker.sock"))
        writer.write(dumps(self.request()) + b"\n"); await writer.drain()
        forwarded = loads(await asyncio.wait_for(device.recv(), 2), 131072)
        self.assertEqual(1, forwarded["sequence"]); self.assertEqual("/v2/health", forwarded["path"])
        expected = {"contractVersion": CONTRACT, "kind": "observation", "health": {"ok": True}}
        await device.send(dumps({"contractVersion": CONTRACT, "kind": "response", "channelRequestId": forwarded["channelRequestId"], "status": 200, "body": expected}).decode())
        self.assertEqual(expected, loads(await asyncio.wait_for(reader.readline(), 2)))
        self.assertEqual(0o600, (self.root / "broker.sock").stat().st_mode & 0o777)
        writer.close(); await writer.wait_closed()

    async def test_lost_acknowledgement_is_unknown_and_never_replayed(self):
        device = await self.device()
        request = self.request("/v2/execute"); request["body"]["actionId"] = str(uuid.uuid4())
        result = asyncio.create_task(self.broker.route(request))
        await asyncio.wait_for(device.recv(), 2)
        await device.close()
        uncertain = await asyncio.wait_for(result, 2)
        self.assertEqual("UnknownEffect", uncertain["error"]["code"])
        self.assertEqual("possible", uncertain["effect"]); self.assertEqual("reconcile", uncertain["retry"]["decision"])
        new = await self.device()
        with self.assertRaises(asyncio.TimeoutError):
            await asyncio.wait_for(new.recv(), 0.1)

    async def test_device_binding_and_remote_endpoint_changes_are_rejected(self):
        request = self.request(); request["deviceId"] = str(uuid.uuid4())
        self.assertEqual("IdentityMismatch", (await self.broker.route(request))["error"]["code"])
        self.assertEqual("InvalidRequest", (await self.broker.route(self.request("/v2/admin/remote")))["error"]["code"])

    async def test_offline_before_dispatch_reports_no_effect(self):
        response = await self.broker.route(self.request("/v2/execute"))
        self.assertEqual("Offline", response["error"]["code"]); self.assertEqual("none", response["effect"])


if __name__ == "__main__":
    unittest.main()
