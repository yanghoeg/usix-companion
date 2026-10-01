package dev.usix.companion.domain

/** Identifiers are decoded/validated at the wire boundary; no ambient device or cwd. */
data class ExecutionContext(
    val deviceId: String, val runtimeId: String, val sessionId: String,
    val taskId: String, val taskRevision: Long, val workspaceId: String,
)
data class ExecutionScope(
    val packageId: String?, val accountRef: String? = null,
    val resourceRefs: List<String> = emptyList(), val snapshotRef: String? = null,
)
data class LeaseRef(val leaseId: String, val revision: Long)
data class AuthorityRef(val kind: String, val ref: String?)
data class ExecutionCommand(
    val requestId: String, val context: ExecutionContext, val actionId: String,
    val operation: String, val scope: ExecutionScope, val payloadHash: String,
    val payloadEmpty: Boolean, val lease: LeaseRef?, val deadlineMillis: Long,
    val cancellationId: String?, val authority: AuthorityRef,
)
enum class ExecutionErrorCode {
    InvalidRequest, IdentityMismatch, UnsupportedVersion, UnsupportedCapability,
    PermissionRequired, AccessibilityDisconnected, DeviceLocked, AppMissing, Offline, Busy,
    StaleSnapshot, ExpiredReference, AmbiguousTarget, ControllerConflict, ActionConflict,
    ApprovalRequired, AuthorityExpired, AuthorityRevoked, Cancelled, DeadlineExceeded,
    UnknownEffect, EventGap,
}
data class ExecutionError(val code: ExecutionErrorCode, val message: String)
enum class ReceiptState { Accepted, Executing, Dispatched, Verified, NeedsVerification, UnknownEffect, Failed, Cancelled }
data class ExecutionReceipt(
    val receiptId: String, val command: ExecutionCommand, val revision: Long,
    val state: ReceiptState, val updatedAtMillis: Long,
    val cancellationRequested: Boolean = false, val error: ExecutionError? = null,
)
data class ControllerLease(val ref: LeaseRef, val sessionId: String, val runtimeId: String, val expiresAtMillis: Long)
/** P2 setup grant is deliberately limited to opening one explicitly selected package. */
data class ControllerSession(
    val context: ExecutionContext, val credentialHash: String, val expiresAtMillis: Long,
    val grantId: String, val packageId: String?, val maxActions: Int = 16,
    val actionsUsed: Int = 0, val revoked: Boolean = false,
    val deliveredCursor: Long = 0, val acknowledgedCursor: Long = 0,
    val displayName: String = "External runtime",
)
data class PairedRuntimeSummary(val sessionId: String, val displayName: String, val expiresAtMillis: Long, val revoked: Boolean, val selected: Boolean)
data class PairingChallenge(val challengeId: String, val nonce: String, val expiresAtMillis: Long)
data class PairedSession(val session: ControllerSession, val bearer: String)
sealed interface EventPayload {
    data class ActionUpdated(val context: ExecutionContext, val actionId: String, val receiptId: String, val receiptRevision: Long) : EventPayload
    data class ControllerChanged(val runtimeId: String?, val lease: LeaseRef?) : EventPayload
}
data class DeviceEvent(val eventId: String, val deviceId: String, val cursor: Long, val createdAtMillis: Long, val payload: EventPayload)
data class EventPage(val events: List<DeviceEvent>, val nextCursor: Long, val oldestAvailableCursor: Long, val resyncRequired: Boolean)
sealed interface ExecutionResult<out T> {
    data class Success<T>(val value: T) : ExecutionResult<T>
    data class Rejected(val error: ExecutionError) : ExecutionResult<Nothing>
}
