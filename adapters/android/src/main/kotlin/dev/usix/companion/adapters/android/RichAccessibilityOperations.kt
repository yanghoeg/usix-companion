package dev.usix.companion.adapters.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import dev.usix.companion.domain.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** All tree/gesture/capture calls run on the injected main dispatcher. */
class RichAccessibilityOperations(private val service: AccessibilityService, private val generation: () -> Long) {
    @Suppress("DEPRECATION") private fun AccessibilityNodeInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
    @Suppress("DEPRECATION") private fun AccessibilityWindowInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
    private fun Rect.value() = ScreenBounds(left, top, right, bottom)
    private fun CharSequence?.bounded(max: Int = 2048): String {
        val value = this?.toString().orEmpty()
        return if (value.codePointCount(0, value.length) <= max) value else value.substring(0, value.offsetByCodePoints(0, max))
    }
    private fun reject(code: ExecutionErrorCode, message: String) = ExecutionResult.Rejected(ExecutionError(code, message))
    private data class Root(val node: AccessibilityNodeInfo, val id: Int, val bounds: ScreenBounds, val signature: String, val ime: Boolean)

    private fun target(packageId: String): Root? {
        val windows = service.windows.orEmpty()
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
            val foreground = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive }
            if (foreground == null && apps.isNotEmpty()) return null
            if (foreground != null) {
                val root = foreground.root ?: return null
                if (root.packageName?.toString() != packageId) { root.release(); return null }
                val rect = Rect(); foreground.getBoundsInScreen(rect)
                val signature = windows.sortedBy { it.id }.joinToString(";") {
                    val b = Rect(); it.getBoundsInScreen(b)
                    "${it.id}:${it.type}:${it.layer}:${it.isActive}:${it.isFocused}:$b"
                }
                return Root(root, foreground.id, rect.value(), signature, windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            }
            val root = service.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != packageId) { root.release(); return null }
            val bounds = Rect(); root.getBoundsInScreen(bounds)
            return Root(root, root.windowId, bounds.value(), "active:${root.windowId}", false)
        } finally { windows.forEach { it.release() } }
    }

    @Suppress("DEPRECATION")
    fun observe(packageId: String): ExecutionResult<ObservedScreen> {
        val root = target(packageId) ?: return reject(ExecutionErrorCode.ExpiredReference, "Selected package has no foreground accessible application window")
        val nodes = mutableListOf<ObservedNode>()
        var complete = true
        fun walk(node: AccessibilityNodeInfo, ref: String, parent: String?, inheritedSensitive: Boolean, depth: Int) {
            if (nodes.size >= 4096 || depth > 64) { complete = false; return }
            if (!node.refresh()) { complete = false; return }
            val sensitive = inheritedSensitive || node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val count = minOf(node.childCount, 1024)
            if (count != node.childCount) complete = false
            val children = (0 until count).map { "$ref/$it" }
            val name = node.className?.toString().orEmpty().take(256)
            val role = when {
                node.isEditable -> "textbox"; node.isScrollable -> "scroll_container"
                name.endsWith("WebView") -> "webview"; name.contains("CheckBox") -> "checkbox"
                name.contains("Button") || node.isClickable -> "button"; name.contains("Image") -> "image"; else -> "text"
            }
            nodes.add(ObservedNode(ref, parent, children, node.viewIdResourceName?.take(512), name, role, bounds.value(),
                if (sensitive) "[redacted]" else node.text.bounded(),
                if (sensitive) "[redacted]" else node.contentDescription.bounded(),
                node.isEnabled, node.isVisibleToUser, node.isEditable, node.isClickable, node.isScrollable,
                node.isFocused || node.isAccessibilityFocused, node.isSelected, sensitive,
                !sensitive && (node.text?.toString()?.let { it.codePointCount(0, it.length) > 2048 } == true ||
                    node.contentDescription?.toString()?.let { it.codePointCount(0, it.length) > 2048 } == true)))
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child == null) { complete = false; continue }
                try { walk(child, "$ref/$i", ref, sensitive, depth + 1) } finally { child.release() }
            }
        }
        try {
            walk(root.node, "n", null, false, 0)
            val manager = service.getSystemService(WindowManager::class.java)
            val display = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds.value()
                else ScreenBounds(0, 0, service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
            return ExecutionResult.Success(ObservedScreen(packageId, root.id, generation(), display, root.bounds,
                manager.defaultDisplay?.rotation ?: 0, nodes.firstOrNull { it.focused }?.ref, root.ime, root.signature, nodes, complete))
        } finally { root.node.release() }
    }

    suspend fun perform(expected: ObservedScreen, operation: String, request: UiActionRequest, guard: suspend () -> ExecutionError?): UiEffect {
        // Authority is checked off the main thread, then revalidation and dispatch
        // run consecutively on main without another suspension point.
        guard()?.let { return UiEffect(UiEffectState.None, it) }
        val live = observe(expected.packageId)
        if (live !is ExecutionResult.Success || live.value != expected)
            return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Screen changed immediately before dispatch"))
        if (operation == "ui.back" || operation == "ui.home") {
            val action = if (operation == "ui.back") AccessibilityService.GLOBAL_ACTION_BACK else AccessibilityService.GLOBAL_ACTION_HOME
            return acknowledgement(service.performGlobalAction(action))
        }
        if (operation == "ui.tap" || operation == "ui.swipe") return gesture(request)
        val root = target(expected.packageId) ?: return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Application focus changed"))
        val ref = request.selector?.nodeRef ?: return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.InvalidRequest, "Resolved target required"))
        var node = root.node
        try {
            for (part in ref.split('/').drop(1)) {
                val child = node.getChild(part.toInt()) ?: return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.ExpiredReference, "Node path no longer exists"))
                if (node !== root.node) node.release()
                node = child
            }
            val old = expected.nodes.singleOrNull { it.ref == ref }
            if (!node.refresh()) return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.ExpiredReference, "Android node is no longer live"))
            val b = Rect(); node.getBoundsInScreen(b)
            if (old == null || !node.isEnabled || !node.isVisibleToUser || node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) || b.value() != old.bounds ||
                node.className?.toString().orEmpty().take(256) != old.className || node.isEditable != old.editable ||
                node.isClickable != old.clickable || node.isScrollable != old.scrollable || node.isSelected != old.selected ||
                (node.isFocused || node.isAccessibilityFocused) != old.focused ||
                node.viewIdResourceName != old.resourceId || node.text.bounded() != old.text || node.contentDescription.bounded() != old.description)
                return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Target metadata changed before dispatch"))
            val action = when (operation) {
                "ui.click" -> AccessibilityNodeInfo.ACTION_CLICK
                "ui.select" -> AccessibilityNodeInfo.ACTION_SELECT
                "ui.long_press" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                "ui.set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
                "ui.scroll" -> if (request.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Unknown UI operation"))
            }
            val args = if (operation == "ui.set_text") Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.text) } else null
            return acknowledgement(node.performAction(action, args))
        } finally { if (node !== root.node) node.release(); root.node.release() }
    }
    private fun acknowledgement(accepted: Boolean) = if (accepted) UiEffect(UiEffectState.Dispatched) else
        UiEffect(UiEffectState.Possible, ExecutionError(ExecutionErrorCode.UnknownEffect, "Android did not acknowledge the action; observe before another attempt"))
    private suspend fun gesture(request: UiActionRequest): UiEffect = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(request.x!!.toFloat(), request.y!!.toFloat())
                request.endX?.let { lineTo(it.toFloat(), request.endY!!.toFloat()) }
            }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, request.durationMillis)).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(UiEffect(UiEffectState.Dispatched)) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(acknowledgement(false)) }
            }
            if (!service.dispatchGesture(gesture, callback, null) && continuation.isActive)
                continuation.resume(UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.PermissionRequired, "Gesture was not dispatched")))
        }
    } ?: acknowledgement(false)

    fun captureReadiness(language: String?): ExecutionError? = when {
        Build.VERSION.SDK_INT < 30 -> ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Accessibility screenshots require Android API 30; node observation remains available")
        service.serviceInfo?.capabilities?.and(AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT).let { it == null || it == 0 } -> ExecutionError(ExecutionErrorCode.PermissionRequired, "Screenshot service capability is not enabled")
        language != null && language !in setOf("latin", "korean") -> ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Only bundled Latin and Korean OCR are available")
        else -> null
    }

    suspend fun capture(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual> {
        if (Build.VERSION.SDK_INT < 30) return reject(ExecutionErrorCode.UnsupportedCapability, "Accessibility screenshots require Android API 30")
        return captureSupported(expected, language)
    }
    @androidx.annotation.RequiresApi(30)
    private suspend fun captureSupported(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual> {
        captureReadiness(language)?.let { return ExecutionResult.Rejected(it) }
        if (!expected.complete) return reject(ExecutionErrorCode.PermissionRequired, "Incomplete tree cannot establish all sensitive capture regions")
        val live = observe(expected.packageId)
        if (live !is ExecutionResult.Success || live.value != expected) return reject(ExecutionErrorCode.StaleSnapshot, "Screen changed before capture")
        // Before API 34 a display capture is cropped to the selected foreground app.
        // Refuse an IME/overlay on that profile rather than exposing unrelated content.
        if (Build.VERSION.SDK_INT < 34) {
            val windows = service.windows.orEmpty()
            val obstructed = try { expected.inputWindowVisible || windows.any {
                val bounds = Rect(); it.getBoundsInScreen(bounds)
                val selected = expected.windowBounds
                it.id != expected.windowId && it.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                    Rect.intersects(bounds, Rect(selected.left, selected.top, selected.right, selected.bottom))
            } } finally { windows.forEach { it.release() } }
            if (obstructed) return reject(ExecutionErrorCode.PermissionRequired, "Close keyboard/overlapping windows before a display-based capture")
        }
        val shot: ExecutionResult<Bitmap> = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, true)
                        wrapped?.recycle(); buffer.close()
                        if (continuation.isActive) continuation.resume(if (copy == null) reject(ExecutionErrorCode.PermissionRequired, "Screenshot buffer unavailable") else ExecutionResult.Success(copy))
                        else copy?.recycle()
                    }
                    override fun onFailure(errorCode: Int) {
                        val result = if (Build.VERSION.SDK_INT >= 34 && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                            reject(ExecutionErrorCode.PermissionRequired, "Protected window cannot be captured")
                        else if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                            reject(ExecutionErrorCode.Busy, "Android screenshot interval limit; wait before a fresh read")
                        else reject(ExecutionErrorCode.PermissionRequired, "Screenshot unavailable or protected")
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
                if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(expected.windowId, service.mainExecutor, callback)
                else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            }
        } ?: return reject(ExecutionErrorCode.DeadlineExceeded, "Screenshot callback deadline exceeded")
        if (shot !is ExecutionResult.Success) return shot as ExecutionResult.Rejected
        var bitmap = shot.value
        try {
            val originX = expected.windowBounds.left; val originY = expected.windowBounds.top
            if (Build.VERSION.SDK_INT < 34) {
                val b = expected.windowBounds
                val left = b.left.coerceIn(0, bitmap.width - 1); val top = b.top.coerceIn(0, bitmap.height - 1)
                val crop = Bitmap.createBitmap(bitmap, left, top, (b.right.coerceAtMost(bitmap.width) - left).coerceAtLeast(1), (b.bottom.coerceAtMost(bitmap.height) - top).coerceAtLeast(1))
                if (crop !== bitmap) { bitmap.recycle(); bitmap = crop }
            }
            val canvas = Canvas(bitmap); val mask = Paint().apply { color = Color.BLACK }
            expected.nodes.filter { it.sensitive }.forEach {
                canvas.drawRect((it.bounds.left - originX).toFloat(), (it.bounds.top - originY).toFloat(),
                    (it.bounds.right - originX).toFloat(), (it.bounds.bottom - originY).toFloat(), mask)
            }
            if (maxOf(bitmap.width, bitmap.height) > 1600) {
                val scale = 1600.0 / maxOf(bitmap.width, bitmap.height)
                val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            }
            val recognized = if (language == null) RecognizedText(emptyList(), true) else when (val result = recognize(bitmap, language)) {
                is ExecutionResult.Rejected -> return result
                is ExecutionResult.Success -> result.value
            }
            val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            if (bytes.size > 524288) return reject(ExecutionErrorCode.InvalidRequest, "Captured PNG exceeds the 512 KiB media limit")
            val hash = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
            return ExecutionResult.Success(CapturedVisual(bytes, bitmap.width, bitmap.height, hash, language, recognized.blocks, expected.nodes.any { it.sensitive }, recognized.complete))
        } finally { bitmap.recycle() }
    }

    private data class RecognizedText(val blocks: List<OcrBlock>, val complete: Boolean)
    private suspend fun recognize(bitmap: Bitmap, language: String): ExecutionResult<RecognizedText> {
        val recognizer = if (language == "korean") TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // The ML Kit task owns this copy until its completion, including caller cancellation.
        val input = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(ExecutionResult.Success(RecognizedText(result.textBlocks.take(128).mapNotNull {
                        val bounds = it.boundingBox ?: return@mapNotNull null
                        OcrBlock(it.text.bounded(), bounds.value())
                    }, result.textBlocks.size <= 128 && result.textBlocks.all { it.text.codePointCount(0, it.text.length) <= 2048 }))) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(reject(ExecutionErrorCode.PermissionRequired, "Bundled OCR could not process this image")) }
                    .addOnCompleteListener { input.recycle(); recognizer.close() }
            }
        } ?: reject(ExecutionErrorCode.DeadlineExceeded, "OCR processing deadline exceeded")
    }
}
