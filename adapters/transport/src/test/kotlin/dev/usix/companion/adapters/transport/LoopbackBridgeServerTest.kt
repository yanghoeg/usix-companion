package dev.usix.companion.adapters.transport

import dev.usix.companion.application.CredentialVerifier
import dev.usix.companion.testing.FakeDevice
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackBridgeServerTest {
    private lateinit var fake: FakeDevice
    private lateinit var server: LoopbackBridgeServer
    private val authorization = "Authorization: Bearer synthetic-test-token\r\n"

    @Before fun start() {
        fake = FakeDevice()
        server = newServer(CredentialVerifier { it == "synthetic-test-token" })
        assertTrue(server.start())
    }
    @After fun stop() { server.close() }

    private fun newServer(verifier: CredentialVerifier, limits: HttpLimits = HttpLimits(idleTimeMs = 300, requestTimeMs = 300, headerBytes = 512, bodyBytes = 64)): LoopbackBridgeServer {
        val application = fake.application()
        return LoopbackBridgeServer(LegacyDeviceRouter(application, application), verifier, port = 0, limits = limits)
    }

    @Test fun unauthenticatedRequestsRejectBeforeReadingBodiesOrDispatching() {
        val response = exchange("POST /type HTTP/1.1\r\nContent-Length: 999999\r\n\r\n", shutdown = false)
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"))
        assertTrue(fake.calls.isEmpty())
        assertTrue(exchange("GET /private HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 401 Unauthorized"))
    }

    @Test fun healthIsLivenessAndReportsAuthenticationWithoutWaitingForItsBody() {
        val response = exchange("GET /health HTTP/1.1\r\nContent-Length: 999999\r\n\r\n", shutdown = false)
        val health = JSONObject(response.substringAfter("\r\n\r\n"))
        assertTrue(health.getBoolean("ok"))
        assertFalse(health.getBoolean("paired"))
        assertTrue(JSONObject(exchange("GET /health HTTP/1.1\r\n${authorization}\r\n").substringAfter("\r\n\r\n")).getBoolean("paired"))
    }

    @Test fun authenticatedCommandsReachPortsAndResponseLengthCountsUtf8Bytes() {
        val body = "{\"text\":\"한글\",\"package\":\"mail.app\"}"
        val response = exchange("POST /type HTTP/1.1\r\n${authorization}Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
        assertTrue(JSONObject(response.substringAfter("\r\n\r\n")).getBoolean("ok"))
        assertEquals(listOf("type:한글"), fake.calls)
        assertEquals("mail.app", fake.selectedPackage?.value)
        val failure = exchange("POST /scroll HTTP/1.1\r\n${authorization}Content-Length: 20\r\n\r\n{\"direction\":\"left\"}")
        val declared = failure.substringBefore("\r\n\r\n").lineSequence().first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
        assertEquals(failure.substringAfter("\r\n\r\n").toByteArray().size, declared)
    }

    @Test fun boundedHeadersAndBodiesRejectOversizeMalformedAndTruncatedRequests() {
        assertStatus("431", "GET /health HTTP/1.1\r\nX-Fill: ${"a".repeat(513)}\r\n\r\n")
        assertStatus("413", "POST /tap HTTP/1.1\r\n${authorization}Content-Length: 65\r\n\r\n")
        for (length in listOf("-1", "no", "9999999999999999999")) {
            assertStatus("400", "POST /tap HTTP/1.1\r\n${authorization}Content-Length: $length\r\n\r\n")
        }
        assertStatus("400", "POST /tap HTTP/1.1\r\n${authorization}Content-Length: 5\r\n\r\n{")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun incompleteHeadersAndBodiesHaveBoundedDeadlines() {
        assertTrue(exchange("GET /health HTTP/1.1\r\n", shutdown = false).startsWith("HTTP/1.1 408"))
        assertTrue(exchange("POST /type HTTP/1.1\r\n${authorization}Content-Length: 5\r\n\r\n", shutdown = false).startsWith("HTTP/1.1 408"))
    }

    @Test fun workerQueueIsBoundedAndCloseReleasesActiveConnections() {
        server.close()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server = newServer(CredentialVerifier { entered.countDown(); release.await(2, TimeUnit.SECONDS); true },
            HttpLimits(workers = 1, pending = 1))
        assertTrue(server.start())
        try {
            socket().use { first ->
                first.getOutputStream().write("GET /health HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                socket().use { second ->
                    second.getOutputStream().write("GET /health HTTP/1.1\r\n\r\n".toByteArray())
                    // TCP accept order queues the second connection before rejecting the third.
                    socket().use { third ->
                        third.soTimeout = 2_000
                        assertEquals(-1, third.getInputStream().read())
                    }
                    server.close()
                    assertEquals(-1, second.getInputStream().read())
                }
            }
        } finally { release.countDown() }
    }

    @Test fun lifecycleStartIsIdempotentAndClosedServerCanRestartOnLoopback() {
        val port = server.boundPort
        assertTrue(server.start())
        assertEquals(port, server.boundPort)
        socket().use { assertTrue(it.inetAddress.isLoopbackAddress) }
        server.close()
        assertNull(server.boundPort)
        assertTrue(server.start())
        assertTrue(exchange("GET /health HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    private fun assertStatus(code: String, raw: String) { assertTrue(exchange(raw).startsWith("HTTP/1.1 $code")) }
    private fun socket() = Socket(InetAddress.getByName("127.0.0.1"), requireNotNull(server.boundPort)).apply { soTimeout = 3_000 }
    private fun exchange(raw: String, shutdown: Boolean = true): String = socket().use { socket ->
        socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        if (shutdown) socket.shutdownOutput()
        String(socket.getInputStream().readBytes(), Charsets.UTF_8)
    }
}
