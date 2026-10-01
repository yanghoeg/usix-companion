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
    data object Notifications : LegacyCommand
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
            method == "GET" && path.substringBefore('?') == "/notifications" -> LegacyCommand.Notifications
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

    fun error(status: String, message: String) = LegacyResponse(status, JSONObject().put("ok", false).put("error", message).toString())

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
