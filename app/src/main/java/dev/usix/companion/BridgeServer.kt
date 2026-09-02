package dev.usix.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 127.0.0.1:8760 에 뜨는 아주 작은 HTTP 브리지. Termux 의 Rust 에이전트가 여기에 붙어
 * 알림을 읽고(GET /notifications) 인라인 답장을 쏜다(POST /reply). 루프백에만 바인딩한다.
 * /health 를 제외한 모든 경로는 `Authorization: Bearer <token>` 이 맞아야 한다(BridgeAuth).
 */
object BridgeServer {
    const val PORT = 8760

    /** 한 연결이 워커를 붙들 수 있는 최대 시간. 멈춘 클라이언트가 브리지를 영영 막지 못하게. */
    private const val SOCKET_TIMEOUT_MS = 10_000

    /** 헤더·바디 상한. Content-Length 를 믿고 그대로 할당하면 요청 하나로 OOM 이 난다. */
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val WORKERS = 4

    private val running = AtomicBoolean(false)

    fun start(ctx: Context) {
        BridgeAuth.init(ctx)
        // CAS 로 동시 start(리스너 연결 + 포그라운드 서비스)를 하나만 통과시킨다. 바인드 실패
        // (예: 이전 프로세스가 아직 포트를 안 놓음)면 되돌려 다음 호출에서 재시도할 수 있게.
        if (!running.compareAndSet(false, true)) return
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
            }
        } catch (e: Exception) {
            running.set(false)
            return
        }
        Thread({ serve(server) }, "usix-bridge").apply { isDaemon = true }.start()
    }

    private fun serve(server: ServerSocket) {
        // 연결당 워커 — accept 루프에서 직접 처리하면 느린 클라이언트 하나가 전체를 세운다.
        val pool = Executors.newFixedThreadPool(WORKERS) { r ->
            Thread(r, "usix-bridge-worker").apply { isDaemon = true }
        }
        server.use {
            while (running.get() && !server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (e: IOException) {
                    continue
                }
                pool.execute {
                    try {
                        handle(sock)
                    } catch (e: Exception) {
                        // 개별 요청 실패는 무시하고 계속 받는다.
                    }
                }
            }
        }
        pool.shutdownNow()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class RequestError(val status: String, message: String) : Exception(message)

    private fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val out = sock.getOutputStream()
            val req = try {
                parseRequest(sock.getInputStream())
            } catch (e: RequestError) {
                writeResponse(out, e.status, err(e.message ?: "bad request"))
                return
            } catch (e: SocketTimeoutException) {
                writeResponse(out, "408 Request Timeout", err("request timed out"))
                return
            }
            val paired = BridgeAuth.check(req.headers["authorization"])
            if (req.path != "/health" && !paired) {
                writeResponse(out, "401 Unauthorized", err("missing or bad bearer token — pair with the companion app"))
                return
            }
            val (status, json) = route(req.method, req.path, req.body, paired)
            writeResponse(out, status, json)
        }
    }

    /**
     * 요청 라인·헤더를 CRLFCRLF 까지 바이트로 읽고, 바디는 Content-Length 만큼 정확히 바이트로 읽는다.
     * Reader 로 읽으면 Content-Length(바이트)와 문자 수가 어긋나 한글 바디에서 영원히 기다린다.
     */
    private fun parseRequest(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        var matched = 0 // \r\n\r\n 상태기계
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw RequestError("400 Bad Request", "truncated request")
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) {
                throw RequestError("431 Request Header Fields Too Large", "headers exceed $MAX_HEADER_BYTES bytes")
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
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0) throw RequestError("400 Bad Request", "bad content-length")
        if (len > MAX_BODY_BYTES) throw RequestError("413 Payload Too Large", "body exceeds $MAX_BODY_BYTES bytes")
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = input.read(body, read, len - read)
            if (r < 0) throw RequestError("400 Bad Request", "truncated body")
            read += r
        }
        return Request(parts[0], parts[1], headers, String(body, Charsets.UTF_8))
    }

    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    /** paired: 이번 요청의 Bearer 토큰이 맞는지 — /health 는 무인증(liveness)이지만 doctor/pair 검증용으로 알려준다. */
    private fun route(method: String, path: String, body: String, paired: Boolean): Pair<String, String> = when {
        method == "GET" && path == "/health" ->
            "200 OK" to JSONObject()
                .put("ok", true)
                .put("auth", true)
                .put("paired", paired)
                .put("listener", NotifStore.listenerConnected)
                .put("accessibility", UiController.connected())
                .toString()

        method == "GET" && path.startsWith("/screen") -> screen(path)

        method == "POST" && path == "/tap" -> tap(body)

        method == "POST" && path == "/type" -> type(body)

        method == "POST" && path == "/back" -> action(UiController.back())

        method == "POST" && path == "/open" -> open(body)

        method == "GET" && path.startsWith("/notifications") -> {
            val arr = JSONArray()
            for (n in NotifStore.snapshot()) {
                arr.put(
                    JSONObject()
                        .put("key", n.key)
                        .put("pkg", n.pkg)
                        .put("title", n.title)
                        .put("text", n.text)
                        .put("postTime", n.postTime)
                        .put("canReply", n.canReply),
                )
            }
            "200 OK" to arr.toString()
        }

        method == "POST" && path == "/reply" -> reply(body)

        else -> "404 Not Found" to err("not found")
    }

    private fun reply(body: String): Pair<String, String> = try {
        val obj = JSONObject(body)
        val key = obj.optString("key")
        val text = obj.optString("text")
        if (key.isEmpty() || text.isEmpty()) {
            "400 Bad Request" to err("key/text required")
        } else {
            val ok = NotifStore.reply(key, text)
            val resp = JSONObject().put("ok", ok)
            if (!ok) resp.put("error", "no reply action for key (expired or not repliable)")
            "200 OK" to resp.toString()
        }
    } catch (e: Exception) {
        "400 Bad Request" to err("bad json")
    }

    private fun accGuard(): Pair<String, String>? =
        if (UiController.connected()) null else "503 Service Unavailable" to err("accessibility service not enabled")

    private fun screen(path: String): Pair<String, String> {
        accGuard()?.let { return it }
        // /screen?package=com.kakao.talk — 특정 앱 창만 읽을 때. 없으면 최상위 앱 창.
        val pkg = path.substringAfter("package=", "").substringBefore('&').ifEmpty { null }
        return "200 OK" to UiController.screen(pkg).toString()
    }

    private fun action(ok: Boolean): Pair<String, String> =
        accGuard() ?: ("200 OK" to JSONObject().put("ok", ok).toString())

    private fun tap(body: String): Pair<String, String> = try {
        val obj = JSONObject(body)
        if (!obj.has("x") || !obj.has("y")) {
            "400 Bad Request" to err("x/y required")
        } else {
            accGuard() ?: ("200 OK" to JSONObject().put("ok", UiController.tap(obj.getInt("x"), obj.getInt("y"))).toString())
        }
    } catch (e: Exception) {
        "400 Bad Request" to err("bad json")
    }

    private fun type(body: String): Pair<String, String> = try {
        val text = JSONObject(body).optString("text")
        if (text.isEmpty()) {
            "400 Bad Request" to err("text required")
        } else {
            accGuard() ?: ("200 OK" to JSONObject().put("ok", UiController.type(text)).toString())
        }
    } catch (e: Exception) {
        "400 Bad Request" to err("bad json")
    }

    private fun open(body: String): Pair<String, String> = try {
        val pkg = JSONObject(body).optString("package")
        if (pkg.isEmpty()) {
            "400 Bad Request" to err("package required")
        } else {
            val ok = UiController.openApp(pkg)
            val resp = JSONObject().put("ok", ok)
            if (!ok) resp.put("error", "no launch intent for package")
            "200 OK" to resp.toString()
        }
    } catch (e: Exception) {
        "400 Bad Request" to err("bad json")
    }

    private fun writeResponse(out: OutputStream, status: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
