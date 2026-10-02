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
    private val observations: DeviceObservations? = null,
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
    suspend fun pair(challengeId: String, nonce: String, context: ExecutionContext, packageId: String?, displayName: String = "External runtime",
        accountRef: String? = null, fixtureUi: Boolean = false): ExecutionResult<PairedSession> = pairing.withLock {
        val challenge = challenges.remove(challengeId)
            ?: return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Pairing challenge missing or already consumed")
        if (challenge.second <= clock.nowMillis() || !crypto.matches(nonce, challenge.first))
            return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Pairing challenge expired or invalid")
        if (context.deviceId != deviceId) return@withLock reject(ExecutionErrorCode.IdentityMismatch, "Selected device does not match")
        if (fixtureUi && packageId != CONTROLLED_UI_PACKAGE) return@withLock reject(ExecutionErrorCode.ApprovalRequired, "UI qualification grant only permits the Companion fixture")
        val bearer = crypto.newSecret()
        controller.withLock {
            repository.transaction {
                val old = repository.session(context.sessionId)
                if (old != null && (old.context != context || old.packageId != packageId || old.accountRef != accountRef || old.fixtureUi != fixtureUi))
                    return@transaction reject(ExecutionErrorCode.IdentityMismatch, "Renewal cannot replace the captured context, package, account or grant scope")
                val session = old?.copy(credentialHash = crypto.digest(bearer), expiresAtMillis = clock.nowMillis() + 1_800_000,
                    grantId = ids.next(), revoked = false)
                    ?: ControllerSession(context, crypto.digest(bearer), clock.nowMillis() + 1_800_000, ids.next(), packageId,
                        maxActions = if (fixtureUi) 64 else 16, displayName = displayName, accountRef = accountRef, fixtureUi = fixtureUi)
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
        observations?.cancelWaits(sessionId)
        repository.session(sessionId)?.let { repository.putSession(it.copy(revoked = true)) }
        if (repository.lease()?.sessionId == sessionId) { repository.putLease(null); controllerEvent(null) }
    }
    override fun pause() = repository.transaction { observations?.cancelWaits(null); repository.putLease(null); controllerEvent(null) }
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

    suspend fun execute(session: ControllerSession, command: ExecutionCommand, uiRequest: UiActionRequest? = null,
        goal: UiGoal? = null): ExecutionResult<ExecutionReceipt> {
        if (command.operation in UI_OPERATIONS && (uiRequest == null || observations == null))
            return reject(ExecutionErrorCode.UnsupportedCapability, "UI execution adapter or typed request unavailable")
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
                        val effect = if (command.operation in UI_OPERATIONS) observations!!.perform(command.context, command.scope,
                            command.operation, uiRequest!!) { admission(session, command, budgetCharged = true) }
                        else when (val result = actions.execute(DeviceAction.Open(command.scope.packageId))) {
                            is DeviceResult.Success -> UiEffect(if (result.value.accepted) UiEffectState.Dispatched else UiEffectState.Possible)
                            is DeviceResult.Rejected -> UiEffect(UiEffectState.Possible)
                        }
                        val done = repository.transaction {
                            val fresh = repository.receipt(command.actionId)!!
                            when (effect.state) {
                                UiEffectState.Dispatched -> update(fresh, ReceiptState.Dispatched)
                                UiEffectState.None -> update(fresh, if (effect.error?.code == ExecutionErrorCode.Cancelled) ReceiptState.Cancelled else ReceiptState.Failed,
                                    effect.error ?: ExecutionError(ExecutionErrorCode.InvalidRequest, "UI action was not dispatched"))
                                UiEffectState.Possible -> update(fresh, ReceiptState.UnknownEffect,
                                    effect.error ?: ExecutionError(ExecutionErrorCode.UnknownEffect, "Adapter did not establish the effect; do not replay"))
                            }
                        }
                        if (done.state == ReceiptState.Dispatched && observations != null) {
                            if (goal != null) verifyGoal(session, command.actionId, goal, command.goalHash!!)
                            else {
                                val observed = observations.observe(command.context, command.scope.copy(snapshotRef = null), null, 0, 128) {
                                    observationAdmission(session, command.scope.copy(snapshotRef = null))
                                }
                                ExecutionResult.Success(if (observed is ExecutionResult.Success) repository.transaction {
                                    update(repository.receipt(command.actionId)!!.copy(observationRef = observed.value.snapshot.ref), ReceiptState.Dispatched)
                                } else done)
                            }
                        } else ExecutionResult.Success(done)
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) { repository.transaction {
                            val fresh = repository.receipt(old.command.actionId)!!
                            if (fresh.state == ReceiptState.Executing) update(fresh, ReceiptState.UnknownEffect,
                                ExecutionError(ExecutionErrorCode.UnknownEffect, "Execution interrupted; no automatic retry"))
                        } }
                        throw error
                    } catch (_: Exception) {
                        ExecutionResult.Success(repository.transaction {
                            val fresh = repository.receipt(command.actionId)!!
                            if (fresh.state == ReceiptState.Executing) update(fresh, ReceiptState.UnknownEffect,
                                ExecutionError(ExecutionErrorCode.UnknownEffect, "Effect acknowledgement unavailable; reconcile")) else fresh
                        })
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
        if (command.cancellationId?.let { repository.cancelled(it) || repository.cancelled("${session.context.sessionId}:$it") } == true || repository.receipt(command.actionId)?.cancellationRequested == true)
            return ExecutionError(ExecutionErrorCode.Cancelled, "Action cancellation context was stopped")
        if (command.operation != "app.open" && command.operation !in UI_OPERATIONS) return ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Operation is not implemented by this APK")
        if (command.scope.packageId == null || command.scope.resourceRefs.isNotEmpty() || command.scope.accountRef != session.accountRef)
            return ExecutionError(ExecutionErrorCode.IdentityMismatch, "Action requires the captured package/account scope")
        if (command.operation == "app.open" && (!command.payloadEmpty || command.scope.snapshotRef != null))
            return ExecutionError(ExecutionErrorCode.InvalidRequest, "app.open requires an empty payload and no snapshot")
        if (command.operation in UI_OPERATIONS && (command.scope.snapshotRef == null || !session.fixtureUi || session.packageId != CONTROLLED_UI_PACKAGE))
            return ExecutionError(ExecutionErrorCode.ApprovalRequired, "UI actions require a snapshot and the explicit controlled-fixture setup grant; general app authority is not implemented")
        val lease = repository.lease()
        if (lease == null || lease.ref != command.lease || lease.sessionId != session.context.sessionId || lease.expiresAtMillis <= clock.nowMillis())
            return ExecutionError(ExecutionErrorCode.ControllerConflict, "Controller lease missing, expired or replaced")
        val current = repository.session(session.context.sessionId)!!
        if (command.authority != AuthorityRef("grant", current.grantId) || command.scope.packageId != current.packageId || (!budgetCharged && current.actionsUsed >= current.maxActions))
            return ExecutionError(ExecutionErrorCode.ApprovalRequired, "No setup grant for this package or the action budget is exhausted")
        return readiness.rejection(command.operation, command.scope.packageId)
    }

    fun observationAdmission(session: ControllerSession, scope: ExecutionScope): ExecutionError? {
        sessionError(session)?.let { return it }
        if (scope.packageId == null || scope.packageId != session.packageId || scope.accountRef != session.accountRef || scope.resourceRefs.isNotEmpty())
            return ExecutionError(ExecutionErrorCode.IdentityMismatch, "Observation cannot replace the captured package, account or resources")
        return readiness.rejection("ui.observe", scope.packageId)
    }
    suspend fun observe(session: ControllerSession, scope: ExecutionScope, selector: NodeSelector?, offset: Int, limit: Int): ExecutionResult<SnapshotPage> =
        observations?.observe(session.context, scope, selector, offset, limit) { observationAdmission(session, scope) }
            ?: reject(ExecutionErrorCode.UnsupportedCapability, "Rich observation adapter unavailable")

    suspend fun waitFor(session: ControllerSession, scope: ExecutionScope, kind: String, selector: NodeSelector?, deadline: Long, cancellationId: String): ExecutionResult<SnapshotPage> =
        observations?.wait(session.context, scope, kind, selector, deadline, cancellationId) {
            observationAdmission(session, scope) ?: if (repository.cancelled("${session.context.sessionId}:$cancellationId"))
                ExecutionError(ExecutionErrorCode.Cancelled, "Observation cancellation context was stopped") else null
        } ?: reject(ExecutionErrorCode.UnsupportedCapability, "Observation waits unavailable")

    fun cancelWait(session: ControllerSession, cancellationId: String): ExecutionResult<Unit> {
        sessionError(session)?.let { return ExecutionResult.Rejected(it) }
        repository.transaction { repository.markCancelled("${session.context.sessionId}:$cancellationId") }
        observations?.cancelWait(session.context.sessionId, cancellationId)
        return ExecutionResult.Success(Unit)
    }
    fun captureReadiness(language: String?) = observations?.captureReadiness(language)
        ?: if (observations == null) ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Capture adapter unavailable") else null
    suspend fun capture(session: ControllerSession, scope: ExecutionScope, language: String?): ExecutionResult<VisualObservation> =
        observations?.capture(session.context, scope, language) { observationAdmission(session, scope) }
            ?: reject(ExecutionErrorCode.UnsupportedCapability, "Capture adapter unavailable")

    suspend fun verifyGoal(session: ControllerSession, actionId: String, goal: UiGoal, goalHash: String): ExecutionResult<ExecutionReceipt> {
        val receipt = receipt(session, actionId)
        if (receipt !is ExecutionResult.Success) return receipt
        val old = receipt.value
        if (old.command.goalId != goal.goalId || old.command.goalHash != goalHash || old.command.operation !in UI_OPERATIONS)
            return reject(ExecutionErrorCode.ActionConflict, "Verification must match the UI-state criterion bound to the original action")
        if (old.state == ReceiptState.Verified) return receipt
        if (old.state !in setOf(ReceiptState.Dispatched, ReceiptState.NeedsVerification, ReceiptState.UnknownEffect))
            return reject(ExecutionErrorCode.InvalidRequest, "Action has no effect to reconcile")
        val verifier = observations ?: return reject(ExecutionErrorCode.UnsupportedCapability, "Verifier unavailable")
        val scope = old.command.scope.copy(snapshotRef = null)
        val verified = verifier.verify(session.context, scope, goal) { observationAdmission(session, scope) }
        return ExecutionResult.Success(repository.transaction {
            val fresh = repository.receipt(actionId)!!
            if (fresh.state == ReceiptState.Verified) fresh
            else when (verified) {
                is ExecutionResult.Rejected -> update(fresh, ReceiptState.NeedsVerification, verified.error)
                is ExecutionResult.Success -> update(fresh.copy(observationRef = verified.value.snapshot.ref,
                    evidence = verified.value.evidence?.let(::listOf) ?: emptyList()),
                    if (verified.value.evidence != null) ReceiptState.Verified else ReceiptState.NeedsVerification, null)
            }
        })
    }

    companion object {
        val UI_OPERATIONS = setOf("ui.click", "ui.select", "ui.set_text", "ui.scroll", "ui.long_press", "ui.tap", "ui.swipe", "ui.back", "ui.home")
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
