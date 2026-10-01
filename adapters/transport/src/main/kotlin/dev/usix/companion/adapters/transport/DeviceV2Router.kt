package dev.usix.companion.adapters.transport

import dev.usix.companion.application.CredentialVerifier
import dev.usix.companion.application.DeviceExecution
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.ExecutionReadiness
import dev.usix.companion.application.RemoteConnectionControl
import dev.usix.companion.domain.*
import dev.usix.companion.protocol.*

data class DeviceAuthorization(val owner: Boolean, val session: ControllerSession?)

class DeviceV2Router(
    private val execution: DeviceExecution,
    private val query: DeviceQuery,
    private val owner: CredentialVerifier,
    private val readiness: ExecutionReadiness,
    private val now: () -> Long,
) {
    var remote: RemoteConnectionControl? = null
    fun authorize(path: String, bearer: String?): DeviceAuthorization? = if (path.startsWith("/v2/pair/") || path.startsWith("/v2/admin/")) {
        if (owner.verify(bearer)) DeviceAuthorization(true, null) else null
    } else execution.authenticate(bearer)?.let { DeviceAuthorization(false, it) }

    suspend fun route(method: String, path: String, body: String, authorization: DeviceAuthorization): LegacyResponse { return try {
        if (method != "POST") return DeviceV2Codec.failure("InvalidRequest", "v2 routes require POST")
        if (path == "/v2/execute") {
            val session = authorization.session ?: return DeviceV2Codec.failure("IdentityMismatch", "Paired session required", "401 Unauthorized")
            val command = DeviceV2Codec.command(body)
            return result(execution.execute(session, command.domain())) { receipt(it) }
        }
        val data = StrictJson.objectValue(body)
        if (data["contractVersion"] != DEVICE_CONTRACT) throw ProtocolFailure("UnsupportedVersion", "Unsupported contract version")
        val requestId = DeviceV2Codec.id(data["requestId"])
        fun fields(vararg extra: String) = DeviceV2Codec.fields(data, "contractVersion", "requestId", *extra)
        fun response(kind: String, value: Map<String, Any?> = emptyMap()) = DeviceV2Codec.response(mapOf(
            "contractVersion" to DEVICE_CONTRACT, "kind" to kind, "requestId" to requestId) + value)
        if (authorization.owner) return when (path) {
            "/v2/pair/challenge" -> {
                fields(); val challenge = execution.challenge()
                response("pairing_challenge", mapOf("deviceId" to execution.deviceId, "challengeId" to challenge.challengeId,
                    "nonce" to challenge.nonce, "expiresAt" to DeviceV2Codec.time(challenge.expiresAtMillis)))
            }
            "/v2/pair/complete" -> {
                fields("challengeId", "nonce", "context", "packageId", "displayName")
                val name = DeviceV2Codec.text(data["displayName"], 64)
                if (name !in setOf("USIX", "USIX Termux")) throw ProtocolFailure(message = "Known setup label required")
                result(execution.pair(DeviceV2Codec.id(data["challengeId"]), DeviceV2Codec.text(data["nonce"], 128),
                    DeviceV2Codec.context(data["context"]).domain(), DeviceV2Codec.packageId(data["packageId"]), name)) {
                    mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "paired", "requestId" to requestId,
                        "context" to context(it.session.context), "bearer" to it.bearer, "grantRef" to it.session.grantId,
                        "expiresAt" to DeviceV2Codec.time(it.session.expiresAtMillis), "maxActions" to it.session.maxActions)
                }
            }
            "/v2/admin/pause" -> { fields(); execution.pause(); response("paused") }
            "/v2/admin/revoke" -> { fields("sessionId"); execution.revoke(DeviceV2Codec.id(data["sessionId"])); response("revoked") }
            "/v2/admin/remote" -> {
                fields("url", "deviceBearer", "trustedCertificatePem")
                val control = remote ?: return DeviceV2Codec.failure("UnsupportedCapability", "Remote connection is not assembled")
                val url = data["url"] as? String
                val endpoint = if (url == null) {
                    if (data["deviceBearer"] != null || data["trustedCertificatePem"] != null) throw ProtocolFailure(message = "Disabled remote settings must be null")
                    null
                } else RemoteEndpoint(DeviceV2Codec.text(url, 2048), DeviceV2Codec.text(data["deviceBearer"], 128),
                    data["trustedCertificatePem"]?.let { DeviceV2Codec.text(it, 8192) })
                control.configure(endpoint); response("remote_configured", mapOf("status" to control.status()))
            }
            else -> DeviceV2Codec.failure("UnsupportedCapability", "Unknown owner route", "404 Not Found")
        }
        val session = authorization.session ?: return DeviceV2Codec.failure("IdentityMismatch", "Paired session required", "401 Unauthorized")
        // Refresh admission even if the session was valid before a slow body arrived.
        if (execution.pairedSessions().none { it.context == session.context && it.credentialHash == session.credentialHash && !it.revoked && it.expiresAtMillis > now() })
            return DeviceV2Codec.failure("AuthorityRevoked", "Session expired or revoked", "401 Unauthorized")
        when (path) {
            "/v2/capabilities" -> { fields(); DeviceV2Codec.response(capabilities(requestId, session)) }
            "/v2/health" -> {
                fields(); val health = query.health()
                response("observation", mapOf("context" to context(session.context), "health" to mapOf("ok" to true,
                    "auth" to true, "paired" to true, "listener" to health.listenerConnected, "accessibility" to health.accessibilityConnected)))
            }
            "/v2/controller/acquire" -> { fields(); result(execution.acquire(session)) { mapOf("contractVersion" to DEVICE_CONTRACT,
                "kind" to "controller", "requestId" to requestId, "lease" to lease(it.ref), "expiresAt" to DeviceV2Codec.time(it.expiresAtMillis)) } }
            "/v2/controller/release" -> { fields(); when (val result = execution.release(session)) {
                is ExecutionResult.Rejected -> failure(result.error)
                is ExecutionResult.Success -> response("controller_released")
            } }
            "/v2/receipt", "/v2/cancel" -> {
                fields("actionId"); val actionId = DeviceV2Codec.id(data["actionId"])
                result(if (path == "/v2/receipt") execution.receipt(session, actionId) else execution.cancel(session, actionId)) { receipt(it) }
            }
            "/v2/events" -> {
                fields("cursor", "limit")
                val cursor = DeviceV2Codec.integer(data["cursor"], 0)
                result(execution.events(session, cursor, DeviceV2Codec.integer(data["limit"], 1, 128).toInt())) { page ->
                    mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "event_page", "requestId" to requestId,
                        "events" to page.events.map(::event), "nextCursor" to page.nextCursor,
                        "oldestAvailableCursor" to page.oldestAvailableCursor, "resyncRequired" to page.resyncRequired,
                        "error" to if (page.resyncRequired) mapOf("code" to "EventGap", "message" to "Retained events do not cover this cursor; resync receipts before acknowledging") else null)
                }
            }
            "/v2/events/ack" -> {
                fields("cursor"); when (val result = execution.acknowledge(session, DeviceV2Codec.integer(data["cursor"], 0))) {
                    is ExecutionResult.Rejected -> failure(result.error)
                    is ExecutionResult.Success -> response("acknowledged")
                }
            }
            "/v2/resync" -> { fields(); result(execution.resync(session)) { (receipts, cursor) -> mapOf("contractVersion" to DEVICE_CONTRACT,
                "kind" to "resync", "requestId" to requestId, "receipts" to receipts.map(::receipt), "cursor" to cursor,
                "notice" to "Refresh live observations before new effects; resync never replays an action") } }
            else -> DeviceV2Codec.failure("UnsupportedCapability", "Unknown v2 route", "404 Not Found")
        }
    } catch (error: ProtocolFailure) { DeviceV2Codec.failure(error.code, error.message ?: "Invalid v2 body") }
      catch (_: IllegalArgumentException) { DeviceV2Codec.failure("InvalidRequest", "Invalid connection settings") }
    }

    private fun capabilities(requestId: String, session: ControllerSession): Map<String, Any?> {
        val rejection = if (session.packageId == null) ExecutionError(ExecutionErrorCode.PermissionRequired, "No app-opening package selected during trusted setup")
            else readiness.rejection("app.open", session.packageId)
        fun capability(operation: String, supported: Boolean, authority: String, controller: Boolean, reason: String? = null) = mapOf(
            "operation" to operation, "supported" to supported, "readiness" to if (!supported) "unsupported" else if (operation == "app.open" && rejection != null) when (rejection.code) {
                ExecutionErrorCode.DeviceLocked -> "device_locked"; ExecutionErrorCode.AppMissing -> "app_missing"; else -> "permission_required"
            } else "ready", "authority" to authority, "requiresController" to controller, "requiresSnapshot" to false,
            "requiresAccount" to false, "reason" to (reason ?: if (operation == "app.open") rejection?.message else null))
        return mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "capabilities", "requestId" to requestId, "deviceId" to execution.deviceId,
            "observedAt" to DeviceV2Codec.time(now()), "supportedVersions" to listOf("usix-companion.device/v1", DEVICE_CONTRACT),
            "capabilities" to listOf(capability("device.health", true, "none", false), capability("app.open", true, "grant", true),
                capability("ui.tap", false, "approval", true, "Requires P3 snapshots and P4 action authority"),
                capability("mail.send", false, "approval", true, "Requires P4 approved workflow and goal evidence"),
                capability("notification.reply", false, "approval", false, "Use supported legacy dispatch or the later approved v2 workflow")),
            "limits" to mapOf("maxCommandBytes" to 65536, "maxObservationBytes" to 1048576, "maxMediaBytes" to 0, "maxPageItems" to 128, "eventRetentionCount" to 512), "pagination" to true)
    }
    private fun <T> result(value: ExecutionResult<T>, encode: (T) -> Map<String, Any?>): LegacyResponse = when (value) {
        is ExecutionResult.Rejected -> failure(value.error)
        is ExecutionResult.Success -> DeviceV2Codec.response(encode(value.value))
    }
    private fun failure(error: ExecutionError) = DeviceV2Codec.failure(error.code.name, error.message, when (error.code) {
        ExecutionErrorCode.IdentityMismatch, ExecutionErrorCode.AuthorityRevoked, ExecutionErrorCode.AuthorityExpired -> "403 Forbidden"
        ExecutionErrorCode.ControllerConflict, ExecutionErrorCode.ActionConflict -> "409 Conflict"
        else -> "400 Bad Request"
    })
    companion object {
        fun context(c: ExecutionContext): Map<String, Any?> = mapOf("deviceId" to c.deviceId, "runtimeId" to c.runtimeId, "sessionId" to c.sessionId,
            "taskId" to c.taskId, "taskRevision" to c.taskRevision, "workspaceId" to c.workspaceId)
        fun lease(ref: LeaseRef?) = ref?.let { mapOf("leaseId" to it.leaseId, "revision" to it.revision) }
        fun receipt(r: ExecutionReceipt): Map<String, Any?> {
            val effect = when (r.state) {
                ReceiptState.Accepted, ReceiptState.Failed, ReceiptState.Cancelled -> "none"
                ReceiptState.Executing, ReceiptState.UnknownEffect, ReceiptState.NeedsVerification -> "possible"
                ReceiptState.Dispatched -> "dispatched"; ReceiptState.Verified -> "verified"
            }
            val retry = if (effect in setOf("possible", "dispatched")) "reconcile" else if (r.state == ReceiptState.Failed) "safe" else "never"
            return mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "receipt", "requestId" to r.command.requestId,
                "receiptId" to r.receiptId, "revision" to r.revision, "context" to context(r.command.context), "actionId" to r.command.actionId,
                "payloadHash" to r.command.payloadHash, "state" to r.state.name, "effect" to effect, "updatedAt" to DeviceV2Codec.time(r.updatedAtMillis),
                "cancellationRequested" to r.cancellationRequested, "evidence" to emptyList<Any>(),
                "error" to r.error?.let { mapOf("code" to it.code.name, "message" to it.message) },
                "retry" to mapOf("decision" to retry, "reason" to if (retry == "reconcile") "Read the stored receipt and refresh observation; do not replay" else "A new attempt needs fresh admission"))
        }
        fun event(e: DeviceEvent): Map<String, Any?> = mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "event", "eventId" to e.eventId,
            "deviceId" to e.deviceId, "cursor" to e.cursor, "createdAt" to DeviceV2Codec.time(e.createdAtMillis), "type" to when (e.payload) {
                is EventPayload.ActionUpdated -> "action_updated"; is EventPayload.ControllerChanged -> "controller_changed"
            }, "payload" to when (val p = e.payload) {
                is EventPayload.ActionUpdated -> mapOf("context" to context(p.context), "actionId" to p.actionId, "receiptId" to p.receiptId, "receiptRevision" to p.receiptRevision)
                is EventPayload.ControllerChanged -> mapOf("runtimeId" to p.runtimeId, "lease" to lease(p.lease))
            })
    }
}
fun WireContext.domain() = ExecutionContext(deviceId, runtimeId, sessionId, taskId, taskRevision, workspaceId)
private fun WireDeviceCommand.domain() = ExecutionCommand(requestId, context.domain(), actionId, operation,
    ExecutionScope(scope.packageId, scope.accountRef, scope.resourceRefs, scope.snapshotRef), payloadHash, payload.isEmpty(), lease?.let { LeaseRef(it.leaseId, it.revision) },
    deadlineMillis, cancellationId, AuthorityRef(authority.kind, authority.ref))
