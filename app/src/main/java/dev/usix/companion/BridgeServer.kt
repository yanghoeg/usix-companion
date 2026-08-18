package dev.usix.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 127.0.0.1:8760 에 뜨는 아주 작은 HTTP 브리지. Termux 의 Rust 에이전트가 여기에 붙어
 * 알림을 읽고(GET /notifications) 인라인 답장을 쏜다(POST /reply). 루프백에만 바인딩한다.
 */
object BridgeServer {
    const val PORT = 8760

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        // 바인드가 성공한 뒤에만 running 을 세운다. 실패(예: 이전 프로세스가 아직 포트를 안 놓음)하면
        // running 이 false 로 남아 다음 재바인드에서 다시 시도할 수 있다. reuseAddress 로 TIME_WAIT 회피.
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
            }
        } catch (e: Exception) {
            return
        }
        running = true
        Thread({ serve(server) }, "usix-bridge").apply { isDaemon = true }.start()
    }

    private fun serve(server: ServerSocket) {
        server.use {
            while (running) {
                try {
                    handle(server.accept())
                } catch (e: Exception) {
                    // 개별 요청 실패는 무시하고 계속 받는다.
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) readBody(reader, contentLength) else ""

            val (status, json) = route(method, path, body)
            writeResponse(sock.getOutputStream(), status, json)
        }
    }

    private fun readBody(reader: BufferedReader, len: Int): String {
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val r = reader.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read)
    }

    private fun route(method: String, path: String, body: String): Pair<String, String> = when {
        method == "GET" && path == "/health" ->
            "200 OK" to JSONObject()
                .put("ok", true)
                .put("listener", NotifStore.listenerConnected)
                .put("accessibility", UiController.connected())
                .toString()

        method == "GET" && path == "/screen" -> screen()

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

        else -> "404 Not Found" to JSONObject().put("error", "not found").toString()
    }

    private fun reply(body: String): Pair<String, String> = try {
        val obj = JSONObject(body)
        val key = obj.optString("key")
        val text = obj.optString("text")
        if (key.isEmpty() || text.isEmpty()) {
            "400 Bad Request" to JSONObject().put("ok", false).put("error", "key/text required").toString()
        } else {
            val ok = NotifStore.reply(key, text)
            val resp = JSONObject().put("ok", ok)
            if (!ok) resp.put("error", "no reply action for key (expired or not repliable)")
            "200 OK" to resp.toString()
        }
    } catch (e: Exception) {
        "400 Bad Request" to JSONObject().put("ok", false).put("error", "bad json").toString()
    }

    private fun accGuard(): Pair<String, String>? =
        if (UiController.connected()) {
            null
        } else {
            "503 Service Unavailable" to
                JSONObject().put("ok", false)
                    .put("error", "accessibility service not enabled").toString()
        }

    private fun screen(): Pair<String, String> =
        accGuard() ?: ("200 OK" to UiController.screen().toString())

    private fun action(ok: Boolean): Pair<String, String> =
        accGuard() ?: ("200 OK" to JSONObject().put("ok", ok).toString())

    private fun tap(body: String): Pair<String, String> = try {
        val obj = JSONObject(body)
        if (!obj.has("x") || !obj.has("y")) {
            "400 Bad Request" to JSONObject().put("ok", false).put("error", "x/y required").toString()
        } else {
            accGuard() ?: ("200 OK" to JSONObject().put("ok", UiController.tap(obj.getInt("x"), obj.getInt("y"))).toString())
        }
    } catch (e: Exception) {
        "400 Bad Request" to JSONObject().put("ok", false).put("error", "bad json").toString()
    }

    private fun type(body: String): Pair<String, String> = try {
        val text = JSONObject(body).optString("text")
        if (text.isEmpty()) {
            "400 Bad Request" to JSONObject().put("ok", false).put("error", "text required").toString()
        } else {
            accGuard() ?: ("200 OK" to JSONObject().put("ok", UiController.type(text)).toString())
        }
    } catch (e: Exception) {
        "400 Bad Request" to JSONObject().put("ok", false).put("error", "bad json").toString()
    }

    private fun open(body: String): Pair<String, String> = try {
        val pkg = JSONObject(body).optString("package")
        if (pkg.isEmpty()) {
            "400 Bad Request" to JSONObject().put("ok", false).put("error", "package required").toString()
        } else {
            val ok = UiController.openApp(pkg)
            val resp = JSONObject().put("ok", ok)
            if (!ok) resp.put("error", "no launch intent for package")
            "200 OK" to resp.toString()
        }
    } catch (e: Exception) {
        "400 Bad Request" to JSONObject().put("ok", false).put("error", "bad json").toString()
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
