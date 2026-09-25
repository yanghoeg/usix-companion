package dev.usix.companion

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
    private lateinit var service: NotificationBridgeService

    @Before
    fun setUp() {
        NotifStore.clear()
        service = Robolectric.buildService(NotificationBridgeService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        NotifStore.appContext = null
    }

    @Test
    fun connectionRestoresNewestFiftyRegardlessOfSystemOrder() {
        // Deliberately newest first: insertion order must not evict recent notifications.
        for (id in 60 downTo 1) shadowOf(service).addActiveNotification(notification(id))

        service.onListenerConnected()

        assertTrue(NotifStore.listenerConnected)
        assertEquals((60L downTo 11L).toList(), NotifStore.snapshot().map { it.postTime })
        assertEquals("title 60", NotifStore.snapshot().first().title)
        assertEquals("text 60", NotifStore.snapshot().first().text)
    }

    @Test
    fun restoredNotificationCanSendInlineReply() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val input = RemoteInput.Builder("reply").build()
        val action = Notification.Action.Builder(0, "Reply", pending).addRemoteInput(input).build()
        val sbn = notification(1, action)
        shadowOf(service).addActiveNotification(sbn)

        service.onListenerConnected()

        assertTrue(NotifStore.snapshot().single().canReply)
        assertTrue(NotifStore.reply(sbn.key, "안녕하세요"))
        val sent = shadowOf(service.application).broadcastIntents.last()
        assertEquals("안녕하세요", RemoteInput.getResultsFromIntent(sent).getCharSequence("reply"))
    }

    @Test
    fun reconnectReplacesStaleNotificationsAndReplyHandles() {
        val pending = PendingIntent.getBroadcast(service, 0, Intent("test.REPLY"), 0)
        val action = Notification.Action.Builder(0, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build()
        val old = notification(1, action)
        service.onNotificationPosted(old)
        val current = notification(2)
        shadowOf(service).addActiveNotification(current)

        service.onListenerConnected()

        assertEquals(listOf(current.key), NotifStore.snapshot().map { it.key })
        assertFalse(NotifStore.reply(old.key, "stale"))
    }

    @Test
    fun restoredNotificationsStillReceiveUpdatesAndRemovals() {
        val sbn = notification(1)
        shadowOf(service).addActiveNotification(sbn)
        service.onListenerConnected()

        service.onNotificationPosted(sbn)
        assertEquals(1, NotifStore.snapshot().size)
        service.onNotificationRemoved(sbn)
        assertTrue(NotifStore.snapshot().isEmpty())
    }

    @Test
    fun disconnectDiscardsRestoredNotifications() {
        shadowOf(service).addActiveNotification(notification(1))
        service.onListenerConnected()

        service.onListenerDisconnected()

        assertFalse(NotifStore.listenerConnected)
        assertTrue(NotifStore.snapshot().isEmpty())
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
