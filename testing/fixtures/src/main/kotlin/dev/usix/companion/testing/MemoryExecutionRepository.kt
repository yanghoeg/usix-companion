package dev.usix.companion.testing

import dev.usix.companion.application.ExecutionRepository
import dev.usix.companion.domain.*

class MemoryExecutionRepository(private val id: String = "00000000-0000-4000-8000-000000000001") : ExecutionRepository {
    val sessionRecords = linkedMapOf<String, ControllerSession>()
    val actionRecords = linkedMapOf<String, ExecutionReceipt>()
    val eventRecords = mutableListOf<DeviceEvent>()
    val counters = mutableMapOf<String, Long>()
    var controllerLease: ControllerLease? = null
    var onReceipt: ((ExecutionReceipt) -> Unit)? = null
    override fun <T> transaction(block: () -> T): T = synchronized(this) { block() }
    override fun deviceId() = id
    override fun nextCounter(name: String): Long = ((counters[name] ?: 0) + 1).also { counters[name] = it }
    override fun cancelled(cancellationId: String) = counters["cancelled:$cancellationId"] != null
    override fun markCancelled(cancellationId: String) { counters["cancelled:$cancellationId"] = 1 }
    override fun session(sessionId: String) = sessionRecords[sessionId]
    override fun sessionByCredentialHash(hash: String) = sessionRecords.values.firstOrNull { it.credentialHash == hash }
    override fun putSession(session: ControllerSession) { sessionRecords[session.context.sessionId] = session }
    override fun sessions() = sessionRecords.values.toList()
    override fun lease() = controllerLease
    override fun putLease(lease: ControllerLease?) { controllerLease = lease }
    override fun receipt(actionId: String) = actionRecords[actionId]
    override fun putReceipt(receipt: ExecutionReceipt) { actionRecords[receipt.command.actionId] = receipt; onReceipt?.invoke(receipt) }
    override fun unfinishedReceipts() = actionRecords.values.filter { it.state in setOf(ReceiptState.Accepted, ReceiptState.Executing) }
    override fun receipts(sessionId: String) = actionRecords.values.filter { it.command.context.sessionId == sessionId }
    override fun append(event: DeviceEvent, retain: Int) { eventRecords.add(event); while (eventRecords.size > retain) eventRecords.removeAt(0) }
    override fun eventsAfter(cursor: Long, limit: Int) = eventRecords.filter { it.cursor > cursor }.take(limit)
    override fun oldestEventCursor() = eventRecords.firstOrNull()?.cursor ?: (latestEventCursor() + 1)
    override fun latestEventCursor() = counters["event"] ?: 0
}
