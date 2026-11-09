package dev.usix.companion.application

import dev.usix.companion.domain.*
import dev.usix.companion.testing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NotificationHistoryTest {
    private var now = 1000L
    private val store = MemoryNotificationHistoryStore()
    private var status = NotificationCaptureStatus(listenerConnected = true)
    private fun history(retention: Long = 30L * 86_400_000, max: Int = 10_000) = NotificationHistory(store,
        ExecutionClock { now }, NotificationCaptureStatusPort { status }, retention, max)
    private val query = NotificationHistoryQuery(NotificationHistory.KAKAO_PACKAGE)
    private suspend fun page(history: NotificationHistory, query: NotificationHistoryQuery = this.query) =
        (history.query(query) as DeviceResult.Success).value

    @Test fun duplicatesStayDeduplicatedAfterRestartWithoutCollapsingRepeatedMessages() = runBlocking {
        val history = history()
        val first = notificationCapture(1)
        assertEquals(1, (history.record(listOf(first, first)) as DeviceResult.Success).value)
        assertEquals(0, (history().record(listOf(first.copy(recoveredActive = true))) as DeviceResult.Success).value)
        history.record(listOf(notificationCapture(2, first.text, 1001)))
        val result = page(history())
        assertEquals(listOf(notificationCapture(2).id, first.id), result.records.map { it.id })
        assertEquals(1000L, result.startedAtMillis)
    }
    @Test fun paginationRemainsStableWhenNewNotificationsArrive() = runBlocking {
        val history = history()
        history.record((1..4).map { notificationCapture(it) })
        val first = page(history, query.copy(limit = 2))
        assertTrue(first.hasMore)
        history.record(listOf(notificationCapture(5)))
        val second = page(history, query.copy(limit = 2, before = first.nextBefore))
        assertEquals(listOf(2, 1).map { notificationCapture(it).id }, second.records.map { it.id })
        assertFalse(second.hasMore); assertNull(second.nextBefore)
        history.clear(query.packageId)
        assertEquals(DeviceErrorCode.INVALID_ARGUMENT, (history.query(query.copy(before = first.nextBefore)) as DeviceResult.Rejected).error.code)
    }
    @Test fun literalUnicodeKeywordAndExclusiveTimeRangeSearchDoNotInventMessages() = runBlocking {
        val history = history()
        history.record(listOf(notificationCapture(1, "회의 ABC", 100), notificationCapture(2, "회의 DEF", 200),
            notificationCapture(3, "문자 그대로 .*", 300)))
        assertEquals(1, page(history, query.copy(query = "abc", sinceMillis = 100, untilMillis = 200)).records.size)
        assertEquals(1, page(history, query.copy(query = ".*")).records.size)
        assertEquals(3, page(history, query.copy(query = "family")).records.size)
        assertTrue(page(history, query.copy(query = "없음")).records.isEmpty())
    }
    @Test fun retentionAndCountLimitsRemoveOldDataFromDurableStorage() = runBlocking {
        val history = history(retention = 100, max = 2)
        history.record((1..3).map { notificationCapture(it, time = now) })
        assertEquals(2, store.archive.records.size)
        now += 101
        assertTrue(page(history).records.isEmpty())
        assertTrue(store.archive.records.isEmpty())
        history.record(listOf(notificationCapture(4, time = 1000)))
        assertTrue(store.archive.records.isEmpty())
    }
    @Test fun writeOrReadFailuresReturnUnavailableAndDoNotCreatePhantomSuccess() = runBlocking {
        val history = history()
        page(history)
        store.failWrites = true
        assertEquals(DeviceErrorCode.NOTIFICATION_HISTORY_UNAVAILABLE,
            (history.record(listOf(notificationCapture(1))) as DeviceResult.Rejected).error.code)
        assertTrue(store.archive.records.isEmpty())
        store.failReads = true
        assertTrue(history.query(query) is DeviceResult.Rejected)
        store.failWrites = false; store.failReads = false
        assertTrue(page(history).records.isEmpty())
    }
    @Test fun validationCannotBroadenPackageScopeOrAcceptUnboundedArguments() = runBlocking {
        val history = history()
        for (invalid in listOf(query.copy(packageId = "other.app"), query.copy(limit = 101), query.copy(limit = 0),
            query.copy(query = "x".repeat(257)), query.copy(sinceMillis = -1), query.copy(sinceMillis = 3, untilMillis = 2), query.copy(before = "fake")))
            assertEquals(DeviceErrorCode.INVALID_ARGUMENT, (history.query(invalid) as DeviceResult.Rejected).error.code)
        assertTrue(history.record(listOf(notificationCapture(1).copy(packageId = "other.app"))) is DeviceResult.Rejected)
        assertTrue(store.archive.records.isEmpty())
    }
    @Test fun concurrentArrivalsAreSerializedAndCaptureGapsRemainVisible() = runBlocking {
        val history = history()
        coroutineScope { (1..100).forEach { launch { history.record(listOf(notificationCapture(it))) } } }
        status = status.copy(listenerConnected = false, droppedBatches = 2, failedBatches = 1)
        val result = page(history, query.copy(limit = 100))
        assertEquals(100, result.records.map { it.id }.toSet().size)
        assertTrue(result.dataLossPossible); assertFalse(result.capture.listenerConnected)
        store.archive = store.archive.copy(recoveredTail = true)
        status = NotificationCaptureStatus()
        assertTrue(page(history()).dataLossPossible)
    }
    @Test fun cancelledCaptureCannotHideARecordAlreadyCommittedToStorage() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val durable = object : NotificationHistoryStore by store {
            override suspend fun append(records: List<ArchivedNotification>) {
                store.append(records); entered.complete(Unit); release.await()
            }
        }
        val history = NotificationHistory(durable, ExecutionClock { now }, NotificationCaptureStatusPort { status })
        val job = launch { history.record(listOf(notificationCapture(1))) }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(1, page(history).records.size)
        assertEquals(store.archive.records, page(history).records)
    }
    @Test fun cancelledClearCommitsItsGenerationSoOldQueuedCapturesStayDeleted() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var pause = false
        val durable = object : NotificationHistoryStore by store {
            override suspend fun replace(archive: NotificationArchive) {
                store.replace(archive)
                if (pause) { entered.complete(Unit); release.await() }
            }
        }
        val history = NotificationHistory(durable, ExecutionClock { now }, NotificationCaptureStatusPort { status })
        history.record(listOf(notificationCapture(1)))
        val oldGeneration = history.captureGeneration
        pause = true
        val job = launch { history.clear(query.packageId) }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(page(history).records.isEmpty())
        assertEquals(0, (history.record(listOf(notificationCapture(2)), oldGeneration) as DeviceResult.Success).value)
        assertTrue(store.archive.records.isEmpty())
    }
}
