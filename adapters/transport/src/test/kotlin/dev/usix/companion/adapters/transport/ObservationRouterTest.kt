package dev.usix.companion.adapters.transport

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.protocol.*
import dev.usix.companion.testing.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObservationRouterTest {
    private val store = MemoryExecutionRepository()
    private val port = FakeRichObservation()
    private val now = 1790850000000L
    private val observations = DeviceObservations(port, ExecutionClock { now }, ExecutionIds { id() })
    private val readiness = ExecutionReadiness { _, _ -> null }
    private val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, ExecutionCrypto(), readiness, FakeDevice().application(), observations = observations)
    private val router = DeviceV2Router(execution, FakeDevice().application(), CredentialVerifier { false }, readiness) { now }
    private fun id() = UUID.randomUUID().toString()
    private fun packet(vararg fields: Pair<String, Any?>) = StrictJson.encode(mapOf("contractVersion" to DEVICE_CONTRACT, "requestId" to id()) + fields)
    private suspend fun pair(): PairedSession {
        val challenge = execution.challenge()
        return (execution.pair(challenge.challengeId, challenge.nonce, ExecutionContext(store.deviceId(), id(), id(), id(), 1, id()), CONTROLLED_UI_PACKAGE,
            accountRef = id(), fixtureUi = true) as ExecutionResult.Success).value
    }
    @Test fun snapshotAndVerifiedReceiptAreRealRouterSchemaOutputs() = runBlocking {
        val paired = pair(); val auth = router.authorize("/v2/observe", paired.bearer)!!
        val scope = mapOf("packageId" to CONTROLLED_UI_PACKAGE, "accountRef" to paired.session.accountRef, "resourceRefs" to emptyList<String>(), "snapshotRef" to null)
        val observed = router.route("POST", "/v2/observe", packet("scope" to scope, "selector" to null, "offset" to 0, "limit" to 128), auth)
        val snapshot = StrictJson.objectValue(observed.json)
        assertEquals("snapshot", snapshot["kind"]); assertTrue(observed.json.contains("[redacted]"))
        val folder = File(System.getProperty("companion.conformance")).apply { mkdirs() }
        folder.resolve("snapshot.json").writeText(observed.json)
        val lease = (execution.acquire(paired.session) as ExecutionResult.Success).value
        val goal = mapOf("goalId" to id(), "kind" to "node_text", "selector" to mapOf("resourceId" to "input"), "expectedText" to "한글 English",
            "accountSelector" to mapOf("text" to "eval-account"))
        val command = mutableMapOf<String, Any?>("contractVersion" to DEVICE_CONTRACT, "kind" to "command", "requestId" to id(),
            "context" to DeviceV2Router.context(paired.session.context), "actionId" to id(), "operation" to "ui.set_text",
            "scope" to scope + ("snapshotRef" to snapshot["snapshotRef"]), "payload" to mapOf("target" to mapOf("resourceId" to "input"), "text" to "한글 English", "goal" to goal),
            "controllerLease" to DeviceV2Router.lease(lease.ref), "deadline" to DeviceV2Codec.time(now + 30000), "cancellationId" to null,
            "authority" to mapOf("kind" to "grant", "ref" to paired.session.grantId))
        command["payloadHash"] = DeviceV2Codec.payloadHash(command)
        val receipt = router.route("POST", "/v2/execute", StrictJson.encode(command), auth)
        folder.resolve("receipt-ui-verified.json").writeText(receipt.json)
        assertEquals("Verified", StrictJson.objectValue(receipt.json)["state"]); assertEquals(1, port.effects)
        command["payload"] = mapOf("target" to mapOf("resourceId" to "input"), "text" to null)
        command["actionId"] = id(); command["payloadHash"] = DeviceV2Codec.payloadHash(command)
        val invalid = router.route("POST", "/v2/execute", StrictJson.encode(command), auth)
        assertEquals("InvalidRequest", DeviceV2Codec.obj(StrictJson.objectValue(invalid.json)["error"])["code"]); assertEquals(1, port.effects)
    }
    @Test fun observationCannotSwitchAccountAndWaitCancellationHasNoEffect() = runBlocking {
        val paired = pair(); val auth = router.authorize("/v2/observe", paired.bearer)!!
        val invalid = router.route("POST", "/v2/observe", packet("scope" to mapOf("packageId" to CONTROLLED_UI_PACKAGE, "accountRef" to id(),
            "resourceRefs" to emptyList<String>(), "snapshotRef" to null), "selector" to null, "offset" to 0, "limit" to 128), auth)
        assertEquals("IdentityMismatch", DeviceV2Codec.obj(StrictJson.objectValue(invalid.json)["error"])["code"]); assertEquals(0, port.observations)
        val cancel = router.route("POST", "/v2/wait/cancel", packet("cancellationId" to id()), auth)
        assertEquals("none", StrictJson.objectValue(cancel.json)["effect"]); assertEquals(0, port.effects)
    }
    @Test fun boundedVisualIsAnActualRouterSchemaOutput() = runBlocking {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkN0AAAAASUVORK5CYII=")
        val visualPort = object : RichObservationPort by port {
            override fun captureReadiness(language: String?): ExecutionError? = null
            override suspend fun capture(expected: ObservedScreen, language: String?) = ExecutionResult.Success(CapturedVisual(png, 1, 1,
                "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }, language, emptyList(), true))
        }
        val model = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { id() }, ExecutionCrypto(), readiness, FakeDevice().application(),
            observations = DeviceObservations(visualPort, ExecutionClock { now }, ExecutionIds { id() }))
        val router = DeviceV2Router(model, FakeDevice().application(), CredentialVerifier { false }, readiness) { now }
        val challenge = model.challenge()
        val paired = (model.pair(challenge.challengeId, challenge.nonce, ExecutionContext(store.deviceId(), id(), id(), id(), 1, id()), CONTROLLED_UI_PACKAGE) as ExecutionResult.Success).value
        val auth = router.authorize("/v2/observe", paired.bearer)!!
        val scope = mapOf("packageId" to CONTROLLED_UI_PACKAGE, "accountRef" to null, "resourceRefs" to emptyList<String>(), "snapshotRef" to null)
        val snapshot = StrictJson.objectValue(router.route("POST", "/v2/observe", packet("scope" to scope, "selector" to null, "offset" to 0, "limit" to 128), auth).json)
        val response = router.route("POST", "/v2/capture", packet("scope" to (scope + ("snapshotRef" to snapshot["snapshotRef"])), "language" to "korean"), auth)
        assertEquals("visual", StrictJson.objectValue(response.json)["kind"])
        File(System.getProperty("companion.conformance")).resolve("visual.json").writeText(response.json)
    }
}
