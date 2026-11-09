package dev.usix.companion.adapters.transport

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.protocol.StrictJson
import dev.usix.companion.testing.FakeDevice
import dev.usix.companion.testing.FakeRichObservation
import dev.usix.companion.testing.MemoryExecutionRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadNavigationRouterTest {
    private var sequence = 1
    private val store = MemoryExecutionRepository()
    private val clock = ExecutionClock { 1000L }
    private val ids = ExecutionIds { "00000000-0000-4000-8000-${(sequence++).toString().padStart(12, '0')}" }
    private val port = FakeRichObservation().apply {
        screen = screen.copy(windowBounds = ScreenBounds(0, 0, 250, 400), nodes = listOf(screen.nodes.first().copy(scrollable = true)),
            windowFocused = false, focusedWindowId = 77)
    }
    private val navigation = ReadNavigation(port, clock, ids)
    private val fake = FakeDevice()
    private val app = fake.application()
    private val execution = DeviceExecution(store, clock, ids, ExecutionCrypto(), ExecutionReadiness { _, _ -> null }, app, readNavigation = navigation)
    private val router = LegacyDeviceRouter(app, object : ExecuteDeviceAction {
        override suspend fun execute(action: DeviceAction) = execution.executeLegacy(action)
    }, navigation)
    private suspend fun window(paired: Boolean = true) = router.route("GET", "/read-window?package=$CONTROLLED_UI_PACKAGE", "", paired)
    private suspend fun select() = router.route("POST", "/read-select", "{\"package\":\"$CONTROLLED_UI_PACKAGE\"}", true)
    private fun body(ref: String, packageId: String = CONTROLLED_UI_PACKAGE) = StrictJson.encode(mapOf(
        "package" to packageId, "direction" to "up", "windowRef" to ref, "nodeRef" to "n/0"))

    @Test fun authenticatedSelectedPopupCanScrollOnceWhileGeneralUiRemainsForbidden() = runBlocking {
        assertFalse(JSONObject(window().json).getBoolean("navigationAvailable"))
        select()
        val view = JSONObject(window().json)
        assertTrue(view.getBoolean("compact")); assertTrue(view.getBoolean("navigationAvailable"))
        assertEquals("n/0", view.getJSONArray("scrollTargets").getJSONObject(0).getString("nodeRef"))
        val request = body(view.getString("windowRef"))
        val response = router.route("POST", "/read-scroll", request, true)
        val result = JSONObject(response.json)
        assertTrue(result.getBoolean("ok")); assertEquals("dispatched", result.getString("effect"))
        assertTrue(result.getBoolean("windowRefConsumed")); assertTrue(result.getBoolean("readRequired"))
        assertEquals("409 Conflict", router.route("POST", "/read-scroll", request, true).status)
        assertEquals("403 Forbidden", router.route("POST", "/scroll", "{\"direction\":\"up\",\"package\":\"$CONTROLLED_UI_PACKAGE\"}", true).status)
        assertEquals("403 Forbidden", router.route("POST", "/type", "{\"text\":\"cannot send\"}", true).status)
        assertEquals(1, port.effects)
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun selectingAndRenewingAnExistingPopupNeverLaunchesOrFocusesAnApp() = runBlocking {
        val first = JSONObject(select().json)
        assertTrue(first.getBoolean("navigationAvailable")); assertFalse(first.getBoolean("windowFocused"))
        assertEquals(77, first.getInt("focusedWindowId"))
        val renewed = JSONObject(select().json)
        assertNotEquals(first.getString("windowRef"), renewed.getString("windowRef"))
        assertEquals("409 Conflict", router.route("POST", "/read-scroll", body(first.getString("windowRef")), true).status)
        assertEquals(77, renewed.getInt("focusedWindowId")); assertFalse(renewed.getBoolean("windowFocused"))
        assertTrue(fake.calls.isEmpty()); assertEquals(0, port.effects)
    }
    @Test fun unpairedRequestsAndUnscopedOrMalformedArgumentsCannotNavigate() = runBlocking {
        assertEquals("401 Unauthorized", window(false).status)
        assertEquals("401 Unauthorized", router.route("POST", "/read-select", "{\"package\":\"$CONTROLLED_UI_PACKAGE\"}", false).status)
        assertEquals(0, port.observations)
        assertEquals("400 Bad Request", router.route("GET", "/read-window", "", true).status)
        for (invalid in listOf("{}", "{\"package\":null}", "{\"package\":\"$CONTROLLED_UI_PACKAGE\",\"launch\":true}"))
            assertEquals("400 Bad Request", router.route("POST", "/read-select", invalid, true).status)
        select(); val ref = JSONObject(window().json).getString("windowRef")
        assertEquals("401 Unauthorized", router.route("POST", "/read-scroll", body(ref), false).status)
        for (invalid in listOf("{}", "{\"package\":null}", body(ref).replace("\"up\"", "null"), body(ref).dropLast(1) + ",\"text\":\"send\"}"))
            assertEquals("400 Bad Request", router.route("POST", "/read-scroll", invalid, true).status)
        assertEquals("403 Forbidden", router.route("POST", "/read-scroll", body(ref, "com.other.app"), true).status)
        assertEquals(0, port.effects)
    }
    @Test fun unavailableWindowAndFullscreenContentAreReportedWithoutEmptySuccessOrPrivateText() = runBlocking {
        val missing = router.route("GET", "/read-window?package=com.other.app", "", true)
        assertEquals("409 Conflict", missing.status)
        assertFalse(JSONObject(missing.json).getBoolean("ok"))
        assertEquals("409 Conflict", router.route("POST", "/read-select", "{\"package\":\"com.other.app\"}", true).status)
        select(); port.screen = port.screen.copy(windowBounds = port.screen.displayBounds)
        assertEquals("409 Conflict", select().status)
        val fullscreen = JSONObject(window().json)
        assertFalse(fullscreen.getBoolean("compact")); assertTrue(fullscreen.isNull("windowRef"))
        assertEquals(0, fullscreen.getJSONArray("nodes").length()); assertEquals(0, fullscreen.getJSONArray("scrollTargets").length())
        assertFalse(fullscreen.toString().contains("eval-account"))
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun pauseAndV2ControllerStillRejectReadingEffects() = runBlocking {
        select(); val beforePause = JSONObject(window().json).getString("windowRef")
        execution.pause()
        assertEquals("409 Conflict", router.route("POST", "/read-scroll", body(beforePause), true).status)
        select(); val ref = JSONObject(window().json).getString("windowRef")
        val challenge = execution.challenge()
        val context = ExecutionContext(store.deviceId(), ids.next(), ids.next(), ids.next(), 1, ids.next())
        val paired = (execution.pair(challenge.challengeId, challenge.nonce, context, CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        execution.acquire(paired.session)
        val observationsBefore = port.observations
        assertEquals("409 Conflict", select().status)
        assertEquals(observationsBefore, port.observations)
        assertEquals("409 Conflict", router.route("POST", "/read-scroll", body(ref), true).status)
        assertEquals(0, port.effects)
    }
}
