package dev.usix.companion.application

import dev.usix.companion.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class NotificationArchive(
    val startedAtMillis: Long,
    val records: List<ArchivedNotification>,
    val recoveredTail: Boolean = false,
)

/** Implementations acknowledge only durable writes and translate storage failures. */
interface NotificationHistoryStore {
    suspend fun load(): NotificationArchive
    suspend fun append(records: List<ArchivedNotification>)
    suspend fun replace(archive: NotificationArchive)
}
class NotificationHistoryStorageException(cause: Throwable? = null) : Exception("Notification history storage unavailable", cause)
fun interface NotificationCaptureStatusPort { fun status(): NotificationCaptureStatus }

/** The listener creates opaque identities; no Android keys, reply handles or attachments are stored. */
data class NotificationCapture(
    val id: String,
    val packageId: String,
    val title: String,
    val sender: String,
    val text: String,
    val eventTimeMillis: Long,
    val source: NotificationRecordSource,
    val truncated: Boolean = false,
    val attachment: Boolean = false,
    val recoveredActive: Boolean = false,
)

class NotificationHistory(
    private val store: NotificationHistoryStore,
    private val clock: ExecutionClock,
    private val capture: NotificationCaptureStatusPort,
    val retentionMillis: Long = 30L * 24 * 60 * 60 * 1000,
    val maxRecords: Int = 10_000,
) {
    private val mutex = Mutex()
    private var archive: NotificationArchive? = null
    @Volatile private var generation = 0L
    val captureGeneration: Long get() = generation

    init { require(retentionMillis > 0 && maxRecords > 0) }

    suspend fun record(batch: List<NotificationCapture>, capturedGeneration: Long? = null): DeviceResult<Int> = access {
        if (capturedGeneration != null && capturedGeneration != generation) return@access 0
        require(batch.size <= 64) { "At most 64 notification previews per batch" }
        batch.forEach {
            require(it.packageId == KAKAO_PACKAGE && ID.matches(it.id) && it.eventTimeMillis >= 0)
            require(it.title.length <= 512 && it.sender.length <= 512 && it.text.length <= 8192)
        }
        val old = current()
        val ids = old.records.mapTo(mutableSetOf()) { it.id }
        val now = clock.nowMillis()
        val fresh = batch.filter { it.eventTimeMillis >= now - retentionMillis && ids.add(it.id) }.map {
            ArchivedNotification(it.id, it.packageId, it.title, it.sender, it.text, it.eventTimeMillis, now,
                it.source, it.truncated, it.attachment, it.recoveredActive)
        }
        if (fresh.isNotEmpty()) {
            val joined = old.records + fresh
            val retained = joined.takeLast(maxRecords)
            val next = old.copy(records = retained)
            withContext(NonCancellable) {
                if (joined.size > maxRecords) store.replace(next) else store.append(fresh)
                archive = next
            }
        }
        fresh.size
    }

    suspend fun query(query: NotificationHistoryQuery): DeviceResult<NotificationHistoryPage> = access {
        val since = query.sinceMillis
        val until = query.untilMillis
        val before = query.before
        require(query.packageId == KAKAO_PACKAGE) { "History is collected only for com.kakao.talk" }
        require(query.query.length <= 256 && query.limit in 1..100) { "query must be at most 256 characters and limit 1..100" }
        require(since == null || since >= 0)
        require(until == null || until >= 0)
        require(since == null || until == null || since <= until)
        require(before == null || ID.matches(before)) { "Invalid history cursor" }
        val current = current()
        val records = current.records.asReversed()
        val offset = before?.let { cursor ->
            val index = records.indexOfFirst { it.id == cursor }
            require(index >= 0) { "History cursor expired or was cleared; restart the query" }
            index + 1
        } ?: 0
        val matches = records.drop(offset).asSequence().filter {
            (since == null || it.eventTimeMillis >= since) &&
            (until == null || it.eventTimeMillis < until) &&
            (query.query.isEmpty() || listOf(it.title, it.sender, it.text).any { field -> field.contains(query.query, ignoreCase = true) })
        }.take(query.limit + 1).toList()
        val page = matches.take(query.limit)
        val status = capture.status()
        NotificationHistoryPage(page, if (matches.size > query.limit) page.last().id else null,
            matches.size > query.limit, current.startedAtMillis, current.records.minOfOrNull { it.eventTimeMillis },
            current.records.size, retentionMillis, maxRecords, status,
            current.recoveredTail || status.droppedBatches > 0 || status.failedBatches > 0)
    }

    suspend fun clear(packageId: String): DeviceResult<ActionOutcome> = access {
        require(packageId == KAKAO_PACKAGE) { "History is collected only for com.kakao.talk" }
        val empty = NotificationArchive(clock.nowMillis(), emptyList())
        withContext(NonCancellable) {
            store.replace(empty)
            archive = empty
            generation++
        }
        ActionOutcome(true, ActionKind.NOTIFICATION_HISTORY)
    }

    suspend fun prune(): DeviceResult<Unit> = access { current(); Unit }

    private suspend fun current(): NotificationArchive {
        val old = archive ?: store.load().also { loaded ->
            if (!loaded.records.all { it.packageId == KAKAO_PACKAGE && ID.matches(it.id) }) throw NotificationHistoryStorageException()
            archive = loaded
        }
        val retained = old.records.filter { it.eventTimeMillis >= clock.nowMillis() - retentionMillis }.takeLast(maxRecords)
        val next = old.copy(startedAtMillis = if (old.startedAtMillis == 0L) clock.nowMillis() else old.startedAtMillis, records = retained)
        if (next != old) withContext(NonCancellable) { store.replace(next); archive = next }
        return next
    }

    private suspend fun <T> access(block: suspend () -> T): DeviceResult<T> = mutex.withLock {
        try { DeviceResult.Success(block()) }
        catch (_: NotificationHistoryStorageException) {
            archive = null
            DeviceResult.Rejected(DeviceError(DeviceErrorCode.NOTIFICATION_HISTORY_UNAVAILABLE, "Notification history storage unavailable; no complete result can be returned"))
        } catch (error: IllegalArgumentException) {
            DeviceResult.Rejected(DeviceError(DeviceErrorCode.INVALID_ARGUMENT, error.message ?: "Invalid history arguments"))
        }
    }

    companion object {
        const val KAKAO_PACKAGE = "com.kakao.talk"
        private val ID = Regex("[0-9a-f]{64}")
    }
}
