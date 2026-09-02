package dev.usix.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 알림 접근 권한이 켜지면 시스템이 바인딩한다. 게시되는 알림을 NotifStore 에 쌓고,
 * 답장 가능한(RemoteInput 이 달린) 알림은 답장 핸들도 함께 보관한다.
 */
class NotificationBridgeService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotifStore.listenerConnected = true
        NotifStore.appContext = applicationContext
        BridgeServer.start(applicationContext)
        // 재부팅 등으로 리스너가 붙으면 프로세스도 포그라운드로 고정 시도. 백그라운드-시작이
        // 제한되는 버전(Android 12+)에선 예외가 날 수 있어 삼킨다 — 그땐 앱을 열면 고정된다.
        try {
            BridgeForegroundService.start(this)
        } catch (e: Exception) {
            // best-effort — MainActivity 를 열면 확실히 고정된다.
        }
    }

    override fun onListenerDisconnected() {
        NotifStore.listenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        var pending: PendingIntent? = null
        var reply: RemoteInput? = null
        var allInputs: Array<RemoteInput>? = null
        n.actions?.forEach { action ->
            val inputs = action.remoteInputs
            val free = inputs?.firstOrNull { it.allowFreeFormInput }
            if (free != null && action.actionIntent != null) {
                pending = action.actionIntent
                reply = free
                allInputs = inputs
            }
        }

        NotifStore.add(
            NotifItem(sbn.key, sbn.packageName, title, text, sbn.postTime, reply != null),
            pending,
            reply,
            allInputs,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifStore.remove(sbn.key)
    }
}
