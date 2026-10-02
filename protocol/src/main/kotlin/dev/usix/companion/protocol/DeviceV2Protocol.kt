package dev.usix.companion.protocol

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

const val DEVICE_CONTRACT = "usix-companion.device/v2"
class ProtocolFailure(val code: String = "InvalidRequest", message: String) : IllegalArgumentException(message)
data class WireContext(val deviceId: String, val runtimeId: String, val sessionId: String, val taskId: String, val taskRevision: Long, val workspaceId: String)
data class WireScope(val packageId: String?, val accountRef: String?, val resourceRefs: List<String>, val snapshotRef: String?)
data class WireLease(val leaseId: String, val revision: Long)
data class WireAuthority(val kind: String, val ref: String?)
data class WireDeviceCommand(
    val requestId: String, val context: WireContext, val actionId: String,
    val operation: String, val scope: WireScope, val payload: Map<String, Any?>,
    val payloadHash: String, val lease: WireLease?, val deadlineMillis: Long,
    val cancellationId: String?, val authority: WireAuthority,
)

/** A strict integer-only JSON parser: duplicate keys, surrogates and coercions fail closed. */
object StrictJson {
    private const val MAX_INTEGER = 9007199254740991L
    fun objectValue(raw: String, maxBytes: Int = 65536): Map<String, Any?> {
        if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) throw ProtocolFailure(message = "Body exceeds the negotiated limit")
        val parser = Parser(raw)
        val value = parser.value(0)
        parser.space()
        if (parser.position != raw.length) throw ProtocolFailure(message = "Trailing JSON input")
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw ProtocolFailure(message = "Object required")
    }
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> { require(value in -MAX_INTEGER..MAX_INTEGER); value.toString() }
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\b' -> append("\\b"); '\t' -> append("\\t"); '\n' -> append("\\n")
                '\u000c' -> append("\\f"); '\r' -> append("\\r")
                else -> if (c.code < 32) append("\\u%04x".format(Locale.US, c.code)) else append(c)
            } }
            append('"')
        }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Map<*, *> -> value.keys.map { it as String }.sorted().joinToString(",", "{", "}") { encode(it) + ":" + encode(value[it]) }
        else -> throw ProtocolFailure(message = "Unsupported JSON value")
    }
    private class Parser(private val input: String) {
        var position = 0
        fun space() { while (position < input.length && input[position] in " \t\r\n") position++ }
        private fun fail(): Nothing = throw ProtocolFailure(message = "Malformed or noncanonical JSON value")
        private fun take(c: Char): Boolean { space(); return if (position < input.length && input[position] == c) { position++; true } else false }
        fun value(depth: Int): Any? {
            if (depth > 32) fail()
            space()
            if (position == input.length) fail()
            return when (input[position]) {
                '{' -> {
                    position++; val result = linkedMapOf<String, Any?>()
                    if (!take('}')) do {
                        space(); if (position >= input.length || input[position] != '"') fail()
                        val key = string()
                        if (key in result || !key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) || !take(':')) fail()
                        result[key] = value(depth + 1)
                        if (result.size > 128) fail()
                        if (take('}')) break
                        if (!take(',')) fail()
                    } while (true)
                    result
                }
                '[' -> {
                    position++; val result = mutableListOf<Any?>()
                    if (!take(']')) do {
                        result.add(value(depth + 1)); if (result.size > 1024) fail()
                        if (take(']')) break
                        if (!take(',')) fail()
                    } while (true)
                    result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = position
                    if (input[position] == '-') position++
                    if (position >= input.length || !input[position].isDigit()) fail()
                    if (input[position] == '0') position++ else while (position < input.length && input[position] in '0'..'9') position++
                    val token = input.substring(start, position)
                    val number = token.toLongOrNull() ?: fail()
                    if (number !in -MAX_INTEGER..MAX_INTEGER) fail()
                    number
                }
            }
        }
        private fun literal(token: String, result: Any?): Any? {
            if (!input.startsWith(token, position)) fail()
            position += token.length; return result
        }
        private fun string(): String {
            position++
            val result = StringBuilder()
            while (position < input.length) {
                val c = input[position++]
                if (c == '"') {
                    val text = result.toString()
                    if (text.codePointCount(0, text.length) > 8192) fail()
                    var i = 0
                    while (i < text.length) {
                        if (text[i].isHighSurrogate()) { if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) fail(); i++ }
                        else if (text[i].isLowSurrogate()) fail()
                        i++
                    }
                    return text
                }
                if (c.code < 32) fail()
                if (c == '\\') {
                    if (position >= input.length) fail()
                    result.append(when (input[position++]) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 't' -> '\t'; 'n' -> '\n'; 'f' -> '\u000c'; 'r' -> '\r'
                        'u' -> { if (position + 4 > input.length) fail(); val hex = input.substring(position, position + 4)
                            if (!hex.matches(Regex("[0-9a-fA-F]{4}"))) fail(); position += 4; hex.toInt(16).toChar() }
                        else -> fail()
                    })
                } else result.append(c)
            }
            fail()
        }
    }
}

object DeviceV2Codec {
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun fields(value: Map<String, Any?>, vararg fields: String) {
        if (value.keys != fields.toSet()) throw ProtocolFailure(message = "Missing or unknown fields")
    }
    fun id(value: Any?): String = (value as? String)?.takeIf { UUID.matches(it) } ?: throw ProtocolFailure(message = "Canonical UUID required")
    fun nullableId(value: Any?): String? = value?.let(::id)
    fun packageId(value: Any?): String? = value?.let { (it as? String)?.takeIf { name -> name.length <= 255 && PACKAGE.matches(name) }
        ?: throw ProtocolFailure(message = "Package name required") }
    fun integer(value: Any?, min: Long = 1, max: Long = 9007199254740991): Long = (value as? Long)?.takeIf { it in min..max }
        ?: throw ProtocolFailure(message = "Bounded integer required")
    fun text(value: Any?, max: Int = 8192): String = (value as? String)?.takeIf { it.isNotEmpty() && it.length <= max }
        ?: throw ProtocolFailure(message = "Bounded text required")
    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: throw ProtocolFailure(message = "Object required")
    fun context(value: Any?): WireContext = obj(value).let {
        fields(it, "deviceId", "runtimeId", "sessionId", "taskId", "taskRevision", "workspaceId")
        WireContext(id(it["deviceId"]), id(it["runtimeId"]), id(it["sessionId"]), id(it["taskId"]), integer(it["taskRevision"]), id(it["workspaceId"]))
    }
    fun scope(value: Any?): WireScope = obj(value).let {
        fields(it, "packageId", "accountRef", "resourceRefs", "snapshotRef")
        val refs = (it["resourceRefs"] as? List<*>)?.map(::id) ?: throw ProtocolFailure(message = "Resource list required")
        if (refs.size > 64 || refs.toSet().size != refs.size) throw ProtocolFailure(message = "Invalid resource list")
        WireScope(packageId(it["packageId"]), nullableId(it["accountRef"]), refs, nullableId(it["snapshotRef"]))
    }
    fun lease(value: Any?): WireLease? = value?.let { obj(it).let { data ->
        fields(data, "leaseId", "revision"); WireLease(id(data["leaseId"]), integer(data["revision"]))
    } }
    fun command(raw: String): WireDeviceCommand = StrictJson.objectValue(raw).let {
        if (it["contractVersion"] != DEVICE_CONTRACT) throw ProtocolFailure("UnsupportedVersion", "Contract version not supported")
        fields(it, "contractVersion", "kind", "requestId", "context", "actionId", "operation", "scope", "payload", "payloadHash", "controllerLease", "deadline", "cancellationId", "authority")
        if (it["kind"] != "command") throw ProtocolFailure(message = "Command kind required")
        val operation = text(it["operation"], 96)
        if (!operation.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*){1,4}"))) throw ProtocolFailure(message = "Invalid operation")
        val hash = text(it["payloadHash"], 71)
        if (!hash.matches(Regex("sha256:[0-9a-f]{64}")) || hash != payloadHash(it)) throw ProtocolFailure(message = "Payload digest mismatch")
        val authority = obj(it["authority"])
        fields(authority, "kind", "ref")
        val kind = authority["kind"]
        if (kind !in setOf("none", "grant", "approval") || (kind == "none" && authority["ref"] != null)) throw ProtocolFailure(message = "Invalid authority reference")
        WireDeviceCommand(id(it["requestId"]), context(it["context"]), id(it["actionId"]), operation, scope(it["scope"]), obj(it["payload"]), hash,
            lease(it["controllerLease"]), parseTime(it["deadline"]), nullableId(it["cancellationId"]), WireAuthority(kind as String, if (kind == "none") null else id(authority["ref"])))
    }
    fun payloadHash(command: Map<String, Any?>): String {
        val raw = StrictJson.encode(mapOf("operation" to command["operation"], "payload" to command["payload"], "scope" to command["scope"])).toByteArray(Charsets.UTF_8)
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(Locale.US, it) }
    }
    fun parseTime(value: Any?): Long {
        val text = text(value, 32)
        if (!text.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z"))) throw ProtocolFailure(message = "UTC timestamp required")
        val normalized = text.substringBefore('.') .removeSuffix("Z") + "." + text.substringAfter('.', "").removeSuffix("Z").padEnd(3, '0') + "Z"
        return try { formatter().parse(normalized)!!.time } catch (_: Exception) { throw ProtocolFailure(message = "Invalid calendar timestamp") }
    }
    fun time(millis: Long): String = formatter().format(java.util.Date(millis))
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        calendar = java.util.GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply { gregorianChange = java.util.Date(Long.MIN_VALUE) }
        isLenient = false
    }
    fun response(value: Map<String, Any?>, status: String = "200 OK"): LegacyResponse {
        val json = StrictJson.encode(value)
        // Leave room for WSS correlation framing inside its 1 MiB bound.
        if (json.toByteArray(Charsets.UTF_8).size > 1047552) throw ProtocolFailure(message = "Observation exceeds the response limit; narrow the query/page or capture")
        return LegacyResponse(status, json)
    }
    fun failure(code: String, message: String, status: String = "400 Bad Request") = response(mapOf(
        "contractVersion" to DEVICE_CONTRACT, "kind" to "error", "error" to mapOf("code" to code, "message" to message),
        "effect" to if (code == "UnknownEffect") "possible" else "none",
        "retry" to mapOf("decision" to if (code == "UnknownEffect") "reconcile" else "never",
            "reason" to if (code == "UnknownEffect") "Read the action receipt and refresh observation; do not replay"
                else "Correct the request or refresh trusted setup; do not replay effects")), status)
}
