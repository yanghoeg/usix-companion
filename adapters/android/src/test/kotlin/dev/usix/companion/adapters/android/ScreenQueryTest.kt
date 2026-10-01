package dev.usix.companion.adapters.android

import dev.usix.companion.application.DeviceApplication
import dev.usix.companion.adapters.transport.LegacyDeviceRouter
import dev.usix.companion.testing.FakeDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import dev.usix.companion.protocol.LegacyCodec
import dev.usix.companion.protocol.LegacyScreenNode
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var service: TestAccessibilityService
    private lateinit var operations: AccessibilityOperations
    private lateinit var connection: ConnectedAccessibilityAdapter
    private lateinit var router: LegacyDeviceRouter
    private lateinit var shadow: ActiveWindowShadow

    @Before
    fun setUp() {
        service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        shadow = Shadow.extract(service)
        operations = AccessibilityOperations(service)
        connection = ConnectedAccessibilityAdapter(Dispatchers.Unconfined)
        connection.attach(operations)
        val fake = FakeDevice()
        val application = DeviceApplication(connection, connection, connection, fake, fake)
        router = LegacyDeviceRouter(application, application)
    }

    @After
    fun tearDown() {
        connection.detach(operations)
        service.onDestroy()
    }

    @Test
    fun missingPackageDoesNotReturnAnotherAppsWindow() {
        shadow.setWindows(listOf(window("other.app", "private text", 1)))
        shadow.activeRoot = node("other.app", "active private text")

        assertEquals(0, screen("wanted.app").length())
    }

    @Test
    fun packageFilterReturnsOnlyMatchingWindows() {
        shadow.setWindows(listOf(
            window("wanted.app", "wanted", 1),
            window("other.app", "other", 2),
            window("wanted.app", "wanted dialog", 3),
        ))

        assertEquals(listOf("wanted", "wanted dialog"), labels(screen("wanted.app")))
    }

    @Test
    fun matchingActiveWindowWorksWhenWindowListIsUnavailable() {
        shadow.activeRoot = node("wanted.app", "wanted")

        assertEquals(listOf("wanted"), labels(screen("wanted.app")))
    }

    @Test
    fun unrelatedActiveWindowIsRejectedWhenWindowListIsUnavailable() {
        shadow.activeRoot = node("other.app", "private text")

        assertEquals(0, screen("wanted.app").length())
    }

    @Test
    fun noPackageStillSelectsTopApplicationWindow() {
        shadow.setWindows(listOf(window("bottom.app", "bottom", 1), window("top.app", "top", 4)))

        assertEquals(listOf("top"), labels(screen(null)))
    }

    @Test
    fun noPackageStillFallsBackToActiveWindow() {
        shadow.activeRoot = node("active.app", "active")

        assertEquals(listOf("active"), labels(screen(null)))
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
        connection.detach(operations)

        assertEquals("503 Service Unavailable", route("/screen?package=wanted.app").first)
    }

    @Test
    fun emptyReplyFieldAndScrollContainerAreExposed() {
        shadow.activeRoot = node("mail.app", "").apply { isEditable = true }
        val input = screen("mail.app").getJSONObject(0)
        assertTrue(input.getBoolean("editable"))
        assertEquals("(입력창)", input.getString("text"))

        shadow.activeRoot = node("mail.app", "").apply { isScrollable = true }
        val container = screen("mail.app").getJSONObject(0)
        assertTrue(container.getBoolean("scrollable"))
    }

    @Test
    fun scrollOnlyActsOnTheRequestedAppAndUsesTheRequestedDirection() {
        val actions = mutableListOf<Int>()
        val root = node("mail.app", "Inbox").apply {
            isScrollable = true
            isVisibleToUser = true
            isEnabled = true
        }
        shadowOf(root).setOnPerformActionListener { action, _ -> actions.add(action); true }
        shadow.activeRoot = root

        assertFalse(operations.scroll("other.app", true))
        assertTrue(actions.isEmpty())
        assertTrue(operations.scroll("mail.app", true))
        assertTrue(operations.scroll("mail.app", false))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), actions)
    }

    @Test
    fun scrollRejectsHiddenContainersAndReportsEndOfContent() {
        var attempts = 0
        val root = node("mail.app", "Inbox").apply {
            isScrollable = true
            isEnabled = true
        }
        shadowOf(root).setOnPerformActionListener { _, _ -> attempts++; false }
        shadow.activeRoot = root

        assertFalse(operations.scroll("mail.app", true))
        assertEquals(0, attempts)
        root.isVisibleToUser = true
        val result = route("/scroll", "POST", "{\"direction\":\"down\",\"package\":\"mail.app\"}")
        assertEquals("200 OK", result.first)
        assertFalse(JSONObject(result.second).getBoolean("ok"))
        assertEquals(1, attempts)
    }

    @Test
    fun scrollValidatesArgumentsAndRequiresAccessibility() {
        assertEquals("400 Bad Request", route("/scroll", "POST", "{\"direction\":\"left\"}").first)
        assertEquals("400 Bad Request", route("/scroll", "POST", "{\"direction\":\"down\",\"package\":null}").first)
        connection.detach(operations)
        assertEquals("503 Service Unavailable", route("/scroll", "POST", "{\"direction\":\"down\"}").first)
    }

    @Test
    fun scopedReplyCannotTypeInAnotherAppsEditor() {
        var actions = 0
        val root = node("other.app", "Private editor").apply {
            isEditable = true
            isEnabled = true
            isVisibleToUser = true
        }
        shadowOf(root).setOnPerformActionListener { _, _ -> actions++; true }
        shadow.activeRoot = root

        val result = route("/type", "POST", "{\"text\":\"reply\",\"package\":\"mail.app\"}")

        assertEquals("200 OK", result.first)
        assertFalse(JSONObject(result.second).getBoolean("ok"))
        assertEquals(0, actions)
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

    private fun screen(pkg: String?): JSONArray = JSONArray(LegacyCodec.screen(operations.dumpScreen(pkg).map {
        LegacyScreenNode(it.text, it.x, it.y, it.clickable, it.editable, it.scrollable)
    }).json)

    // Public adapter routing; no reflection and no production port binding.
    private fun route(path: String, verb: String = "GET", body: String = ""): Pair<String, String> {
        val response = runBlocking { router.route(verb, path, body, true) }
        return response.status to response.json
    }

    @Implements(AccessibilityService::class)
    class ActiveWindowShadow : ShadowAccessibilityService() {
        var activeRoot: AccessibilityNodeInfo? = null

        @Implementation
        protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRoot?.let { AccessibilityNodeInfo.obtain(it) }
    }
}
