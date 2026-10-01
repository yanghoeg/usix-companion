package dev.usix.companion.adapters.transport

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.protocol.*
import dev.usix.companion.testing.FakeDevice
import dev.usix.companion.testing.MemoryExecutionRepository
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class OutboundDeviceConnectionTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun authenticatedTlsExecutesHealthAndRejectsSequenceReplay() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false)
        val messages = LinkedBlockingQueue<String>(); val socket = AtomicReference<WebSocket>(); val closed = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.put(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); if (code == 1008) closed.countDown() }
        })); server.start()
        val device = FakeDevice(); val store = MemoryExecutionRepository(); val now = System.currentTimeMillis()
        val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, ExecutionCrypto(), ExecutionReadiness { _, _ -> null }, device.application())
        val router = DeviceV2Router(execution, device.application(), CredentialVerifier { false }, ExecutionReadiness { _, _ -> null }) { now }
        val challenge = execution.challenge()
        val paired = (execution.pair(challenge.challengeId, challenge.nonce, ExecutionContext(store.deviceId(), id(), id(), id(), 1, id()), null) as ExecutionResult.Success).value
        val settings = object : RemoteEndpointStore { var value: RemoteEndpoint? = null; override suspend fun load() = value; override suspend fun save(endpoint: RemoteEndpoint?) { value = endpoint } }
        val connection = OutboundDeviceConnection(settings, router, { store.deviceId() })
        try {
            connection.configure(RemoteEndpoint(server.url("/v2/device").toString().replace("https:", "wss:"), "a".repeat(64), certificate.certificatePem()))
            val hello = StrictJson.objectValue(messages.poll(8, TimeUnit.SECONDS) ?: error("TLS hello missing"))
            assertEquals("hello", hello["kind"])
            val handshake = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer " + "a".repeat(64), handshake.getHeader("Authorization"))
            val request = StrictJson.encode(mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "request", "channelRequestId" to id(),
                "sequence" to 1, "path" to "/v2/health", "bearer" to paired.bearer, "body" to mapOf("contractVersion" to DEVICE_CONTRACT, "requestId" to id())))
            socket.get().send(request)
            val response = StrictJson.objectValue(messages.poll(8, TimeUnit.SECONDS) ?: error("TLS response missing"))
            assertEquals("observation", DeviceV2Codec.obj(response["body"])["kind"])
            socket.get().send(request)
            assertTrue("Replay must close the channel", closed.await(5, TimeUnit.SECONDS))
            assertTrue(store.actionRecords.isEmpty())
        } finally { connection.close(); server.shutdown() }
    }
    @Test fun plaintextUserinfoAndRedirectDestinationsCannotBecomeDeviceEndpoints() {
        listOf("ws://localhost/v2/device", "wss://user:password@localhost/v2/device", "wss://localhost/wrong", "wss://localhost/v2/device?token=x").forEach {
            try { OutboundDeviceConnection.validatedClient(RemoteEndpoint(it, "a".repeat(64), null)); fail("Unsafe endpoint accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun explicitTrustStillEnforcesTheCertificateHostname() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("wrong.example").addSubjectAlternativeName("wrong.example").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false); server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})); server.start()
        val store = MemoryExecutionRepository(); val device = FakeDevice(); val now = System.currentTimeMillis()
        val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, ExecutionCrypto(), ExecutionReadiness { _, _ -> null }, device.application())
        val router = DeviceV2Router(execution, device.application(), CredentialVerifier { false }, ExecutionReadiness { _, _ -> null }) { now }
        val settings = object : RemoteEndpointStore { override suspend fun load(): RemoteEndpoint? = null; override suspend fun save(endpoint: RemoteEndpoint?) {} }
        val connection = OutboundDeviceConnection(settings, router, { store.deviceId() })
        try {
            connection.configure(RemoteEndpoint(server.url("/v2/device").toString().replace("https:", "wss:"), "a".repeat(64), certificate.certificatePem()))
            repeat(200) { if (connection.status() == "offline") return@repeat; Thread.sleep(10) }
            assertEquals("offline", connection.status()); assertTrue(device.calls.isEmpty())
        } finally { connection.close(); server.shutdown() }
    }
}
