package dev.usix.companion

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ScreenQueryTest.ActiveWindowShadow::class])
@Suppress("DEPRECATION")
class ScreenQueryTest {
    private lateinit var service: UsixAccessibilityService
    private lateinit var shadow: ActiveWindowShadow

    @Before
    fun setUp() {
        service = Robolectric.buildService(UsixAccessibilityService::class.java).create().get()
        shadow = Shadow.extract(service)
        UiController.service = service
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun missingPackageDoesNotReturnAnotherAppsWindow() {
        shadow.setWindows(listOf(window("other.app", "private text", 1)))
        shadow.activeRoot = node("other.app", "active private text")

        assertEquals(0, service.dumpScreen("wanted.app").length())
    }

    @Test
    fun packageFilterReturnsOnlyMatchingWindows() {
        shadow.setWindows(listOf(
            window("wanted.app", "wanted", 1),
            window("other.app", "other", 2),
            window("wanted.app", "wanted dialog", 3),
        ))

        assertEquals(listOf("wanted", "wanted dialog"), labels(service.dumpScreen("wanted.app")))
    }

    @Test
    fun matchingActiveWindowWorksWhenWindowListIsUnavailable() {
        shadow.activeRoot = node("wanted.app", "wanted")

        assertEquals(listOf("wanted"), labels(service.dumpScreen("wanted.app")))
    }

    @Test
    fun unrelatedActiveWindowIsRejectedWhenWindowListIsUnavailable() {
        shadow.activeRoot = node("other.app", "private text")

        assertEquals(0, service.dumpScreen("wanted.app").length())
    }

    @Test
    fun noPackageStillSelectsTopApplicationWindow() {
        shadow.setWindows(listOf(window("bottom.app", "bottom", 1), window("top.app", "top", 4)))

        assertEquals(listOf("top"), labels(service.dumpScreen(null)))
    }

    @Test
    fun noPackageStillFallsBackToActiveWindow() {
        shadow.activeRoot = node("active.app", "active")

        assertEquals(listOf("active"), labels(service.dumpScreen(null)))
    }

    @Test
    fun encodedPackageIsDecodedAndOtherQueryValuesCannotOverrideIt() {
        shadow.setWindows(listOf(
            window("wanted.app", "wanted", 1), window("other.app", "other", 2),
        ))

        val (status, body) = route("/screen?notpackage=other.app&package=wanted%2Eapp")

        assertEquals("200 OK", status)
        assertEquals(listOf("wanted"), labels(JSONArray(body)))
    }

    @Test
    fun routePrefixesDoNotMatchRealEndpoints() {
        assertEquals("404 Not Found", route("/screen-private").first)
        assertEquals("404 Not Found", route("/notifications-private").first)
        assertEquals("200 OK", route("/notifications?limit=50").first)
    }

    @Test
    fun screenWithoutAccessibilityReturnsUnavailable() {
        UiController.service = null

        assertEquals("503 Service Unavailable", route("/screen?package=wanted.app").first)
    }

    private fun labels(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getJSONObject(it).getString("text") }

    private fun node(pkg: String, label: String): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            text = label
            setBoundsInScreen(Rect(0, 0, 100, 100))
        }

    private fun window(pkg: String, label: String, layer: Int): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setRoot(node(pkg, label))
            shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(it).setLayer(layer)
        }

    // Exercise routing and the real screen traversal without binding the fixed production port.
    @Suppress("UNCHECKED_CAST")
    private fun route(path: String): Pair<String, String> {
        val method = BridgeServer::class.java.getDeclaredMethod(
            "route", String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(BridgeServer, "GET", path, "", true) as Pair<String, String>
    }

    @Implements(AccessibilityService::class)
    class ActiveWindowShadow : ShadowAccessibilityService() {
        var activeRoot: AccessibilityNodeInfo? = null

        @Implementation
        protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRoot
    }
}
