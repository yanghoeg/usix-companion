package dev.usix.companion.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

sealed interface WireField {
    data object Missing : WireField
    data object Invalid : WireField
    data class Text(val value: String) : WireField
}

sealed interface LegacyCommand {
    data object Health : LegacyCommand
    data class Screen(val packageName: String?) : LegacyCommand
    data class ReadWindow(val packageName: String?) : LegacyCommand
    data class ReadSelect(val packageName: String) : LegacyCommand
    data class ReadScroll(val packageName: String, val direction: String, val windowRef: String, val nodeRef: String) : LegacyCommand
    data object Notifications : LegacyCommand
    data class NotificationHistory(val packageName: String, val query: String, val since: Long?, val until: Long?, val limit: Int, val before: String?) : LegacyCommand
    data class ClearNotificationHistory(val packageName: String) : LegacyCommand
    data class Tap(val x: Int?, val y: Int?) : LegacyCommand
    data class Type(val text: String?, val packageName: WireField) : LegacyCommand
    data object Back : LegacyCommand
    data class Open(val packageName: String?) : LegacyCommand
    data class Scroll(val direction: String?, val packageName: WireField) : LegacyCommand
    data class OpenEmail(val packageName: WireField) : LegacyCommand
    data class ComposeEmail(val to: String?, val subject: WireField, val body: WireField, val packageName: WireField) : LegacyCommand
    data class Reply(val key: String?, val text: String?) : LegacyCommand
    data object NotFound : LegacyCommand
}

data class LegacyScreenNode(val text: String, val x: Int, val y: Int, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean)
data class LegacyNotification(val key: String, val pkg: String, val title: String, val text: String, val postTime: Long, val canReply: Boolean)
data class LegacyResponse(val status: String, val json: String)
class DecodeFailure : Exception("bad json")

/** Only wire representation and endpoint recognition; no authority or action policy. */
object LegacyCodec {
    fun decode(method: String, path: String, body: String): LegacyCommand = try {
        when {
            method == "GET" && path == "/health" -> LegacyCommand.Health
            method == "GET" && path.substringBefore('?') == "/screen" -> LegacyCommand.Screen(queryPackage(path))
            method == "GET" && path.substringBefore('?') == "/read-window" -> LegacyCommand.ReadWindow(queryPackage(path))
            method == "POST" && path == "/read-select" -> JSONObject(body).let {
                require(it.keySet() == setOf("package"))
                require(it.opt("package") is String)
                LegacyCommand.ReadSelect(it.getString("package").also { value -> require(value.isNotBlank() && value.length <= 256) })
            }
            method == "POST" && path == "/read-scroll" -> JSONObject(body).let {
                require(it.keySet() == setOf("package", "direction", "windowRef", "nodeRef"))
                fun text(key: String): String { require(it.opt(key) is String); return it.getString(key).also { value -> require(value.isNotBlank() && value.length <= if (key == "nodeRef") 512 else 256) } }
                LegacyCommand.ReadScroll(text("package"), text("direction"), text("windowRef"), text("nodeRef"))
            }
            method == "GET" && path.substringBefore('?') == "/notifications" -> LegacyCommand.Notifications
            method == "GET" && path.substringBefore('?') == "/notifications/history" -> historyQuery(path)
            method == "POST" && path == "/notifications/history/clear" -> JSONObject(body).let {
                require(it.keySet() == setOf("package") && it.opt("package") is String)
                LegacyCommand.ClearNotificationHistory(it.getString("package").also { value -> require(value.isNotBlank() && value.length <= 256) })
            }
            method == "POST" && path == "/back" -> LegacyCommand.Back
            method == "POST" && path == "/tap" -> JSONObject(body).let {
                LegacyCommand.Tap(if (it.has("x")) it.getInt("x") else null, if (it.has("y")) it.getInt("y") else null)
            }
            method == "POST" && path == "/type" -> JSONObject(body).let { LegacyCommand.Type(it.opt("text") as? String, field(it, "package")) }
            method == "POST" && path == "/open" -> LegacyCommand.Open(JSONObject(body).optString("package"))
            method == "POST" && path == "/scroll" -> JSONObject(body).let { LegacyCommand.Scroll(it.optString("direction"), field(it, "package")) }
            method == "POST" && path == "/email/open" -> LegacyCommand.OpenEmail(field(JSONObject(body), "package"))
            method == "POST" && path == "/email/compose" -> JSONObject(body).let {
                LegacyCommand.ComposeEmail(it.opt("to") as? String, field(it, "subject"), field(it, "body"), field(it, "package"))
            }
            method == "POST" && path == "/reply" -> JSONObject(body).let { LegacyCommand.Reply(it.optString("key"), it.optString("text")) }
            else -> LegacyCommand.NotFound
        }
    } catch (_: JSONException) {
        throw DecodeFailure()
    } catch (_: IllegalArgumentException) {
        throw DecodeFailure()
    }

    private fun field(obj: JSONObject, key: String): WireField = when {
        !obj.has(key) -> WireField.Missing
        obj.opt(key) is String -> WireField.Text(obj.getString(key))
        else -> WireField.Invalid
    }

    private fun queryPackage(path: String): String? {
        val query = path.substringAfter('?', "")
        val pair = query.split('&').firstOrNull {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") == "package"
        } ?: return null
        return URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8").ifEmpty { null }
    }

    private fun historyQuery(path: String): LegacyCommand.NotificationHistory {
        require(path.length <= 8192)
        val fields = mutableMapOf<String, String>()
        path.substringAfter('?', "").split('&').forEach { pair ->
            require('=' in pair)
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            require(key in setOf("package", "query", "since", "until", "limit", "before") && key !in fields)
            fields[key] = value
        }
        val pkg = requireNotNull(fields["package"])
        require(pkg.isNotBlank() && pkg.length <= 256)
        fun number(key: String): Long? = fields[key]?.let {
            require(Regex("[0-9]{1,19}").matches(it)); requireNotNull(it.toLongOrNull())
        }
        val limit = number("limit") ?: 50L
        require(limit in 1L..100L)
        val query = fields["query"].orEmpty()
        require(query.length <= 256 && (fields["before"]?.length ?: 0) <= 64)
        return LegacyCommand.NotificationHistory(pkg, query, number("since"), number("until"), limit.toInt(), fields["before"])
    }

    fun error(status: String, message: String) = LegacyResponse(status, JSONObject().put("ok", false).put("error", message).toString())

    /**
     * Unauthenticated identity proof. Loopback is shared by every app, so a client checks
     * `HMAC(token, PROOF_PREFIX + challenge)` before it sends its bearer to port 8760.
     */
    const val PROOF_PREFIX = "usix-companion-proof-v1:"

    fun isProof(method: String, path: String) = method == "GET" && path.substringBefore('?') == "/proof"

    /** The challenge is exactly 64 lowercase hex characters (32 random bytes), else null. */
    fun proofChallenge(path: String): String? = path.substringAfter('?', "").split('&')
        .firstOrNull { it.substringBefore('=') == "challenge" }?.substringAfter('=', "")
        ?.takeIf { c -> c.length == 64 && c.all { it in '0'..'9' || it in 'a'..'f' } }

    fun proofMessage(challenge: String) = (PROOF_PREFIX + challenge).toByteArray(Charsets.UTF_8)

    fun proof(mac: ByteArray) = LegacyResponse("200 OK", JSONObject().put("ok", true)
        .put("proof", mac.joinToString("") { "%02x".format(it) }).toString())

    fun health(paired: Boolean, listener: Boolean, accessibility: Boolean) = LegacyResponse("200 OK", JSONObject()
        .put("ok", true).put("auth", true).put("paired", paired).put("listener", listener).put("accessibility", accessibility).toString())

    fun action(ok: Boolean, draft: Boolean = false, detail: String? = null): LegacyResponse {
        val obj = JSONObject().put("ok", ok)
        if (draft) obj.put("sent", false)
        if (detail != null) obj.put("error", detail)
        return LegacyResponse("200 OK", obj.toString())
    }

    fun screen(nodes: List<LegacyScreenNode>) = LegacyResponse("200 OK", JSONArray().apply {
        nodes.forEach { put(JSONObject().put("text", it.text).put("x", it.x).put("y", it.y)
            .put("clickable", it.clickable).put("editable", it.editable).put("scrollable", it.scrollable)) }
    }.toString())

    fun notifications(items: List<LegacyNotification>) = LegacyResponse("200 OK", JSONArray().apply {
        items.forEach { put(JSONObject().put("key", it.key).put("pkg", it.pkg).put("title", it.title)
            .put("text", it.text).put("postTime", it.postTime).put("canReply", it.canReply)) }
    }.toString())
}
