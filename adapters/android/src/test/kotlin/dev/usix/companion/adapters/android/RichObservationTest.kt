package dev.usix.companion.adapters.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.usix.companion.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RichObservationTest.ActiveRoot::class])
@Suppress("DEPRECATION")
class RichObservationTest {
    @Implements(AccessibilityService::class)
    class ActiveRoot : ShadowAccessibilityService() {
        var root: AccessibilityNodeInfo? = null
        var windowFactory: (() -> List<AccessibilityWindowInfo>)? = null
        // Android returns fresh window handles; the default shadow reuses recycled instances.
        @Implementation override fun getWindows(): List<AccessibilityWindowInfo> = windowFactory?.invoke() ?: super.getWindows()
        @Implementation protected fun getRootInActiveWindow() = root?.let { AccessibilityNodeInfo.obtain(it).apply {
            org.robolectric.Shadows.shadowOf(this).setRefreshReturnValue(true)
        } }
    }
    @Test fun resourceIdsBoundsStatesAndPasswordMaskSurviveAdapterBoundary() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        shadow.root = AccessibilityNodeInfo.obtain().apply {
            packageName = CONTROLLED_UI_PACKAGE; text = "CONTROLLED_SECRET_123"; contentDescription = "secret description"
            viewIdResourceName = "$CONTROLLED_UI_PACKAGE:id/password"; className = "android.widget.EditText"
            isPassword = true; isEditable = true; isEnabled = true; isVisibleToUser = true; setBoundsInScreen(Rect(10, 20, 300, 100))
        }
        val operations = AccessibilityOperations(service); val adapter = ConnectedAccessibilityAdapter(Dispatchers.Unconfined); adapter.attach(operations)
        val snapshot = (adapter.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        val node = snapshot.nodes.single()
        assertEquals("[redacted]", node.text); assertEquals("[redacted]", node.description); assertTrue(node.sensitive)
        assertEquals(ScreenBounds(10, 20, 300, 100), node.bounds); assertEquals("$CONTROLLED_UI_PACKAGE:id/password", node.resourceId)
        assertEquals("[redacted]", adapter.observe(PackageId(CONTROLLED_UI_PACKAGE)).single().text)
        assertEquals(ExecutionErrorCode.UnsupportedCapability, adapter.captureReadiness("korean")!!.code)
        adapter.detach(operations); assertEquals(ExecutionErrorCode.AccessibilityDisconnected, adapter.captureReadiness(null)!!.code)
        service.onDestroy()
    }
    @Test fun foregroundPackageMismatchExposesNoPrivateNodes() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        shadow.root = AccessibilityNodeInfo.obtain().apply { packageName = "dev.other.app"; text = "private" }
        val adapter = ConnectedAccessibilityAdapter(Dispatchers.Unconfined); val operations = AccessibilityOperations(service); adapter.attach(operations)
        assertEquals(ExecutionErrorCode.ExpiredReference, (adapter.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Rejected).error.code)
        service.onDestroy()
    }

    @Test fun unfocusedPopupCanBeReadWithoutExposingTheFocusedAppOrDispatchingGlobalActions() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        shadow.windowFactory = { listOf(
            window("dev.other.app", "other private text", 1, Rect(0, 0, 320, 470), true),
            window(CONTROLLED_UI_PACKAGE, "popup content", 2, Rect(160, 0, 300, 180), false)) }
        val operations = RichAccessibilityOperations(service) { 1 }
        val observed = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        assertEquals(2, observed.windowId)
        assertEquals(false, observed.windowFocused); assertEquals(1, observed.focusedWindowId)
        assertEquals(listOf("popup content"), observed.nodes.map { it.text })
        assertEquals(ScreenBounds(160, 0, 300, 180), observed.windowBounds)
        for (action in listOf("ui.back", "ui.home", "ui.tap", "ui.swipe")) {
            val effect = operations.perform(observed, action, UiActionRequest(x = 200, y = 50, endX = 220, endY = 80)) { null }
            assertEquals(UiEffectState.None, effect.state)
            assertEquals(ExecutionErrorCode.Busy, effect.error!!.code)
        }
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertTrue(shadow.gesturesDispatched.isEmpty())
        service.onDestroy()
    }

    @Test fun fullscreenWindowNeverReceivesAnAutomatedEffect() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        shadow.windowFactory = { listOf(window(CONTROLLED_UI_PACKAGE, "fullscreen", 1, Rect(0, 0, 320, 470), true)) }
        val operations = RichAccessibilityOperations(service) { 1 }
        val display = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value.displayBounds
        shadow.windowFactory = { listOf(window(CONTROLLED_UI_PACKAGE, "fullscreen", 1,
            Rect(display.left, display.top, display.right, display.bottom), true)) }
        val observed = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        val effect = operations.perform(observed, "ui.back", UiActionRequest()) { null }
        assertEquals(UiEffectState.None, effect.state)
        assertEquals(ExecutionErrorCode.UnsupportedCapability, effect.error!!.code)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        service.onDestroy()
    }

    @Test fun unfocusedPopupScrollUsesOnlyItsOwnNodeAndDirectionalAction() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        val actions = mutableListOf<Int>()
        shadow.windowFactory = { listOf(
            window("dev.other.app", "other private text", 1, Rect(0, 0, 320, 470), true),
            window(CONTROLLED_UI_PACKAGE, "scroll popup", 2, Rect(160, 0, 300, 180), false) { node ->
                node.isScrollable = true
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                shadowOf(node).setOnPerformActionListener { action, _ -> actions.add(action); true }
            }) }
        val operations = RichAccessibilityOperations(service) { 1 }
        for (direction in listOf("up", "down")) {
            val observed = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
            val effect = operations.perform(observed, "ui.scroll", UiActionRequest(selector = NodeSelector(nodeRef = "n"),
                forward = direction == "down", verticalDirection = direction)) { null }
            assertEquals(UiEffectState.Dispatched, effect.state)
        }
        assertEquals(listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id), actions)
        assertTrue(shadow.gesturesDispatched.isEmpty()); assertTrue(shadow.globalActionsPerformed.isEmpty())
        service.onDestroy()
    }

    @Test fun popupScrollFallsBackToContainerForwardBackwardWithoutDisplayGestures() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        val actions = mutableListOf<Int>()
        shadow.windowFactory = { listOf(window(CONTROLLED_UI_PACKAGE, "scroll popup", 2, Rect(160, 0, 300, 180), false) { node ->
            node.isScrollable = true
            shadowOf(node).setOnPerformActionListener { action, _ -> actions.add(action); true }
        }) }
        val operations = RichAccessibilityOperations(service) { 1 }
        for (direction in listOf("up", "down")) {
            val observed = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
            operations.perform(observed, "ui.scroll", UiActionRequest(selector = NodeSelector(nodeRef = "n"),
                forward = direction == "down", verticalDirection = direction)) { null }
        }
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions)
        assertTrue(shadow.gesturesDispatched.isEmpty())
        service.onDestroy()
    }

    @Test fun readingScrollKeepsTheOtherAppFocusedWhenItsContentAndGlobalRevisionChange() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        var revision = 1L
        var otherText = "user is typing"
        val actions = mutableListOf<Int>()
        shadow.windowFactory = { listOf(
            window("dev.other.app", otherText, 1, Rect(0, 0, 320, 470), true),
            window(CONTROLLED_UI_PACKAGE, "popup list", 2, Rect(160, 0, 300, 180), false) { node ->
                node.isScrollable = true
                shadowOf(node).setOnPerformActionListener { action, _ -> actions.add(action); true }
            }) }
        val operations = RichAccessibilityOperations(service) { revision }
        val before = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        revision++; otherText = "user keeps typing"
        val request = UiActionRequest(selector = NodeSelector(nodeRef = "n"), verticalDirection = "down", windowScoped = true)
        assertEquals(UiEffectState.None, operations.perform(before, "ui.scroll", request.copy(windowScoped = false)) { null }.state)
        assertEquals(UiEffectState.Dispatched, operations.perform(before, "ui.scroll", request) { null }.state)
        val after = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        assertEquals(before.focusedWindowId, after.focusedWindowId); assertEquals(false, after.windowFocused)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions)
        assertTrue(shadow.globalActionsPerformed.isEmpty()); assertTrue(shadow.gesturesDispatched.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
        service.onDestroy()
    }

    @Test fun readingScrollStillRejectsChangedPopupContentsBoundsOrFocusContext() = runBlocking {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow: ActiveRoot = Shadow.extract(service)
        var revision = 1L
        var popupText = "popup list"
        var bounds = Rect(160, 0, 300, 180)
        var popupFocused = false
        var dispatches = 0
        shadow.windowFactory = { listOf(
            window("dev.other.app", "private", 1, Rect(0, 0, 320, 470), !popupFocused),
            window(CONTROLLED_UI_PACKAGE, popupText, 2, bounds, popupFocused) { node ->
                node.isScrollable = true
                shadowOf(node).setOnPerformActionListener { _, _ -> dispatches++; true }
            }) }
        val operations = RichAccessibilityOperations(service) { revision }
        val before = (operations.observe(CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        val request = UiActionRequest(selector = NodeSelector(nodeRef = "n"), verticalDirection = "down", windowScoped = true)
        revision++; popupText = "different popup content"
        assertEquals(UiEffectState.None, operations.perform(before, "ui.scroll", request) { null }.state)
        popupText = "popup list"; bounds = Rect(140, 0, 300, 180)
        assertEquals(UiEffectState.None, operations.perform(before, "ui.scroll", request) { null }.state)
        bounds = Rect(160, 0, 300, 180); popupFocused = true
        assertEquals(UiEffectState.None, operations.perform(before, "ui.scroll", request) { null }.state)
        assertEquals(0, dispatches); assertTrue(shadow.globalActionsPerformed.isEmpty())
        service.onDestroy()
    }

    private fun window(packageName: String, label: String, id: Int, bounds: Rect, focused: Boolean,
        customize: (AccessibilityNodeInfo) -> Unit = {}): AccessibilityWindowInfo {
        val node = AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName; text = label; isEnabled = true; isVisibleToUser = true
            setBoundsInScreen(bounds)
        }
        shadowOf(node).setRefreshReturnValue(true)
        customize(node)
        return AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setRoot(node); shadowOf(this).setId(id); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setLayer(id); shadowOf(this).setFocused(focused); shadowOf(this).setActive(focused)
            shadowOf(this).setBoundsInScreen(bounds)
        }
    }
}
