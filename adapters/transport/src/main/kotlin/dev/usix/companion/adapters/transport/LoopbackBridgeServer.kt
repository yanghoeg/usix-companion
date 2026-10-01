package dev.usix.companion.adapters.transport

import dev.usix.companion.application.CredentialVerifier
import dev.usix.companion.protocol.LegacyCodec
import dev.usix.companion.protocol.LegacyResponse
import dev.usix.companion.protocol.DeviceV2Codec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicit limits allow short failure-boundary tests without changing production defaults. */
data class HttpLimits(
    val idleTimeMs: Int = 10_000,
    val requestTimeMs: Int = 10_000,
    val headerBytes: Int = 16 * 1024,
    val bodyBytes: Int = 64 * 1024,
    val workers: Int = 4,
    val pending: Int = 16,
) {
    init {
        require(idleTimeMs > 0 && requestTimeMs > 0 && headerBytes > 0 && bodyBytes > 0 && workers > 0 && pending > 0)
    }
}

/** Owns sockets and bounded workers. Android services only start/close this injected instance. */
class LoopbackBridgeServer(
    private val router: LegacyDeviceRouter,
    private val credentials: CredentialVerifier,
    private val port: Int = PORT,
    private val limits: HttpLimits = HttpLimits(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val v2: DeviceV2Router? = null,
) : AutoCloseable {
    private class Running(val socket: ServerSocket, val pool: ThreadPoolExecutor, val job: Job) {
        val clients = ConcurrentHashMap.newKeySet<Socket>()
    }
    @Volatile private var running: Running? = null
    val boundPort: Int? get() = running?.socket?.localPort

    @Synchronized
    fun start(): Boolean {
        if (running != null) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            }
        } catch (_: IOException) { return false }
        val pool = ThreadPoolExecutor(limits.workers, limits.workers, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(limits.pending),
            { r -> Thread(r, "usix-bridge-worker").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        val current = Running(socket, pool, SupervisorJob())
        running = current
        Thread({ serve(current) }, "usix-bridge").apply { isDaemon = true }.start()
        return true
    }

    private fun serve(current: Running) {
        try {
            while (!current.socket.isClosed) {
                val sock = try { current.socket.accept() } catch (_: IOException) { break }
                current.clients.add(sock)
                try {
                    current.pool.execute {
                        try { handle(sock, current.job) } catch (_: Exception) {
                            // A failed request never terminates the accept loop or exposes contents.
                        } finally {
                            current.clients.remove(sock)
                            runCatching { sock.close() }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    current.clients.remove(sock)
                    runCatching { sock.close() }
                }
            }
        } finally {
            release(current)
            synchronized(this) { if (running === current) running = null }
        }
    }

    @Synchronized
    override fun close() {
        val current = running ?: return
        running = null
        release(current)
    }

    private fun release(current: Running) {
        current.job.cancel()
        runCatching { current.socket.close() }
        current.clients.forEach { runCatching { it.close() } }
        current.pool.shutdownNow()
    }

    private class RequestHead(val method: String, val path: String, val headers: Map<String, String>)

    private class RequestError(val status: String, message: String) : Exception(message)

    /** 헤더와 바디를 같은 총 제한 시간 안에서 읽는다. 인증 전에는 바디를 읽지 않는다. */
    private class RequestReader(private val sock: Socket, private val limits: HttpLimits) {
        private val input = sock.getInputStream()
        private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.requestTimeMs.toLong())

        fun readHead(): RequestHead {
            val head = ByteArrayOutputStream()
            var matched = 0 // \r\n\r\n 상태기계
            while (matched < 4) {
                val b = readByte()
                if (b < 0) throw RequestError("400 Bad Request", "truncated request")
                head.write(b)
                if (head.size() > limits.headerBytes) {
                    throw RequestError("431 Request Header Fields Too Large", "headers exceed ${limits.headerBytes} bytes")
                }
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2 || parts[0].isEmpty() || !parts[1].startsWith("/")) {
                throw RequestError("400 Bad Request", "malformed request line")
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                val key = line.substring(0, i).trim().lowercase()
                if (key in headers || key == "transfer-encoding") throw RequestError("400 Bad Request", "ambiguous request headers")
                headers[key] = line.substring(i + 1).trim()
            }
            return RequestHead(parts[0], parts[1], headers)
        }

        fun readBody(headers: Map<String, String>): String {
            val len = headers["content-length"]?.let {
                it.toIntOrNull() ?: throw RequestError("400 Bad Request", "bad content-length")
            } ?: 0
            if (len < 0) throw RequestError("400 Bad Request", "bad content-length")
            if (len > limits.bodyBytes) throw RequestError("413 Payload Too Large", "body exceeds ${limits.bodyBytes} bytes")
            val body = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = readBytes(body, read, len - read)
                if (r < 0) throw RequestError("400 Bad Request", "truncated body")
                read += r
            }
            return try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw RequestError("400 Bad Request", "invalid UTF-8") }
        }

        private fun readByte(): Int {
            sock.soTimeout = readTimeoutMillis()
            return input.read()
        }

        private fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int {
            sock.soTimeout = readTimeoutMillis()
            return input.read(buffer, offset, length)
        }

        private fun readTimeoutMillis(): Int {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMillis <= 0) throw SocketTimeoutException("request deadline exceeded")
            return minOf(limits.idleTimeMs.toLong(), remainingMillis).coerceAtLeast(1L).toInt()
        }
    }

    private fun handle(sock: Socket, job: Job) {
        sock.use {
            val out = sock.getOutputStream()
            val reader = RequestReader(sock, limits)
            val head = try { reader.readHead() } catch (error: RequestError) {
                writeResponse(out, LegacyCodec.error(error.status, error.message ?: "bad request")); return
            } catch (_: SocketTimeoutException) {
                writeResponse(out, LegacyCodec.error("408 Request Timeout", "request timed out")); return
            }
            val paired = credentials.verify(bearer(head.headers["authorization"]))
            val v2Call = head.path.startsWith("/v2/")
            val authorization = if (v2Call) v2?.authorize(head.path, bearer(head.headers["authorization"])) else null
            if ((v2Call && authorization == null) || (!v2Call && head.path != "/health" && !paired)) {
                writeResponse(out, if (v2Call) DeviceV2Codec.failure("IdentityMismatch", "Valid paired session or local setup owner required", "401 Unauthorized")
                    else LegacyCodec.error("401 Unauthorized", "missing or bad bearer token — pair with the companion app"))
                return
            }
            // Authenticate before reading a potentially slow or oversized body.
            val body = try {
                if (head.method == "GET" && head.path == "/health") "" else reader.readBody(head.headers)
            } catch (error: RequestError) {
                writeResponse(out, if (v2Call) DeviceV2Codec.failure("InvalidRequest", error.message ?: "bad request", error.status)
                    else LegacyCodec.error(error.status, error.message ?: "bad request")); return
            } catch (_: SocketTimeoutException) {
                writeResponse(out, if (v2Call) DeviceV2Codec.failure("DeadlineExceeded", "request timed out before dispatch", "408 Request Timeout")
                    else LegacyCodec.error("408 Request Timeout", "request timed out")); return
            }
            val response = runBlocking(job + dispatcher) {
                if (v2Call) v2!!.route(head.method, head.path, body, authorization!!)
                else router.route(head.method, head.path, body, paired)
            }
            writeResponse(out, response)
        }
    }

    private fun bearer(header: String?): String? = header?.trim()?.let {
        if (it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) it.substring(7).trim() else null
    }

    private fun writeResponse(out: OutputStream, response: LegacyResponse) {
        val bytes = response.json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 ${response.status}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object { const val PORT = 8760 }
}
