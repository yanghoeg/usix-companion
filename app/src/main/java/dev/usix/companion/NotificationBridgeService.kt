package dev.usix.companion

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.usix.companion.adapters.android.AndroidNotificationAdapter
import dev.usix.companion.adapters.transport.LoopbackBridgeServer
import javax.inject.Inject

@AndroidEntryPoint
class NotificationBridgeService : NotificationListenerService() {
    @Inject lateinit var notifications: AndroidNotificationAdapter
    @Inject lateinit var bridge: LoopbackBridgeServer
    override fun onListenerConnected() {
        if (!notifications.connect { activeNotifications.orEmpty() }) return
        bridge.start()
        try { BridgeForegroundService.start(this) } catch (_: Exception) {
            // Android may deny background foreground-service startup; opening the app retries.
        }
    }
    override fun onListenerDisconnected() { notifications.disconnect() }
    override fun onDestroy() {
        notifications.disconnect()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { notifications.posted(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { notifications.removed(sbn.key) }
}
