package dev.usix.companion.adapters.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.usix.companion.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
}
