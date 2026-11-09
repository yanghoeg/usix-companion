package dev.usix.companion.testing

import dev.usix.companion.application.*
import dev.usix.companion.domain.*

class MemoryNotificationHistoryStore : NotificationHistoryStore {
    var archive = NotificationArchive(0, emptyList())
    var failWrites = false
    var failReads = false
    override suspend fun load(): NotificationArchive {
        if (failReads) throw NotificationHistoryStorageException()
        return archive.copy(records = archive.records.toList())
    }
    override suspend fun append(records: List<ArchivedNotification>) {
        if (failWrites) throw NotificationHistoryStorageException()
        archive = archive.copy(records = archive.records + records)
    }
    override suspend fun replace(archive: NotificationArchive) {
        if (failWrites) throw NotificationHistoryStorageException()
        this.archive = archive.copy(records = archive.records.toList())
    }
}

fun notificationCapture(number: Int, text: String = "preview $number", time: Long = 1000) = NotificationCapture(
    number.toString(16).padStart(64, '0'), NotificationHistory.KAKAO_PACKAGE, "Family", "Sender", text,
    time, NotificationRecordSource.MessagingStyle)
