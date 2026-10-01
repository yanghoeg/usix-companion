package dev.usix.companion.adapters.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AccessibilityAdapterTest.GestureShadow::class])
class AccessibilityAdapterTest {
    @Test fun gestureUsesInjectedDispatcherAndSuspendsUntilCallback() = runTest {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow = Shadow.extract<GestureShadow>(service)
        val adapter = ConnectedAccessibilityAdapter(StandardTestDispatcher(testScheduler))
        adapter.attach(AccessibilityOperations(service))
        val result = async { adapter.tap(10, 20) }
        assertEquals(0, shadow.dispatches)
        runCurrent()
        assertEquals(1, shadow.dispatches)
        assertFalse(result.isCompleted)
        requireNotNull(shadow.callback).onCompleted(null)
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun missingGestureCallbackTimesOutAndLateCallbackIsIgnored() = runTest {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow = Shadow.extract<GestureShadow>(service)
        val adapter = ConnectedAccessibilityAdapter(StandardTestDispatcher(testScheduler))
        adapter.attach(AccessibilityOperations(service))
        val result = async { adapter.tap(10, 20) }
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(result.await())
        requireNotNull(shadow.callback).onCompleted(null)
    }

    @Test fun cancelledGestureDoesNotCompleteLater() = runTest {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        val shadow = Shadow.extract<GestureShadow>(service)
        val adapter = ConnectedAccessibilityAdapter(StandardTestDispatcher(testScheduler))
        adapter.attach(AccessibilityOperations(service))
        val result = async { adapter.tap(10, 20) }
        runCurrent()
        result.cancelAndJoin()
        requireNotNull(shadow.callback).onCompleted(null)
        assertTrue(result.isCancelled)
    }

    @Test fun staleServiceCannotDetachItsReplacement() = runTest {
        val first = AccessibilityOperations(Robolectric.buildService(TestAccessibilityService::class.java).create().get())
        val second = AccessibilityOperations(Robolectric.buildService(TestAccessibilityService::class.java).create().get())
        val adapter = ConnectedAccessibilityAdapter(StandardTestDispatcher(testScheduler))
        adapter.attach(first)
        adapter.attach(second)
        adapter.detach(first)
        assertTrue(adapter.accessibilityConnected())
        adapter.detach(second)
        assertFalse(adapter.accessibilityConnected())
        assertFalse(adapter.back())
        assertTrue(adapter.observe(null).isEmpty())
    }

    @Implements(AccessibilityService::class)
    class GestureShadow : ShadowAccessibilityService() {
        var dispatches = 0
        var callback: AccessibilityService.GestureResultCallback? = null
        @Implementation
        protected override fun dispatchGesture(gesture: GestureDescription, callback: AccessibilityService.GestureResultCallback?, handler: Handler?): Boolean {
            dispatches++
            this.callback = callback
            return true
        }
    }
}
