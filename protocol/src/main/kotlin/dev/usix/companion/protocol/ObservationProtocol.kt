package dev.usix.companion.protocol

import java.security.MessageDigest
import java.util.Locale

data class WireSelector(val fields: Map<String, Any?>)
data class WireUiGoal(val goalId: String, val kind: String, val selector: WireSelector?, val expectedText: String?, val accountSelector: WireSelector?)
data class WireUiPayload(val target: WireSelector?, val text: String?, val x: Int?, val y: Int?, val endX: Int?, val endY: Int?,
    val durationMillis: Long, val forward: Boolean, val goal: WireUiGoal?, val goalHash: String?)

/** Per-operation schemas fail closed before the action journal or Android dispatch. */
object ObservationCodec {
    private val selectorFields = setOf("nodeRef", "resourceId", "text", "description", "className", "role", "windowId", "editable", "scrollable")
    fun selector(value: Any?, durable: Boolean = false): WireSelector? = value?.let {
        val data = DeviceV2Codec.obj(it)
        if (data.isEmpty() || data.keys.any { key -> key !in selectorFields } || data.keys == setOf("windowId")) throw ProtocolFailure(message = "Semantic selector required")
        data.forEach { (key, item) -> when (key) {
            "windowId" -> DeviceV2Codec.integer(item, 0, 2147483647)
            "editable", "scrollable" -> boolean(item)
            "nodeRef" -> {
                if (durable || !DeviceV2Codec.text(item, 256).matches(Regex("n(?:/[0-9]+){0,64}"))) throw ProtocolFailure(message = "Snapshot-scoped node reference required; goals need semantic selectors")
            }
            else -> if (string(item).isEmpty()) throw ProtocolFailure(message = "Nonempty selector attribute required")
        } }
        WireSelector(data)
    }
    fun boolean(value: Any?): Boolean = value as? Boolean ?: throw ProtocolFailure(message = "Boolean required")
    fun string(value: Any?, max: Int = 2048): String = (value as? String)?.takeIf { it.codePointCount(0, it.length) <= max }
        ?: throw ProtocolFailure(message = "Bounded string required")
    fun goal(value: Any?): WireUiGoal = DeviceV2Codec.obj(value).let {
        DeviceV2Codec.fields(it, "goalId", "kind", "selector", "expectedText", "accountSelector")
        val kind = DeviceV2Codec.text(it["kind"], 32)
        if (kind !in setOf("package_visible", "node_present", "node_text")) throw ProtocolFailure(message = "Only UI-state verification criteria are supported")
        val targetSelector = selector(it["selector"], durable = true)
        val expected = it["expectedText"]?.let(::string)
        if ((kind == "package_visible" && (targetSelector != null || expected != null)) || (kind != "package_visible" && targetSelector == null) ||
            (kind == "node_text" && expected == null) || (kind == "node_present" && expected != null)) throw ProtocolFailure(message = "Criterion does not match its verification kind")
        WireUiGoal(DeviceV2Codec.id(it["goalId"]), kind, targetSelector, expected, selector(it["accountSelector"], durable = true))
    }
    fun goalHash(value: Any?): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(StrictJson.encode(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.US, it) }
    fun action(command: WireDeviceCommand): WireUiPayload? {
        val p = command.payload
        val required = when (command.operation) {
            "ui.click", "ui.select", "ui.long_press" -> setOf("target")
            "ui.set_text" -> setOf("target", "text")
            "ui.scroll" -> setOf("target", "forward")
            "ui.tap" -> setOf("x", "y")
            "ui.swipe" -> setOf("x", "y", "endX", "endY", "durationMillis")
            "ui.back", "ui.home" -> emptySet()
            else -> return null
        }
        if (p.keys - setOf("goal") != required) throw ProtocolFailure(message = "Missing or unknown UI action fields")
        val target = selector(p["target"])
        if ("target" in required && target == null) throw ProtocolFailure(message = "Action target required")
        fun coordinate(name: String) = if (name in required) DeviceV2Codec.integer(p[name], 0, 32768).toInt() else null
        val goal = if ("goal" in p) goal(p["goal"]) else null
        return WireUiPayload(target, if ("text" in required) string(p["text"]) else null, coordinate("x"), coordinate("y"), coordinate("endX"), coordinate("endY"),
            if ("durationMillis" in required) DeviceV2Codec.integer(p["durationMillis"], 50, 2000) else 250,
            if ("forward" in required) boolean(p["forward"]) else true, goal, if (goal == null) null else goalHash(p["goal"]))
    }
}
