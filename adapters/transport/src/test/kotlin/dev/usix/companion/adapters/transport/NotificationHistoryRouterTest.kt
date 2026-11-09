package dev.usix.companion.adapters.transport

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.testing.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationHistoryRouterTest {
    private val store = MemoryNotificationHistoryStore()
    private val fake = FakeDevice().apply { accessibility = false }
    private val history = NotificationHistory(store, ExecutionClock { 2000 }, NotificationCaptureStatusPort { NotificationCaptureStatus(true) })
    private val repository = MemoryExecutionRepository()
    private val execution = DeviceExecution(repository, ExecutionClock { 2000 }, ExecutionIds { "id" }, ExecutionCrypto(),
        ExecutionReadiness { _, _ -> ExecutionError(ExecutionErrorCode.DeviceLocked, "locked") }, fake.application(), notificationHistory = history)
    private val actions = object : ExecuteDeviceAction { override suspend fun execute(action: DeviceAction) = execution.executeLegacy(action) }
    private val router = LegacyDeviceRouter(fake.application(), actions, notificationHistory = history)
    private val path = "/notifications/history?package=com.kakao.talk"
    private val clear = "{\"package\":\"com.kakao.talk\"}"

    @Test fun pairedQueryWorksWhileLockedWithoutOpeningObservingOrFocusingAnApp() = runBlocking {
        history.record(listOf(notificationCapture(1)))
        val result = router.route("GET", path, "", true)
        assertEquals("200 OK", result.status)
        val json = JSONObject(result.json)
        assertEquals("notification_history", json.getString("source")); assertFalse(json.getBoolean("conversationComplete"))
        val record = json.getJSONArray("records").getJSONObject(0)
        assertFalse(record.has("key")); assertFalse(record.has("canReply"))
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun bothHistoryAndClearRequirePairingAndExplicitKakaoScope() = runBlocking {
        assertEquals("401 Unauthorized", router.route("GET", path, "", false).status)
        assertEquals("401 Unauthorized", router.route("POST", "/notifications/history/clear", clear, false).status)
        assertEquals("400 Bad Request", router.route("GET", "/notifications/history", "", true).status)
        assertEquals("400 Bad Request", router.route("GET", "/notifications/history?package=other.app", "", true).status)
        assertEquals("400 Bad Request", router.route("POST", "/notifications/history/clear", "{\"package\":\"other.app\"}", true).status)
    }
    @Test fun unknownDuplicateMalformedAndUnboundedQueryArgumentsAreRejected() = runBlocking {
        for (suffix in listOf("&unknown=1", "&package=com.kakao.talk", "&limit=0", "&limit=101", "&since=-1",
            "&since=1.5", "&since=99999999999999999999", "&since=4&until=3", "&before=guess", "&query=%ZZ"))
            assertEquals(suffix, "400 Bad Request", router.route("GET", path + suffix, "", true).status)
        for (body in listOf("{}", "{\"package\":null}", clear.dropLast(1) + ",\"allApps\":true}"))
            assertEquals("400 Bad Request", router.route("POST", "/notifications/history/clear", body, true).status)
    }
    @Test fun clearDeletesOnlyHistoryAndActiveControllerBlocksItWithoutBlockingReads() = runBlocking {
        history.record(listOf(notificationCapture(1)))
        repository.putLease(ControllerLease(LeaseRef("lease", 1), "session", "runtime", 10_000))
        assertEquals("409 Conflict", router.route("POST", "/notifications/history/clear", clear, true).status)
        assertEquals(1, JSONObject(router.route("GET", path, "", true).json).getJSONArray("records").length())
        repository.putLease(null)
        assertEquals("200 OK", router.route("POST", "/notifications/history/clear", clear, true).status)
        assertTrue(store.archive.records.isEmpty()); assertTrue(fake.calls.isEmpty())
        assertEquals(DeviceErrorCode.APPROVAL_REQUIRED,
            (fake.application().execute(DeviceAction.ClearNotificationHistory(NotificationHistory.KAKAO_PACKAGE)) as DeviceResult.Rejected).error.code)
    }
    @Test fun unavailableStoreReturnsErrorAndEmptyHistoryNeverMeansEmptyConversation() = runBlocking {
        store.failReads = true
        assertEquals("503 Service Unavailable", router.route("GET", path, "", true).status)
        store.failReads = false
        val empty = JSONObject(router.route("GET", path, "", true).json)
        assertEquals(0, empty.getJSONArray("records").length()); assertFalse(empty.getBoolean("conversationComplete"))
    }
    @Test fun responseBudgetProvidesAContinuationInsteadOfSilentlyDroppingLongRecords() = runBlocking {
        history.record((1..64).map { notificationCapture(it, "\u0001".repeat(8192)) })
        history.record((65..100).map { notificationCapture(it, "\u0001".repeat(8192)) })
        val first = router.route("GET", "$path&limit=100", "", true)
        assertTrue(first.json.toByteArray().size < 600 * 1024)
        val json = JSONObject(first.json); assertTrue(json.getBoolean("hasMore"))
        val next = JSONObject(router.route("GET", "$path&limit=100&before=${json.getString("nextBefore")}", "", true).json)
        assertNotEquals(json.getJSONArray("records").getJSONObject(0).getString("id"), next.getJSONArray("records").getJSONObject(0).getString("id"))
    }
}
