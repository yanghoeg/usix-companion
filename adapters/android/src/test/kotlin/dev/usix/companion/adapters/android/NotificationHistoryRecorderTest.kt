package dev.usix.companion.adapters.android

import android.app.Notification
import android.app.Person
import android.os.Process
import android.service.notification.StatusBarNotification
import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.testing.MemoryNotificationHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationHistoryRecorderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun notification(time: Long = 1000, pkg: String = NotificationHistory.KAKAO_PACKAGE, summary: Boolean = false,
        text: String = "본문", messaging: Boolean = false, second: Boolean = false): StatusBarNotification {
        val builder = Notification.Builder(context, "test").setContentTitle("가족방").setContentText(text)
        if (summary) builder.setGroup("group").setGroupSummary(true)
        if (messaging) {
            val sender = Person.Builder().setName("엄마").build()
            val style = Notification.MessagingStyle(Person.Builder().setName("나").build()).setConversationTitle("가족방")
                .addMessage(text, 900, sender)
            if (second) style.addMessage("추가 메시지", 950, sender)
            builder.setStyle(style)
        }
        return StatusBarNotification(pkg, pkg, 1, null, 1000, 0, 0, builder.build(), Process.myUserHandle(), time)
    }
    @Test fun messagingUpdatesAndReconnectDeduplicateActualMessageIdentities() = runTest {
        val store = MemoryNotificationHistoryStore(); val state = NotificationHistoryCaptureState()
        val history = NotificationHistory(store, ExecutionClock { 2000 }, state)
        val recorder = NotificationHistoryRecorder(history, state, StandardTestDispatcher(testScheduler), maintenanceIntervalMillis = null)
        try {
            recorder.posted(notification(messaging = true))
            recorder.posted(notification(time = 1100, messaging = true, second = true))
            recorder.posted(notification(time = 1100, messaging = true, second = true), recoveredActive = true)
            advanceUntilIdle()
            val records = store.archive.records
            assertEquals(2, records.size); assertEquals(listOf(900L, 950L), records.map { it.eventTimeMillis })
            assertEquals("가족방", records.first().title); assertEquals("엄마", records.first().sender)
            assertEquals(NotificationRecordSource.MessagingStyle, records.first().source)
            assertFalse(records.first().recoveredActive)
        } finally { recorder.close() }
    }
    @Test fun removalAndDisconnectEraseLiveHandlesButPreserveDurableText() = runTest {
        val store = MemoryNotificationHistoryStore(); val state = NotificationHistoryCaptureState()
        val history = NotificationHistory(store, ExecutionClock { 2000 }, state)
        val recorder = NotificationHistoryRecorder(history, state, StandardTestDispatcher(testScheduler), maintenanceIntervalMillis = null)
        val adapter = AndroidNotificationAdapter(context, Dispatchers.Unconfined, recorder)
        try {
            val posted = notification()
            adapter.connect { arrayOf(posted) }; advanceUntilIdle()
            adapter.removed(posted.key); adapter.disconnect()
            assertTrue(adapter.snapshot().isEmpty()); assertFalse(state.status().listenerConnected)
            assertEquals(1, store.archive.records.size); assertTrue(store.archive.records.first().recoveredActive)
            adapter.connect { arrayOf(posted) }; advanceUntilIdle()
            assertEquals(1, store.archive.records.size)
        } finally { recorder.close() }
    }
    @Test fun otherAppsAndGroupSummariesAreNeverArchived() {
        assertTrue(NotificationHistoryExtractor.extract(notification(pkg = "test.chat")).isEmpty())
        assertTrue(NotificationHistoryExtractor.extract(notification(summary = true)).isEmpty())
    }
    @Test fun clearingHistoryDoesNotAllowPreviouslyQueuedPreviewsToReappear() = runTest {
        val store = MemoryNotificationHistoryStore(); val state = NotificationHistoryCaptureState()
        val history = NotificationHistory(store, ExecutionClock { 2000 }, state)
        val recorder = NotificationHistoryRecorder(history, state, StandardTestDispatcher(testScheduler), maintenanceIntervalMillis = null)
        try {
            recorder.posted(notification())
            history.clear(NotificationHistory.KAKAO_PACKAGE)
            advanceUntilIdle()
            assertTrue(store.archive.records.isEmpty())
            recorder.posted(notification(time = 1001)); advanceUntilIdle()
            assertEquals(1, store.archive.records.size)
        } finally { recorder.close() }
    }
    @Test fun bigTextIsPreferredAndUnicodeTruncationDoesNotSplitSurrogatePairs() {
        val sbn = notification(text = "short")
        sbn.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "😀".repeat(4097))
        val item = NotificationHistoryExtractor.extract(sbn).single()
        assertEquals(4096, item.text.codePointCount(0, item.text.length)); assertTrue(item.truncated)
        assertEquals("😀", item.text.takeLast(2)); assertEquals(NotificationRecordSource.Notification, item.source)
    }
    @Test fun boundedQueueAndStorageFailuresAreReportedWithoutCrashingListener() = runTest {
        val store = MemoryNotificationHistoryStore(); val state = NotificationHistoryCaptureState()
        val history = NotificationHistory(store, ExecutionClock { 2000 }, state)
        val recorder = NotificationHistoryRecorder(history, state, StandardTestDispatcher(testScheduler), capacity = 1, maintenanceIntervalMillis = null)
        try {
            recorder.posted(notification()); recorder.posted(notification(time = 1001))
            assertEquals(1L, state.status().droppedBatches)
            assertEquals(1, state.status().pendingBatches)
            store.failWrites = true; advanceUntilIdle()
            assertEquals(0, state.status().pendingBatches); assertEquals(1L, state.status().failedBatches)
            store.failWrites = false
            val page = (history.query(NotificationHistoryQuery(NotificationHistory.KAKAO_PACKAGE)) as DeviceResult.Success).value
            assertTrue(page.dataLossPossible)
        } finally { recorder.close() }
    }
    @Test fun notificationsWithoutMessageContentRemainPreviewsAndCannotBecomeReplyHandles() {
        val item = NotificationHistoryExtractor.extract(notification()).single()
        assertEquals("", item.sender); assertEquals("본문", item.text)
        assertEquals(64, item.id.length); assertFalse(item.id.contains("com.kakao"))
        assertFalse(item.attachment)
    }
}
