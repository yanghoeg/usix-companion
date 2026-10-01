package dev.usix.companion.application

import dev.usix.companion.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Device execution policy, shared by loopback and WSS; transport cannot grant authority. */
class DeviceExecution(
    private val repository: ExecutionRepository,
    private val clock: ExecutionClock,
    private val ids: ExecutionIds,
    private val crypto: CredentialCrypto,
    private val readiness: ExecutionReadiness,
    private val actions: ExecuteDeviceAction,
    private val eventRetention: Int = 512,
) : LocalExecutionControl {
    private val controller = Mutex()
    private val pairing = Mutex()
    private val challenges = mutableMapOf<String, Pair<String, Long>>()
    val deviceId get() = repository.deviceId()

    /** Called once before serving on process startup. Never re-enter an external effect. */
    fun recover() = repository.transaction {
        repository.unfinishedReceipts().forEach { old ->
            update(old, if (old.state == ReceiptState.Executing) ReceiptState.UnknownEffect else ReceiptState.Cancelled,
                ExecutionError(if (old.state == ReceiptState.Executing) ExecutionErrorCode.UnknownEffect else ExecutionErrorCode.Cancelled,
                    if (old.state == ReceiptState.Executing) "Process stopped during execution; observe before another attempt" else "Process stopped before dispatch"))
        }
        if (repository.lease() != null) { repository.putLease(null); controllerEvent(null) }
    }

    suspend fun challenge(): PairingChallenge = pairing.withLock {
        val now = clock.nowMillis()
        challenges.entries.removeAll { it.value.second <= now }
        check(challenges.size < 32) { "Pairing queue is full" }
        PairingChallenge(ids.next(), crypto.newSecret(), now + 60_000).also {
            challenges[it.challengeId] = crypto.digest(it.nonce) to it.expiresAtMillis
        }
    }

    /** Invoked only after the transport authenticates the local setup owner. */
    suspend fun pair(challengeId: String, nonce: String, context: ExecutionContext, packageId: String?, displayName: String = "External runtime"): ExecutionResult<PairedSession> = pairing.withLock {
        val challenge = challenges.remove(challengeId)
            ?: return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Pairing challenge missing or already consumed")
        if (challenge.second <= clock.nowMillis() || !crypto.matches(nonce, challenge.first))
            return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Pairing challenge expired or invalid")
        if (context.deviceId != deviceId) return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Selected device does not match")
        val bearer = crypto.newSecret()
        controller.withLock {
            repository.transaction {
                val old = repository.session(context.sessionId)
                if (old != null && (old.context != context || old.packageId != packageId))
                    return@transaction reject(ExecutionErrorCode.IdentityMismatch, "Renewal cannot replace the captured context or package")
                val session = old?.copy(credentialHash = crypto.digest(bearer), expiresAtMillis = clock.nowMillis() + 1_800_000,
                    grantId = ids.next(), revoked = false)
                    ?: ControllerSession(context, crypto.digest(bearer), clock.nowMillis() + 1_800_000, ids.next(), packageId, displayName = displayName)
                repository.putSession(session)
                if (repository.lease()?.sessionId == context.sessionId) { repository.putLease(null); controllerEvent(null) }
                ExecutionResult.Success(PairedSession(session, bearer))
            }
        }
    }

    fun authenticate(bearer: String?): ControllerSession? {
        if (bearer == null || bearer.length !in 16..128) return null
        val session = repository.sessionByCredentialHash(crypto.digest(bearer)) ?: return null
        return session.takeIf { !it.revoked && it.expiresAtMillis > clock.nowMillis() }
    }

    suspend fun acquire(session: ControllerSession): ExecutionResult<ControllerLease> = controller.withLock {
        repository.transaction {
            sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
            val current = repository.lease()?.takeIf { it.expiresAtMillis > clock.nowMillis() }
            if (current != null && current.sessionId != session.context.sessionId)
                return@transaction reject(ExecutionErrorCode.ControllerConflict, "Another session controls this device")
            val lease = current?.copy(expiresAtMillis = minOf(session.expiresAtMillis, clock.nowMillis() + 60_000))
                ?: ControllerLease(LeaseRef(ids.next(), repository.nextCounter("lease")), session.context.sessionId,
                    session.context.runtimeId, minOf(session.expiresAtMillis, clock.nowMillis() + 60_000))
            repository.putLease(lease)
            controllerEvent(lease)
            ExecutionResult.Success(lease)
        }
    }

    suspend fun release(session: ControllerSession): ExecutionResult<Unit> = controller.withLock {
        repository.transaction {
            sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
            if (repository.lease()?.sessionId != session.context.sessionId)
                return@transaction reject(ExecutionErrorCode.ControllerConflict, "This session does not own the controller")
            repository.putLease(null); controllerEvent(null)
            ExecutionResult.Success(Unit)
        }
    }

    /** Local user pause/revocation takes priority over future dispatch, including queued calls. */
    override fun revoke(sessionId: String) = repository.transaction {
        repository.session(sessionId)?.let { repository.putSession(it.copy(revoked = true)) }
        if (repository.lease()?.sessionId == sessionId) { repository.putLease(null); controllerEvent(null) }
    }
    override fun pause() = repository.transaction { repository.putLease(null); controllerEvent(null) }
    override fun selectController(sessionId: String) = repository.transaction {
        val session = repository.session(sessionId) ?: return@transaction
        if (sessionError(session) != null) return@transaction
        val lease = ControllerLease(LeaseRef(ids.next(), repository.nextCounter("lease")), sessionId, session.context.runtimeId,
            minOf(clock.nowMillis() + 60_000, session.expiresAtMillis))
        repository.putLease(lease); controllerEvent(lease)
    }
    override fun runtimeSummaries(): List<PairedRuntimeSummary> = repository.transaction {
        val selected = repository.lease()?.takeIf { it.expiresAtMillis > clock.nowMillis() }?.sessionId
        repository.sessions().map { PairedRuntimeSummary(it.context.sessionId, it.displayName, it.expiresAtMillis,
            it.revoked || it.expiresAtMillis <= clock.nowMillis(), it.context.sessionId == selected) }
    }
    fun pairedSessions(): List<ControllerSession> = repository.sessions()

    suspend fun execute(session: ControllerSession, command: ExecutionCommand): ExecutionResult<ExecutionReceipt> {
        // Queueing and adapter waiting are both bounded by the caller deadline and server limit.
        val remaining = command.deadlineMillis - clock.nowMillis()
        if (remaining <= 0) return reject(ExecutionErrorCode.DeadlineExceeded, "Action deadline passed")
        return try {
            withTimeout(minOf(remaining, 30_000)) {
                controller.withLock {
                    var created = false
                    val accepted = repository.transaction {
                        sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
                        if (session.context != command.context) return@transaction reject(ExecutionErrorCode.IdentityMismatch, "Command context differs from authenticated setup")
                        val old = repository.receipt(command.actionId)
                        if (old != null) {
                            if (!sameBinding(old.command, command)) return@transaction reject(ExecutionErrorCode.ActionConflict, "Action ID is bound to different context or arguments")
                            return@transaction ExecutionResult.Success(old)
                        }
                        admission(session, command)?.let { return@transaction ExecutionResult.Rejected(it) }
                        val current = repository.session(session.context.sessionId)!!
                        repository.putSession(current.copy(actionsUsed = current.actionsUsed + 1))
                        val receipt = ExecutionReceipt(ids.next(), command, 1, ReceiptState.Accepted, clock.nowMillis())
                        repository.putReceipt(receipt); receiptEvent(receipt)
                        created = true
                        ExecutionResult.Success(receipt)
                    }
                    if (accepted !is ExecutionResult.Success || !created || accepted.value.state != ReceiptState.Accepted) return@withLock accepted
                    val old = accepted.value
                    val executing = repository.transaction {
                        val fresh = repository.receipt(command.actionId)!!
                        if (fresh.cancellationRequested || fresh.state == ReceiptState.Cancelled) return@transaction fresh
                        admission(session, command, budgetCharged = true)?.let { return@transaction update(fresh, ReceiptState.Failed, it) }
                        update(fresh, ReceiptState.Executing)
                    }
                    if (executing.state != ReceiptState.Executing) return@withLock ExecutionResult.Success(executing)
                    try {
                        val result = actions.execute(DeviceAction.Open(command.scope.packageId))
                        val done = repository.transaction {
                            val fresh = repository.receipt(command.actionId)!!
                            when (result) {
                                is DeviceResult.Success -> if (result.value.accepted) update(fresh, ReceiptState.Dispatched)
                                    else update(fresh, ReceiptState.UnknownEffect, ExecutionError(ExecutionErrorCode.UnknownEffect, "Adapter did not establish the effect; do not replay"))
                                is DeviceResult.Rejected -> update(fresh, ReceiptState.UnknownEffect,
                                    ExecutionError(ExecutionErrorCode.UnknownEffect, "Adapter rejected after execution began; reconcile before another attempt"))
                            }
                        }
                        ExecutionResult.Success(done)
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) { repository.transaction {
                            update(repository.receipt(old.command.actionId)!!, ReceiptState.UnknownEffect,
                                ExecutionError(ExecutionErrorCode.UnknownEffect, "Execution interrupted; no automatic retry"))
                        } }
                        throw error
                    } catch (_: Exception) {
                        ExecutionResult.Success(repository.transaction { update(repository.receipt(command.actionId)!!,
                            ReceiptState.UnknownEffect, ExecutionError(ExecutionErrorCode.UnknownEffect, "Effect acknowledgement unavailable; reconcile")) })
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            val old = repository.receipt(command.actionId)
            if (old != null && old.command.context == session.context) ExecutionResult.Success(old)
            else reject(ExecutionErrorCode.DeadlineExceeded, "Action timed out before dispatch")
        }
    }

    private fun admission(session: ControllerSession, command: ExecutionCommand, budgetCharged: Boolean = false): ExecutionError? {
        sessionError(session)?.let { return it }
        if (command.deadlineMillis <= clock.nowMillis()) return ExecutionError(ExecutionErrorCode.DeadlineExceeded, "Action deadline passed")
        if (command.cancellationId?.let(repository::cancelled) == true) return ExecutionError(ExecutionErrorCode.Cancelled, "Action cancellation context was stopped")
        if (command.operation != "app.open") return ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Operation is not implemented by this APK")
        if (!command.payloadEmpty || command.scope.accountRef != null || command.scope.snapshotRef != null || command.scope.resourceRefs.isNotEmpty() || command.scope.packageId == null)
            return ExecutionError(ExecutionErrorCode.InvalidRequest, "app.open requires only an explicit package scope and empty payload")
        val lease = repository.lease()
        if (lease == null || lease.ref != command.lease || lease.sessionId != session.context.sessionId || lease.expiresAtMillis <= clock.nowMillis())
            return ExecutionError(ExecutionErrorCode.ControllerConflict, "Controller lease missing, expired or replaced")
        val current = repository.session(session.context.sessionId)!!
        if (command.authority != AuthorityRef("grant", current.grantId) || command.scope.packageId != current.packageId || (!budgetCharged && current.actionsUsed >= current.maxActions))
            return ExecutionError(ExecutionErrorCode.ApprovalRequired, "No setup grant for this package or the action budget is exhausted")
        return readiness.rejection(command.operation, command.scope.packageId)
    }

    fun receipt(session: ControllerSession, actionId: String): ExecutionResult<ExecutionReceipt> = repository.transaction {
        sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
        val receipt = repository.receipt(actionId) ?: return@transaction reject(ExecutionErrorCode.ExpiredReference, "Action receipt not found")
        if (receipt.command.context != session.context) return@transaction reject(ExecutionErrorCode.IdentityMismatch, "Receipt belongs to a different setup context")
        ExecutionResult.Success(receipt)
    }

    fun cancel(session: ControllerSession, actionId: String): ExecutionResult<ExecutionReceipt> = repository.transaction {
        when (val result = receipt(session, actionId)) {
            is ExecutionResult.Rejected -> result
            is ExecutionResult.Success -> {
                val old = result.value
                old.command.cancellationId?.let(repository::markCancelled)
                if (old.state in setOf(ReceiptState.Failed, ReceiptState.Cancelled, ReceiptState.Verified) || old.cancellationRequested) result
                else ExecutionResult.Success(update(old.copy(cancellationRequested = true),
                    if (old.state == ReceiptState.Accepted) ReceiptState.Cancelled else old.state))
            }
        }
    }

    fun events(session: ControllerSession, cursor: Long, limit: Int): ExecutionResult<EventPage> = repository.transaction {
        sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
        if (cursor < 0 || limit !in 1..128 || cursor > repository.latestEventCursor()) return@transaction reject(ExecutionErrorCode.InvalidRequest, "Invalid event cursor or page limit")
        val oldest = repository.oldestEventCursor()
        if (cursor < oldest - 1) return@transaction ExecutionResult.Success(EventPage(emptyList(), cursor, oldest, true))
        val scanned = repository.eventsAfter(cursor, limit)
        val visible = scanned.filter { event -> (event.payload as? EventPayload.ActionUpdated)?.let { it.context == session.context } ?: true }
        val next = scanned.lastOrNull()?.cursor ?: cursor
        val current = repository.session(session.context.sessionId)!!
        repository.putSession(current.copy(deliveredCursor = maxOf(current.deliveredCursor, next)))
        ExecutionResult.Success(EventPage(visible, next, oldest, false))
    }

    fun acknowledge(session: ControllerSession, cursor: Long): ExecutionResult<Unit> = repository.transaction {
        sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
        val current = repository.session(session.context.sessionId)!!
        if (cursor < current.acknowledgedCursor || cursor > current.deliveredCursor) return@transaction reject(ExecutionErrorCode.InvalidRequest, "Acknowledgement is outside the delivered cursor range")
        repository.putSession(current.copy(acknowledgedCursor = cursor)); ExecutionResult.Success(Unit)
    }

    fun resync(session: ControllerSession): ExecutionResult<Pair<List<ExecutionReceipt>, Long>> = repository.transaction {
        sessionError(session)?.let { return@transaction ExecutionResult.Rejected(it) }
        val cursor = repository.latestEventCursor()
        val current = repository.session(session.context.sessionId)!!
        repository.putSession(current.copy(deliveredCursor = cursor))
        ExecutionResult.Success(repository.receipts(session.context.sessionId) to cursor)
    }

    /** Legacy has no scoped identity: reject effects while a v2 controller owns the phone. */
    suspend fun executeLegacy(action: DeviceAction): DeviceResult<ActionOutcome> = controller.withLock {
        if (repository.lease()?.let { it.expiresAtMillis > clock.nowMillis() } == true)
            DeviceResult.Rejected(DeviceError(DeviceErrorCode.CONTROLLER_CONFLICT, "v2 controller is active; release it before a legacy mutation"))
        else if (action is DeviceAction.Tap || action is DeviceAction.Type || action == DeviceAction.Back || action is DeviceAction.Scroll || action is DeviceAction.Reply)
            DeviceResult.Rejected(DeviceError(DeviceErrorCode.APPROVAL_REQUIRED,
                "ApprovalRequired: legacy UI/reply has no argument-bound Companion authority; use the later approved v2 workflow"))
        else actions.execute(action)
    }

    private fun sessionError(session: ControllerSession): ExecutionError? {
        val current = repository.session(session.context.sessionId)
        return when {
            current == null || current.context != session.context || current.credentialHash != session.credentialHash -> ExecutionError(ExecutionErrorCode.IdentityMismatch, "Session no longer matches")
            current.revoked -> ExecutionError(ExecutionErrorCode.AuthorityRevoked, "Session revoked by the local user")
            current.expiresAtMillis <= clock.nowMillis() -> ExecutionError(ExecutionErrorCode.AuthorityExpired, "Session expired; renew through trusted setup")
            else -> null
        }
    }
    private fun sameBinding(old: ExecutionCommand, new: ExecutionCommand) = old.context == new.context && old.operation == new.operation && old.scope == new.scope && old.payloadHash == new.payloadHash
    private fun update(old: ExecutionReceipt, state: ReceiptState, error: ExecutionError? = old.error): ExecutionReceipt = old.copy(
        revision = old.revision + 1, state = state, updatedAtMillis = maxOf(clock.nowMillis(), old.updatedAtMillis), error = error,
    ).also { repository.putReceipt(it); receiptEvent(it) }
    private fun receiptEvent(receipt: ExecutionReceipt) = append(EventPayload.ActionUpdated(receipt.command.context,
        receipt.command.actionId, receipt.receiptId, receipt.revision))
    private fun controllerEvent(lease: ControllerLease?) = append(EventPayload.ControllerChanged(lease?.runtimeId, lease?.ref))
    private fun append(payload: EventPayload) = repository.append(DeviceEvent(ids.next(), deviceId,
        repository.nextCounter("event"), clock.nowMillis(), payload), eventRetention)
    private fun reject(code: ExecutionErrorCode, message: String) = ExecutionResult.Rejected(ExecutionError(code, message))
}
