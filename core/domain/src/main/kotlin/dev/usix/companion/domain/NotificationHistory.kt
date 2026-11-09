package dev.usix.companion.domain

/** Notification previews are evidence of exposed text, never a complete conversation. */
enum class NotificationRecordSource { Notification, MessagingStyle }

data class ArchivedNotification(
    val id: String,
    val packageId: String,
    val title: String,
    val sender: String,
    val text: String,
    val eventTimeMillis: Long,
    val capturedAtMillis: Long,
    val source: NotificationRecordSource,
    val truncated: Boolean,
    val attachment: Boolean,
    val recoveredActive: Boolean,
)

data class NotificationHistoryQuery(
    val packageId: String,
    val query: String = "",
    val sinceMillis: Long? = null,
    val untilMillis: Long? = null,
    val limit: Int = 50,
    val before: String? = null,
)

data class NotificationCaptureStatus(
    val listenerConnected: Boolean = false,
    val pendingBatches: Int = 0,
    val droppedBatches: Long = 0,
    val failedBatches: Long = 0,
)

data class NotificationHistoryPage(
    val records: List<ArchivedNotification>,
    val nextBefore: String?,
    val hasMore: Boolean,
    val startedAtMillis: Long,
    val oldestEventTimeMillis: Long?,
    val storedRecords: Int,
    val retentionMillis: Long,
    val maxRecords: Int,
    val capture: NotificationCaptureStatus,
    val dataLossPossible: Boolean,
)
