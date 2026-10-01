package dev.usix.companion.application

import dev.usix.companion.domain.*
import dev.usix.companion.testing.MemoryExecutionRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceExecutionTest {
    private val store = MemoryExecutionRepository()
    private var now = 1_000L
    private var sequence = 100
    private var dispatches = 0
    private var ready: ExecutionError? = null
    private var effect: suspend () -> Unit = { }
    private val crypto = object : CredentialCrypto {
        override fun newSecret() = "synthetic-pairing-secret-${sequence++}"
        override fun digest(secret: String) = "digest-$secret"
        override fun matches(secret: String, digest: String) = digest(secret) == digest
    }
    private val actions = object : ExecuteDeviceAction {
        override suspend fun execute(action: DeviceAction): DeviceResult<ActionOutcome> {
            dispatches++; effect(); return DeviceResult.Success(ActionOutcome(true, ActionKind.APP_LAUNCH))
        }
    }
    private fun coordinator(retain: Int = 512) = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id(sequence++) }, crypto, ExecutionReadiness { _, _ -> ready }, actions, retain)
    private val app = coordinator()
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun context(n: Int = 2) = ExecutionContext(store.deviceId(), id(n), id(n + 1), id(n + 2), 1, id(n + 3))
    private suspend fun pair(n: Int = 2): ControllerSession {
        val challenge = app.challenge()
        return (app.pair(challenge.challengeId, challenge.nonce, context(n), "dev.usix.companion") as ExecutionResult.Success).value.session
    }
    private suspend fun command(session: ControllerSession, n: Int = 40): ExecutionCommand {
        val lease = (app.acquire(session) as ExecutionResult.Success).value
        return ExecutionCommand(id(30), session.context, id(n), "app.open", ExecutionScope("dev.usix.companion"), "sha256:" + "a".repeat(64), true,
            lease.ref, now + 10_000, id(50), AuthorityRef("grant", session.grantId))
    }
    private fun receipt(result: ExecutionResult<ExecutionReceipt>) = (result as ExecutionResult.Success).value
    private fun code(result: ExecutionResult<*>) = (result as ExecutionResult.Rejected).error.code

    @Test fun lostResponseDuplicateReturnsStoredReceiptAndChargesOnce() = runBlocking {
        val session = pair(); val command = command(session)
        val first = receipt(app.execute(session, command))
        assertEquals(ReceiptState.Dispatched, first.state)
        assertEquals(first, receipt(app.execute(session, command.copy(requestId = id(31)))))
        assertEquals(1, dispatches); assertEquals(1, store.session(session.context.sessionId)!!.actionsUsed)
    }
    @Test fun alteredPayloadScopeOperationAndTaskCannotReuseActionId() = runBlocking {
        val s = pair(); val c = command(s); app.execute(s, c)
        listOf(c.copy(payloadHash = "sha256:" + "b".repeat(64)), c.copy(scope = ExecutionScope("another.app")),
            c.copy(operation = "mail.send")).forEach { assertEquals(ExecutionErrorCode.ActionConflict, code(app.execute(s, it))) }
        assertEquals(ExecutionErrorCode.IdentityMismatch, code(app.execute(s, c.copy(context = c.context.copy(taskRevision = 2)))))
        assertEquals(1, dispatches)
    }
    @Test fun twoSessionsStaleControllerAndLegacyCannotMutate() = runBlocking {
        val a = pair(); val old = command(a); val b = pair(10)
        assertEquals(ExecutionErrorCode.ControllerConflict, code(app.acquire(b)))
        assertEquals(DeviceErrorCode.CONTROLLER_CONFLICT, (app.executeLegacy(DeviceAction.Back) as DeviceResult.Rejected).error.code)
        app.pause(); command(b)
        assertEquals(ExecutionErrorCode.ControllerConflict, code(app.execute(a, old)))
        assertEquals(0, dispatches)
    }
    @Test fun volatileRevocationBeforeDispatchProducesNoEffect() = runBlocking {
        val s = pair(); val c = command(s)
        store.onReceipt = { if (it.state == ReceiptState.Accepted) app.revoke(s.context.sessionId) }
        val result = receipt(app.execute(s, c))
        assertEquals(ReceiptState.Failed, result.state); assertEquals(ExecutionErrorCode.AuthorityRevoked, result.error!!.code)
        assertEquals(0, dispatches)
    }
    @Test fun readinessAndDeadlineFailBeforeJournalAndEffect() = runBlocking {
        val s = pair(); val c = command(s)
        ready = ExecutionError(ExecutionErrorCode.DeviceLocked, "locked")
        assertEquals(ExecutionErrorCode.DeviceLocked, code(app.execute(s, c)))
        ready = null; now = c.deadlineMillis
        assertEquals(ExecutionErrorCode.DeadlineExceeded, code(app.execute(s, c)))
        assertNull(store.receipt(c.actionId)); assertEquals(0, dispatches)
    }
    @Test fun interruptedEffectAndRestartCannotBlindlyReplay() = runBlocking {
        val s = pair(); val c = command(s)
        effect = { throw IllegalStateException("acknowledgement lost") }
        val uncertain = receipt(app.execute(s, c))
        assertEquals(ReceiptState.UnknownEffect, uncertain.state)
        val restarted = coordinator(); restarted.recover()
        assertEquals(uncertain, receipt(restarted.execute(s, c)))
        assertEquals(1, dispatches)
    }
    @Test fun startupMarksExecutingUnknownAndAcceptedCancelledAndInvalidatesLease() = runBlocking {
        val s = pair(); val c = command(s)
        store.putReceipt(ExecutionReceipt(id(90), c, 2, ReceiptState.Executing, now))
        store.putReceipt(ExecutionReceipt(id(91), c.copy(actionId = id(41)), 1, ReceiptState.Accepted, now))
        coordinator().recover()
        assertEquals(ReceiptState.UnknownEffect, store.receipt(id(40))!!.state)
        assertEquals(ReceiptState.Cancelled, store.receipt(id(41))!!.state)
        assertNull(store.lease()); assertEquals(0, dispatches)
    }
    @Test fun cancelBeforeDispatchAndAfterDispatchReportsActualEffect() = runBlocking {
        val s = pair(); val c = command(s)
        store.onReceipt = { if (it.state == ReceiptState.Accepted) app.cancel(s, c.actionId) }
        val before = receipt(app.execute(s, c)); assertEquals(ReceiptState.Cancelled, before.state); assertEquals(0, dispatches)
        store.onReceipt = null
        val after = c.copy(actionId = id(42), cancellationId = id(51)); app.execute(s, after)
        val cancelled = receipt(app.cancel(s, after.actionId))
        assertEquals(ReceiptState.Dispatched, cancelled.state); assertTrue(cancelled.cancellationRequested); assertEquals(1, dispatches)
    }
    @Test fun cancelledCoroutinePreservesUncertainty() = runBlocking {
        val s = pair(); val c = command(s); val entered = CompletableDeferred<Unit>()
        effect = { entered.complete(Unit); awaitCancellation() }
        val job = launch { app.execute(s, c) }; entered.await(); job.cancelAndJoin()
        assertEquals(ReceiptState.UnknownEffect, store.receipt(c.actionId)!!.state); assertEquals(1, dispatches)
    }
    @Test fun receiptAndEventPrivacyIsBoundToCapturedSession() = runBlocking {
        val a = pair(); val c = command(a); app.execute(a, c); val b = pair(10)
        assertEquals(ExecutionErrorCode.IdentityMismatch, code(app.receipt(b, c.actionId)))
        val page = (app.events(b, 0, 128) as ExecutionResult.Success).value
        assertTrue(page.events.none { it.payload is EventPayload.ActionUpdated })
    }
    @Test fun boundedOutboxHasExplicitGapAndDurableMonotonicAcknowledgement() = runBlocking {
        val s = pair(); val small = coordinator(3); val c = command(s)
        small.execute(s, c)
        val gap = (small.events(s, 0, 128) as ExecutionResult.Success).value
        assertTrue(gap.resyncRequired)
        val sync = (small.resync(s) as ExecutionResult.Success).value
        assertEquals(1, sync.first.size)
        assertTrue(small.acknowledge(s, sync.second) is ExecutionResult.Success)
        assertEquals(ExecutionErrorCode.InvalidRequest, code(small.acknowledge(s, sync.second + 1)))
        assertEquals(sync.second, store.session(s.context.sessionId)!!.acknowledgedCursor)
    }
    @Test fun concurrentIdenticalActionDispatchesOnce() = runBlocking {
        val s = pair(); val c = command(s)
        val receipts = awaitAll(async { app.execute(s, c) }, async { app.execute(s, c) })
        assertEquals(receipt(receipts[0]), receipt(receipts[1])); assertEquals(1, dispatches)
    }
    @Test fun pairingChallengeIsSingleUseAndSessionsExpire() = runBlocking {
        val challenge = app.challenge()
        val paired = app.pair(challenge.challengeId, challenge.nonce, context(), null) as ExecutionResult.Success
        assertNotNull(app.authenticate(paired.value.bearer))
        assertEquals(ExecutionErrorCode.IdentityMismatch, code(app.pair(challenge.challengeId, challenge.nonce, context(10), null)))
        now = paired.value.session.expiresAtMillis
        assertNull(app.authenticate(paired.value.bearer))
    }
    @Test fun legacyUnboundUiAndReplyCannotCommitConsequentialEffects() = runBlocking {
        listOf(DeviceAction.Tap(10, 20), DeviceAction.Type("synthetic"), DeviceAction.Back, DeviceAction.Scroll("down"), DeviceAction.Reply("synthetic-handle", "synthetic reply")).forEach {
            assertEquals(DeviceErrorCode.APPROVAL_REQUIRED, (app.executeLegacy(it) as DeviceResult.Rejected).error.code)
        }
        assertEquals(0, dispatches)
    }
}
