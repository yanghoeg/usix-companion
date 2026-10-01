package dev.usix.companion.domain

@JvmInline
value class PackageId(val value: String)

@JvmInline
value class NotificationRef(val value: String)

data class DeviceHealth(val listenerConnected: Boolean, val accessibilityConnected: Boolean)

/** Legacy observation values; Android node handles and JSON stay in adapters. */
data class ScreenNode(
    val text: String,
    val x: Int,
    val y: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
)

data class NotificationInfo(
    val reference: NotificationRef,
    val packageId: PackageId,
    val title: String,
    val text: String,
    val postTime: Long,
    val canReply: Boolean,
)

data class MailDraft(val to: String, val subject: String, val body: String, val packageId: PackageId)

enum class ActionKind { UI, APP_LAUNCH, MAIL_DRAFT, NOTIFICATION_REPLY }

/** Acceptance is only dispatch acknowledgement, never verified completion. */
data class ActionOutcome(val accepted: Boolean, val kind: ActionKind, val detail: String? = null)

enum class DeviceErrorCode { INVALID_ARGUMENT, ACCESSIBILITY_UNAVAILABLE, CONTROLLER_CONFLICT, APPROVAL_REQUIRED }
data class DeviceError(val code: DeviceErrorCode, val message: String)

sealed interface DeviceResult<out T> {
    data class Success<T>(val value: T) : DeviceResult<T>
    data class Rejected(val error: DeviceError) : DeviceResult<Nothing>
}
