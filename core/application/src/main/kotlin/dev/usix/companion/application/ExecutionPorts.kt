package dev.usix.companion.application

import dev.usix.companion.domain.ControllerLease
import dev.usix.companion.domain.ControllerSession
import dev.usix.companion.domain.DeviceEvent
import dev.usix.companion.domain.ExecutionError
import dev.usix.companion.domain.ExecutionReceipt
import dev.usix.companion.domain.PairedRuntimeSummary

fun interface ExecutionClock { fun nowMillis(): Long }
fun interface ExecutionIds { fun next(): String }
interface CredentialCrypto {
    fun newSecret(): String
    fun digest(secret: String): String
    fun matches(secret: String, digest: String): Boolean
}
fun interface ExecutionReadiness { fun rejection(operation: String, packageId: String?): ExecutionError? }
interface LocalExecutionControl {
    fun runtimeSummaries(): List<PairedRuntimeSummary>
    fun pause()
    fun revoke(sessionId: String)
    fun selectController(sessionId: String)
}

/** One atomic boundary joins session budgets, immutable receipts and outbox updates. */
interface ExecutionRepository {
    fun <T> transaction(block: () -> T): T
    fun deviceId(): String
    fun nextCounter(name: String): Long
    fun cancelled(cancellationId: String): Boolean
    fun markCancelled(cancellationId: String)
    fun session(sessionId: String): ControllerSession?
    fun sessionByCredentialHash(hash: String): ControllerSession?
    fun putSession(session: ControllerSession)
    fun sessions(): List<ControllerSession>
    fun lease(): ControllerLease?
    fun putLease(lease: ControllerLease?)
    fun receipt(actionId: String): ExecutionReceipt?
    fun putReceipt(receipt: ExecutionReceipt)
    fun unfinishedReceipts(): List<ExecutionReceipt>
    fun receipts(sessionId: String): List<ExecutionReceipt>
    fun append(event: DeviceEvent, retain: Int)
    fun eventsAfter(cursor: Long, limit: Int): List<DeviceEvent>
    fun oldestEventCursor(): Long
    fun latestEventCursor(): Long
}
