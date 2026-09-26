package dev.usix.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
 *
 * 노드 회수: API 33 미만은 AccessibilityNodeInfo/WindowInfo 풀이 유한해서 recycle 을 안 하면
 * /screen 반복 폴링에 고갈돼 서비스가 끊긴다. 33+ 는 recycle 이 no-op 이라 SDK 가드로 감싼다.
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

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    /**
     * 대상 창의 텍스트·클릭가능 노드를 (라벨, 중심x, 중심y, clickable, editable) 로 수집.
     * pkg 가 주어지면 그 패키지의 창을 콕 집어 읽는다(멀티윈도에서 rootInActiveWindow 가 엉뚱한
     * 창을 주는 문제 회피). 없으면 최상위 앱 창 → 그것도 없으면 활성 창으로 폴백.
     */
    fun dumpScreen(pkg: String?): JSONArray {
        val arr = JSONArray()
        val rect = Rect()
        val roots = targetRoots(pkg)
        try {
            for (root in roots) walk(root) { node ->
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val label = if (text.isNotEmpty()) text else desc
                if (label.isEmpty() && !node.isClickable && !node.isEditable && !node.isScrollable) return@walk
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) return@walk
                arr.put(
                    JSONObject()
                        .put("text", label.ifEmpty {
                            when {
                                node.isEditable -> "(입력창)"
                                node.isScrollable -> "(스크롤 영역)"
                                else -> "(빈 버튼)"
                            }
                        })
                        .put("x", rect.centerX())
                        .put("y", rect.centerY())
                        .put("clickable", node.isClickable)
                        .put("editable", node.isEditable)
                        .put("scrollable", node.isScrollable),
                )
            }
        } finally {
            roots.forEach { it.release() }
        }
        return arr
    }

    /** pkg 지정 시 일치하는 창만 반환. 미지정 시 최상위 앱 창 → 활성 창. 호출자가 release. */
    private fun targetRoots(pkg: String?): List<AccessibilityNodeInfo> {
        val ws = windows ?: emptyList()
        try {
            if (!pkg.isNullOrEmpty()) {
                val matched = ArrayList<AccessibilityNodeInfo>()
                for (w in ws) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == pkg) matched.add(r) else r.release()
                }
                if (matched.isNotEmpty()) return matched
                // 창 목록을 얻지 못해도 활성 창의 패키지는 반드시 확인한다.
                val active = rootInActiveWindow ?: return emptyList()
                if (active.packageName?.toString() == pkg) return listOf(active)
                active.release()
                return emptyList()
            }
            val top = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxByOrNull { it.layer }
                ?.root
            return listOfNotNull(top ?: rootInActiveWindow)
        } finally {
            ws.forEach { it.release() }
        }
    }

    /** 깊이 우선 순회. 자식 노드는 순회가 끝나면 회수한다(루트는 호출자 책임). */
    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, visit)
            } finally {
                child.release()
            }
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
    fun setFocusedText(text: String, pkg: String? = null): Boolean {
        val target = (if (pkg == null) findFocusedInput() else findFocusedInputInPackage(pkg)) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        try {
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            target.release()
        }
    }

    /** With an explicit app, require its focused editor rather than choosing another field. */
    private fun findFocusedInputInPackage(pkg: String): AccessibilityNodeInfo? {
        val roots = targetRoots(pkg)
        var found: AccessibilityNodeInfo? = null
        try {
            for (root in roots) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                if (focused.isEditable && focused.isEnabled && focused.isVisibleToUser) {
                    found = focused
                    break
                }
                if (focused !== root) focused.release()
            }
            return found
        } finally {
            roots.filter { it !== found }.forEach { it.release() }
        }
    }

    /** 반환 노드는 호출자가 release. 그 외 중간에 얻은 노드는 여기서 회수한다. */
    private fun findFocusedInput(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.release()
            if (focused != null) return focused
        }
        val ws = windows ?: emptyList()
        val roots = ws.mapNotNull { it.root }
        ws.forEach { it.release() }
        var found: AccessibilityNodeInfo? = null
        for (r in roots) {
            found = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (found != null) break
        }
        if (found == null) {
            for (r in roots) {
                found = findEditable(r)
                if (found != null) break
            }
        }
        for (r in roots) if (r !== found) r.release()
        if (found != null) return found
        val root = rootInActiveWindow ?: return null
        val editable = findEditable(root)
        if (editable !== root) root.release()
        return editable
    }

    /** node 자신 또는 하위에서 첫 편집 가능 노드. 반환 노드 이외의 자식은 회수한다. */
    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditable(child)
            if (hit != null) {
                if (hit !== child) child.release()
                return hit
            }
            child.release()
        }
        return null
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Scroll only a visible container in the requested app; never fall back to another app. */
    fun scroll(pkg: String?, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val roots = targetRoots(pkg)
        try {
            return roots.any { scrollFirst(it, action) }
        } finally {
            roots.forEach { it.release() }
        }
    }

    private fun scrollFirst(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isVisibleToUser && node.isEnabled && node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scrollFirst(child, action)) return true
            } finally {
                child.release()
            }
        }
        return false
    }
}
