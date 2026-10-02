package dev.usix.companion.application

import dev.usix.companion.domain.*
import dev.usix.companion.testing.*
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceObservationsTest {
    private fun id() = UUID.randomUUID().toString()
    private val context = ExecutionContext(id(), id(), id(), id(), 1, id())
    private val scope = ExecutionScope(CONTROLLED_UI_PACKAGE)
    private val port = FakeRichObservation()
    private var now = 1000L
    private val observations = DeviceObservations(port, ExecutionClock { now }, ExecutionIds { id() })
    private suspend fun snapshot() = (observations.observe(context, scope, null, 0, 128) { null } as ExecutionResult.Success).value.snapshot
    private fun code(result: ExecutionResult<*>) = (result as ExecutionResult.Rejected).error.code

    @Test fun paginationRetainsAllNodesAndRejectsCrossContextAndExpiry() = runTest {
        port.change { it.copy(nodes = (0 until 200).map { n -> FakeRichObservation.node("n/$n", "row_$n", "row $n") }) }
        val first = (observations.observe(context, scope, null, 0, 80) { null } as ExecutionResult.Success).value
        assertEquals(200, first.totalMatching); assertEquals(80, first.nextOffset)
        val second = (observations.observe(context, scope.copy(snapshotRef = first.snapshot.ref), null, 80, 128) { null } as ExecutionResult.Success).value
        assertEquals(120, second.nodes.size); assertNull(second.nextOffset); assertEquals(first.snapshot.ref, second.snapshot.ref)
        assertEquals(ExecutionErrorCode.IdentityMismatch, code(observations.observe(context.copy(workspaceId = id()), scope.copy(snapshotRef = first.snapshot.ref), null, 0, 128) { null }))
        now = first.snapshot.expiresAtMillis
        assertEquals(ExecutionErrorCode.StaleSnapshot, code(observations.observe(context, scope.copy(snapshotRef = first.snapshot.ref), null, 0, 128) { null }))
    }
    @Test fun identicalLabelsAndOutOfWindowCoordinatesNeverDispatch() = runTest {
        val snapshot = snapshot(); val scoped = scope.copy(snapshotRef = snapshot.ref)
        val ambiguous = observations.perform(context, scoped, "ui.click", UiActionRequest(selector = NodeSelector(text = "Duplicate"))) { null }
        assertEquals(ExecutionErrorCode.AmbiguousTarget, ambiguous.error!!.code); assertEquals(UiEffectState.None, ambiguous.state)
        val outside = observations.perform(context, scoped, "ui.tap", UiActionRequest(x = 600, y = 40)) { null }
        assertEquals(ExecutionErrorCode.InvalidRequest, outside.error!!.code); assertEquals(0, port.effects)
    }
    @Test fun focusKeyboardRotationAndGenerationChangesInvalidateActions() = runTest {
        for (change in listOf<(ObservedScreen) -> ObservedScreen>(
            { it.copy(focusRef = "n/1") }, { it.copy(inputWindowVisible = true) }, { it.copy(rotation = 1) }, { it })) {
            val old = snapshot(); port.change(change)
            val result = observations.perform(context, scope.copy(snapshotRef = old.ref), "ui.click", UiActionRequest(selector = NodeSelector(resourceId = "duplicate_a"))) { null }
            assertEquals(ExecutionErrorCode.StaleSnapshot, result.error!!.code)
        }
        assertEquals(0, port.effects)
    }
    @Test fun eventWaitHasNoPollingAndCancellationCannotAffectAnotherSession() = runTest {
        val cancellation = id()
        val waiting = async { observations.wait(context, scope, "text", NodeSelector(text = "Ready"), now + 30000, cancellation) { null } }
        runCurrent(); assertEquals(1, port.observations)
        observations.cancelWait(id(), cancellation); runCurrent(); assertFalse(waiting.isCompleted)
        port.change { it.copy(nodes = it.nodes + FakeRichObservation.node("n/5", "ready", "Ready")) }
        runCurrent(); assertTrue(waiting.await() is ExecutionResult.Success); assertEquals(2, port.observations)
        val stopped = async { observations.wait(context, scope, "text", NodeSelector(text = "Absent"), now + 30000, cancellation) { null } }
        runCurrent(); observations.cancelWait(context.sessionId, cancellation); runCurrent()
        assertEquals(ExecutionErrorCode.Cancelled, code(stopped.await()))
    }
    @Test fun waitDeadlineAndProtectedUnavailableCaptureAreExplicit() = runTest {
        val result = async { observations.wait(context, scope, "node", NodeSelector(text = "Absent"), now + 50, id()) { null } }
        runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(ExecutionErrorCode.DeadlineExceeded, code(result.await())); assertEquals(1, port.observations)
        assertEquals(ExecutionErrorCode.UnsupportedCapability, code(observations.capture(context, scope, "korean") { null }))
    }
    @Test fun verificationNeedsUniqueUiStateAndAccountEvidence() = runTest {
        val goal = UiGoal(id(), "node_text", NodeSelector(resourceId = "input"), "Initial")
        val accountScope = scope.copy(accountRef = id())
        val unverified = (observations.verify(context, accountScope, goal) { null } as ExecutionResult.Success).value
        assertNull(unverified.evidence)
        val verified = (observations.verify(context, accountScope, goal.copy(accountSelector = NodeSelector(text = "eval-account"))) { null } as ExecutionResult.Success).value
        val evidence = verified.evidence!!
        assertEquals("ui_state", evidence.purpose); assertEquals(goal.goalId, evidence.goalId)
        assertEquals(ExecutionErrorCode.AmbiguousTarget, code(observations.verify(context, scope, UiGoal(id(), "node_present", NodeSelector(text = "Duplicate"))) { null }))
    }
    @Test fun fixtureGrantReceiptsDeduplicateAndBindVerificationCriterion() = runTest {
        val store = MemoryExecutionRepository()
        val crypto = object : CredentialCrypto {
            override fun newSecret() = id()
            override fun digest(secret: String) = secret
            override fun matches(secret: String, digest: String) = secret == digest
        }
        val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, crypto,
            ExecutionReadiness { _, _ -> null }, FakeDevice().application(), observations = observations)
        val challenge = execution.challenge()
        val paired = (execution.pair(challenge.challengeId, challenge.nonce, context.copy(deviceId = store.deviceId()), CONTROLLED_UI_PACKAGE,
            fixtureUi = true) as ExecutionResult.Success).value
        val lease = (execution.acquire(paired.session) as ExecutionResult.Success).value
        val observed = (execution.observe(paired.session, scope, null, 0, 128) as ExecutionResult.Success).value.snapshot
        val goal = UiGoal(id(), "node_text", NodeSelector(resourceId = "input"), "한글 English")
        val command = ExecutionCommand(id(), paired.session.context, id(), "ui.set_text", scope.copy(snapshotRef = observed.ref), "sha256:" + "a".repeat(64),
            false, lease.ref, now + 30000, null, AuthorityRef("grant", paired.session.grantId), goal.goalId, "bound-goal")
        val request = UiActionRequest(NodeSelector(resourceId = "input"), "한글 English")
        val receipt = (execution.execute(paired.session, command, request, goal) as ExecutionResult.Success).value
        assertEquals(ReceiptState.Verified, receipt.state); assertEquals(1, receipt.evidence.size); assertNotNull(receipt.observationRef)
        assertEquals(receipt, (execution.execute(paired.session, command, request, goal) as ExecutionResult.Success).value); assertEquals(1, port.effects)
        assertEquals(ExecutionErrorCode.ActionConflict, code(execution.verifyGoal(paired.session, command.actionId, goal.copy(expectedText = "other"), "changed-goal")))
        val cancelled = id(); execution.cancelWait(paired.session, cancelled)
        assertEquals(ExecutionErrorCode.Cancelled, code(execution.execute(paired.session, command.copy(actionId = id(), cancellationId = cancelled), request, goal)))
        assertEquals(1, port.effects)
    }
    @Test fun truncatedTextCannotAuthorizeAnActionOrVerifyTheFullValue() = runTest {
        port.change { it.copy(nodes = it.nodes.map { node -> if (node.resourceId == "input") node.copy(textTruncated = true) else node }) }
        val snapshot = snapshot()
        val effect = observations.perform(context, scope.copy(snapshotRef = snapshot.ref), "ui.set_text", UiActionRequest(NodeSelector(resourceId = "input"), "replacement")) { null }
        assertEquals(UiEffectState.None, effect.state); assertEquals(0, port.effects)
        val goal = UiGoal(id(), "node_text", NodeSelector(resourceId = "input"), "Initial")
        assertNull((observations.verify(context, scope, goal) { null } as ExecutionResult.Success).value.evidence)
        val query = observations.observe(context, scope, NodeSelector(text = "Initial"), 0, 128) { null } as ExecutionResult.Success
        assertEquals(0, query.value.totalMatching)
        assertEquals(ExecutionErrorCode.DeadlineExceeded, code(observations.wait(context, scope, "text", NodeSelector(text = "Initial"), now + 20, id()) { null }))
    }
    @Test fun generalPackageCannotObtainFixtureExecutionAuthority() = runTest {
        val store = MemoryExecutionRepository()
        val crypto = object : CredentialCrypto { override fun newSecret() = id(); override fun digest(secret: String) = secret; override fun matches(secret: String, digest: String) = secret == digest }
        val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, crypto, ExecutionReadiness { _, _ -> null }, FakeDevice().application(), observations = observations)
        val challenge = execution.challenge()
        assertEquals(ExecutionErrorCode.ApprovalRequired, code(execution.pair(challenge.challengeId, challenge.nonce, context.copy(deviceId = store.deviceId()), "dev.other.app", fixtureUi = true)))
        assertEquals(0, port.effects)
    }
}
