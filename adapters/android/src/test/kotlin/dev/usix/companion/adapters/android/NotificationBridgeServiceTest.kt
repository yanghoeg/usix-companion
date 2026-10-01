package dev.usix.companion.adapters.android

import dev.usix.companion.domain.NotificationRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationBridgeServiceTest {
    private lateinit var service: TestNotificationService
    private lateinit var notifications: AndroidNotificationAdapter

    @Before
    fun setUp() {
        service = Robolectric.buildService(TestNotificationService::class.java).create().get()
        notifications = AndroidNotificationAdapter(service, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        notifications.disconnect()
    }

    @Test
    fun connectionRestoresNewestFiftyRegardlessOfSystemOrder() {
        // Deliberately newest first: insertion order must not evict recent notifications.
        for (id in 60 downTo 1) shadowOf(service).addActiveNotification(notification(id))

        notifications.connect { service.activeNotifications.orEmpty() }

        assertTrue(notifications.connected())
        assertEquals((60L downTo 11L).toList(), snapshot().map { it.postTime })
        assertEquals("title 60", snapshot().first().title)
        assertEquals("text 60", snapshot().first().text)
    }

    @Test
    fun restoredNotificationCanSendInlineReply() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val input = RemoteInput.Builder("reply").build()
        val action = Notification.Action.Builder(0, "Reply", pending).addRemoteInput(input).build()
        val sbn = notification(1, action)
        shadowOf(service).addActiveNotification(sbn)

        notifications.connect { service.activeNotifications.orEmpty() }

        assertTrue(snapshot().single().canReply)
        assertTrue(reply(sbn.key, "안녕하세요"))
        val sent = shadowOf(service.application).broadcastIntents.last()
        assertEquals("안녕하세요", RemoteInput.getResultsFromIntent(sent).getCharSequence("reply"))
    }

    @Test
    fun reconnectReplacesStaleNotificationsAndReplyHandles() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val action = Notification.Action.Builder(0, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val old = notification(1, action)
        notifications.posted(old)
        val current = notification(2)
        shadowOf(service).addActiveNotification(current)

        notifications.connect { service.activeNotifications.orEmpty() }

        assertEquals(listOf(current.key), snapshot().map { it.reference.value })
        assertFalse(reply(old.key, "stale"))
    }

    @Test
    fun restoredNotificationsStillReceiveUpdatesAndRemovals() {
        val sbn = notification(1)
        shadowOf(service).addActiveNotification(sbn)
        notifications.connect { service.activeNotifications.orEmpty() }

        notifications.posted(sbn)
        assertEquals(1, snapshot().size)
        notifications.removed(sbn.key)
        assertTrue(snapshot().isEmpty())
    }

    @Test
    fun disconnectDiscardsRestoredNotifications() {
        shadowOf(service).addActiveNotification(notification(1))
        notifications.connect { service.activeNotifications.orEmpty() }

        notifications.disconnect()

        assertFalse(notifications.connected())
        assertTrue(snapshot().isEmpty())
    }

    private fun snapshot() = runBlocking { notifications.snapshot() }
    private fun reply(key: String, text: String) = runBlocking { notifications.reply(NotificationRef(key), text) }

    @Test
    fun permissionLossDuringReconnectClearsHandlesAndReadiness() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val action = Notification.Action.Builder(0, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val item = notification(1, action)
        notifications.connect { arrayOf(item) }
        assertFalse(notifications.connect { throw SecurityException("access revoked") })
        assertFalse(notifications.connected())
        assertTrue(snapshot().isEmpty())
        assertFalse(reply(item.key, "stale"))
    }

    @Test
    fun updatedNotificationWithoutReplyAndRemovedNotificationInvalidateHandles() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val action = Notification.Action.Builder(0, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val item = notification(1, action)
        notifications.connect { arrayOf(item) }
        notifications.posted(notification(1))
        assertFalse(reply(item.key, "stale"))
        notifications.posted(item)
        notifications.removed(item.key)
        assertFalse(reply(item.key, "stale"))
    }

    @Test
    fun cancelledPendingIntentDoesNotReportReplyAcceptance() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val action = Notification.Action.Builder(0, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val item = notification(1, action)
        notifications.connect { arrayOf(item) }
        pending.cancel()
        assertFalse(reply(item.key, "stale"))
        assertFalse(reply(item.key, "stale"))
    }

    private fun notification(id: Int, action: Notification.Action? = null): StatusBarNotification {
        val builder = Notification.Builder(service, "test")
            .setContentTitle("title $id").setContentText("text $id")
        action?.let { builder.addAction(it) }
        return StatusBarNotification(
            "test.chat", "test.chat", id, null, 1000, 0, 0,
            builder.build(), Process.myUserHandle(), id.toLong(),
        )
    }
}
