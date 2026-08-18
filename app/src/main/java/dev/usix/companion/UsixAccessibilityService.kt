package dev.usix.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    /**
     * 대상 창의 텍스트·클릭가능 노드를 (라벨, 중심x, 중심y, clickable, editable) 로 수집.
     * pkg 가 주어지면 그 패키지의 창을 콕 집어 읽는다(멀티윈도에서 rootInActiveWindow 가 엉뚱한
     * 창을 주는 문제 회피). 없으면 최상위 앱 창 → 그것도 없으면 활성 창으로 폴백.
     */
    fun dumpScreen(pkg: String?): JSONArray {
        val arr = JSONArray()
        val rect = Rect()
        for (root in targetRoots(pkg)) {
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
        }
        return arr
    }

    /** 읽을 창(들)의 루트 노드. pkg 매칭 우선, 없으면 최상위 앱 창, 최후엔 rootInActiveWindow. */
    private fun targetRoots(pkg: String?): List<AccessibilityNodeInfo> {
        val ws = windows ?: emptyList()
        if (!pkg.isNullOrEmpty()) {
            val matched = ws.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
            if (matched.isNotEmpty()) return matched
        }
        val top = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
            ?.root
        return listOfNotNull(top ?: rootInActiveWindow)
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

    /**
     * 포커스된 입력창에 텍스트를 세팅. adb input 과 달리 한글이 정상 입력된다.
     * 멀티윈도 대비: 활성 창 → 전체 창 순으로 포커스된 입력을, 없으면 편집 가능한 노드를 찾는다.
     */
    fun setFocusedText(text: String): Boolean {
        val target = findFocusedInput() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        val roots = (windows ?: emptyList()).mapNotNull { it.root }
        for (r in roots) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        for (r in roots) {
            findEditable(r)?.let { return it }
        }
        return rootInActiveWindow?.let { findEditable(it) }
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
