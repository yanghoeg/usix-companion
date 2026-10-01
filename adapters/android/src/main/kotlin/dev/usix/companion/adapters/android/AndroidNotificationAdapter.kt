package dev.usix.companion.adapters.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import dev.usix.companion.application.NotificationPort
import dev.usix.companion.domain.NotificationInfo
import dev.usix.companion.domain.NotificationRef
import dev.usix.companion.domain.PackageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Live Android handles are session-local; persistence of events/receipts belongs to P2. */
class AndroidNotificationAdapter(private val context: Context, private val mainDispatcher: CoroutineDispatcher) : NotificationPort {
    private class ReplyHandle(val pending: PendingIntent, val input: RemoteInput, val inputs: Array<RemoteInput>)
    private val items = ArrayDeque<NotificationInfo>()
    private val handles = HashMap<String, ReplyHandle>()
    @Volatile private var listenerConnected = false

    override fun connected() = listenerConnected

    @Synchronized
    fun connect(active: () -> Array<out StatusBarNotification>): Boolean {
        disconnect()
        val notifications = try { active() } catch (_: SecurityException) { return false }
        notifications.sortedBy { it.postTime }.forEach(::posted)
        listenerConnected = true
        return true
    }

    @Synchronized
    fun disconnect() {
        listenerConnected = false
        items.clear()
        handles.clear()
    }

    @Synchronized
    fun posted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        var handle: ReplyHandle? = null
        notification.actions?.forEach { action ->
            val inputs = action.remoteInputs
            val input = inputs?.firstOrNull { it.allowFreeFormInput }
            if (input != null && action.actionIntent != null) handle = ReplyHandle(action.actionIntent, input, inputs)
        }
        val item = NotificationInfo(NotificationRef(sbn.key), PackageId(sbn.packageName),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), sbn.postTime, handle != null)
        items.removeAll { it.reference == item.reference }
        items.addLast(item)
        while (items.size > MAX) handles.remove(items.removeFirst().reference.value)
        if (handle != null) handles[sbn.key] = requireNotNull(handle) else handles.remove(sbn.key)
    }

    @Synchronized
    fun removed(key: String) {
        items.removeAll { it.reference.value == key }
        handles.remove(key)
    }

    override suspend fun snapshot(): List<NotificationInfo> = synchronized(this) { items.toList().asReversed() }

    override suspend fun reply(reference: NotificationRef, text: String): Boolean = withContext(mainDispatcher) {
        // Removal/reconnect cannot race a stale handle into dispatch.
        synchronized(this@AndroidNotificationAdapter) {
            if (!listenerConnected) return@synchronized false
            val handle = handles[reference.value] ?: return@synchronized false
            val intent = Intent()
            val bundle = Bundle().apply { putCharSequence(handle.input.resultKey, text) }
            RemoteInput.addResultsToIntent(handle.inputs, intent, bundle)
            try {
                handle.pending.send(context, 0, intent)
                true
            } catch (_: PendingIntent.CanceledException) {
                handles.remove(reference.value)
                false
            }
        }
    }

    companion object { private const val MAX = 50 }
}
