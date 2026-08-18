package dev.usix.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 접근성 서비스 — 루트·adb 없이 화면을 읽고(노드 트리) 제스처로 탭하며, 포커스된 입력창에 텍스트를
 * 넣는다(한글 포함). 사용자가 '설정 > 접근성'에서 켜면 시스템이 바인딩한다.
 */
class UsixAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        UiController.service = this
    }

    override fun onDestroy() {
        if (UiController.service === this) UiController.service = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 현재 활성 창의 텍스트·클릭가능 노드를 (라벨, 중심x, 중심y, clickable, editable) 로 수집. */
    fun dumpScreen(): JSONArray {
        val arr = JSONArray()
        val root = rootInActiveWindow ?: return arr
        val rect = Rect()
        walk(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text else desc
            if (label.isEmpty() && !node.isClickable) return@walk
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@walk
            arr.put(
                JSONObject()
                    .put("text", label.ifEmpty { "(빈 버튼)" })
                    .put("x", rect.centerX())
                    .put("y", rect.centerY())
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable),
            )
        }
        return arr
    }

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), visit)
        }
    }

    /** (x,y) 를 짧게 탭. 제스처 콜백을 래치로 기다려 성공 여부를 동기 반환. */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(d: GestureDescription?) {
                    ok = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    /** 포커스된(없으면 첫 번째 편집 가능) 입력창에 텍스트를 세팅. adb input 과 달리 한글이 정상 입력된다. */
    fun setFocusedText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
