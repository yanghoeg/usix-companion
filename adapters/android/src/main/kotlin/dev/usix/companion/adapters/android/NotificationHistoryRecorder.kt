package dev.usix.companion.adapters.android

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.usix.companion.application.NotificationCapture
import dev.usix.companion.application.NotificationCaptureStatusPort
import dev.usix.companion.application.NotificationHistory
import dev.usix.companion.domain.DeviceResult
import dev.usix.companion.domain.NotificationCaptureStatus
import dev.usix.companion.domain.NotificationRecordSource
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class NotificationHistoryCaptureState : NotificationCaptureStatusPort {
    @Volatile var connected = false
    internal val pending = AtomicInteger()
    internal val dropped = AtomicLong()
    internal val failed = AtomicLong()
    override fun status() = NotificationCaptureStatus(connected, pending.get(), dropped.get(), failed.get())
}

/** Listener callbacks never wait for storage. One bounded worker preserves callback ordering. */
class NotificationHistoryRecorder(
    private val history: NotificationHistory,
    private val state: NotificationHistoryCaptureState,
    dispatcher: CoroutineDispatcher,
    capacity: Int = 128,
    maintenanceIntervalMillis: Long? = 3_600_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private data class Batch(val records: List<NotificationCapture>, val generation: Long)
    private val queue = Channel<Batch>(capacity)
    init {
        if (maintenanceIntervalMillis != null) {
            require(maintenanceIntervalMillis > 0)
            scope.launch {
                while (isActive) {
                    try { history.prune() } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { state.failed.incrementAndGet() }
                    delay(maintenanceIntervalMillis)
                }
            }
        }
        scope.launch {
            for (batch in queue) {
                try {
                    if (history.record(batch.records, batch.generation) is DeviceResult.Rejected) state.failed.incrementAndGet()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { state.failed.incrementAndGet() }
                finally { state.pending.decrementAndGet() }
            }
        }
    }
    fun connected(value: Boolean) { state.connected = value }
    fun posted(notification: StatusBarNotification, recoveredActive: Boolean = false) {
        if (notification.packageName != NotificationHistory.KAKAO_PACKAGE) return
        val generation = history.captureGeneration
        val batch = try { NotificationHistoryExtractor.extract(notification, recoveredActive) }
        catch (_: RuntimeException) { state.failed.incrementAndGet(); return }
        if (batch.isEmpty()) return
        state.pending.incrementAndGet()
        if (!queue.trySend(Batch(batch, generation)).isSuccess) { state.pending.decrementAndGet(); state.dropped.incrementAndGet() }
    }
    fun close() { queue.close(); scope.cancel() }
}

/** Uses documented notification previews only. Group summaries and other apps are excluded. */
object NotificationHistoryExtractor {
    @Suppress("DEPRECATION")
    fun extract(sbn: StatusBarNotification, recoveredActive: Boolean = false): List<NotificationCapture> {
        if (sbn.packageName != NotificationHistory.KAKAO_PACKAGE) return emptyList()
        val notification = sbn.notification ?: return emptyList()
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val extras = notification.extras
        val title = bounded((extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty(), 256)
        if (Build.VERSION.SDK_INT >= 30) {
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles?.takeLast(64)?.toTypedArray())
            if (messages.isNotEmpty()) return messages.map { message ->
                val text = bounded(message.text?.toString().orEmpty(), 4096)
                val sender = bounded((message.senderPerson?.name ?: message.sender)?.toString().orEmpty(), 256)
                val time = message.timestamp.takeIf { it > 0 } ?: sbn.postTime
                val source = NotificationRecordSource.MessagingStyle
                NotificationCapture(identity(sbn, time, sender.first, text.first, source), sbn.packageName,
                    title.first, sender.first, text.first, time, source,
                    title.second || sender.second || text.second || (bundles?.size ?: 0) > 64,
                    message.dataMimeType != null || message.dataUri != null, recoveredActive)
            }
        }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val raw = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: lines?.takeLast(64)?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = bounded(raw, 4096)
        if (title.first.isBlank() && text.first.isBlank()) return emptyList()
        val source = NotificationRecordSource.Notification
        return listOf(NotificationCapture(identity(sbn, sbn.postTime, "", text.first, source), sbn.packageName,
            title.first, "", text.first, sbn.postTime, source,
            title.second || text.second || (lines?.size ?: 0) > 64, recoveredActive = recoveredActive))
    }

    private fun bounded(value: String, codePoints: Int): Pair<String, Boolean> {
        if (value.codePointCount(0, value.length) <= codePoints) return value to false
        return value.substring(0, value.offsetByCodePoints(0, codePoints)) to true
    }
    private fun identity(sbn: StatusBarNotification, time: Long, sender: String, text: String, source: NotificationRecordSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(sbn.packageName, sbn.key, time.toString(), sender, text, source.name).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
