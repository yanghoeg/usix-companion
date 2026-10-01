package dev.usix.companion.adapters.transport

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import dev.usix.companion.protocol.*
import dev.usix.companion.testing.FakeDevice
import dev.usix.companion.testing.MemoryExecutionRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceV2RouterTest {
    private val store = MemoryExecutionRepository()
    private val device = FakeDevice()
    private val now = 1790850000000L
    private val owner = CredentialVerifier { it == "synthetic-owner-bearer" }
    private val readiness = ExecutionReadiness { _, _ -> null }
    private val execution = DeviceExecution(store, ExecutionClock { now }, ExecutionIds { UUID.randomUUID().toString() }, ExecutionCrypto(), readiness, device.application())
    private val router = DeviceV2Router(execution, device.application(), owner, readiness) { now }
    private fun id() = UUID.randomUUID().toString()
    private fun packet(vararg fields: Pair<String, Any?>) = StrictJson.encode(mapOf("contractVersion" to DEVICE_CONTRACT, "requestId" to id()) + fields)
    private suspend fun pair(): PairedSession {
        val challenge = execution.challenge()
        return (execution.pair(challenge.challengeId, challenge.nonce, ExecutionContext(store.deviceId(), id(), id(), id(), 1, id()), "dev.usix.companion") as ExecutionResult.Success).value
    }
    private fun save(name: String, value: String) {
        File(System.getProperty("companion.conformance")).apply { mkdirs() }.resolve("$name.json").writeText(value)
    }
    @Test fun realRouterEmitsSchemaConformantCapabilitiesReceiptAndEvents() = runBlocking {
        val paired = pair(); val auth = router.authorize("/v2/capabilities", paired.bearer)!!
        val caps = router.route("POST", "/v2/capabilities", packet(), auth)
        save("capabilities", caps.json)
        assertEquals("capabilities", StrictJson.objectValue(caps.json)["kind"])
        val lease = (execution.acquire(paired.session) as ExecutionResult.Success).value
        val command = mutableMapOf<String, Any?>("contractVersion" to DEVICE_CONTRACT, "kind" to "command", "requestId" to id(),
            "context" to DeviceV2Router.context(paired.session.context), "actionId" to id(), "operation" to "app.open", "payload" to emptyMap<String, Any>(),
            "scope" to mapOf("packageId" to "dev.usix.companion", "accountRef" to null, "resourceRefs" to emptyList<String>(), "snapshotRef" to null),
            "controllerLease" to DeviceV2Router.lease(lease.ref), "deadline" to DeviceV2Codec.time(now + 30_000), "cancellationId" to null,
            "authority" to mapOf("kind" to "grant", "ref" to paired.session.grantId))
        command["payloadHash"] = DeviceV2Codec.payloadHash(command)
        val response = router.route("POST", "/v2/execute", StrictJson.encode(command), auth)
        save("receipt-dispatched", response.json)
        assertEquals("Dispatched", StrictJson.objectValue(response.json)["state"])
        assertEquals(response.json, router.route("POST", "/v2/execute", StrictJson.encode(command), auth).json)
        assertEquals(1, device.calls.count { it == "open" })
        store.eventsAfter(0, 128).forEachIndexed { index, event -> save("event-$index", StrictJson.encode(DeviceV2Router.event(event))) }
        assertEquals(DEVICE_CONTRACT, StrictJson.objectValue(response.json)["contractVersion"])
    }
    @Test fun ownerCannotImpersonatePairedSessionAndRevocationStopsQueries() = runBlocking {
        assertNull(router.authorize("/v2/execute", "synthetic-owner-bearer"))
        val paired = pair(); val auth = router.authorize("/v2/health", paired.bearer)!!
        execution.revoke(paired.session.context.sessionId)
        assertNull(router.authorize("/v2/health", paired.bearer))
        assertEquals("401 Unauthorized", router.route("POST", "/v2/health", packet(), auth).status)
    }
    @Test fun unknownOperationsHaveStructuredErrorsAndNoAdapterDispatch() = runBlocking {
        val paired = pair(); val auth = router.authorize("/v2/health", paired.bearer)!!
        val response = router.route("POST", "/v2/nonexistent", packet(), auth)
        assertEquals("UnsupportedCapability", DeviceV2Codec.obj(StrictJson.objectValue(response.json)["error"])["code"])
        assertTrue(device.calls.isEmpty())
    }
}
