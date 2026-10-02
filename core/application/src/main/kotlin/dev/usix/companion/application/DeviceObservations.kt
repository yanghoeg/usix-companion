package dev.usix.companion.application

import dev.usix.companion.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short-lived, context-bound observations. Raw screens/media never enter the action journal. */
class DeviceObservations(
    private val port: RichObservationPort, private val clock: ExecutionClock, private val ids: ExecutionIds,
) {
    private val cacheLock = Mutex()
    private val snapshots = linkedMapOf<String, ScreenSnapshot>()
    private val waits = mutableMapOf<Pair<String, String>, Deferred<ExecutionResult<SnapshotPage>>>()
    fun captureReadiness(language: String?) = port.captureReadiness(language)

    suspend fun observe(context: ExecutionContext, scope: ExecutionScope, selector: NodeSelector?, offset: Int,
        limit: Int, guard: () -> ExecutionError?): ExecutionResult<SnapshotPage> {
        guard()?.let { return ExecutionResult.Rejected(it) }
        val result = if (scope.snapshotRef == null) fresh(context, scope, guard) else current(context, scope, guard)
        return when (result) {
            is ExecutionResult.Rejected -> result
            is ExecutionResult.Success -> page(result.value, selector, offset, limit)
        }
    }

    suspend fun perform(context: ExecutionContext, scope: ExecutionScope, operation: String, request: UiActionRequest,
        guard: () -> ExecutionError?): UiEffect {
        val result = current(context, scope, guard)
        if (result is ExecutionResult.Rejected) return UiEffect(UiEffectState.None, result.error)
        val screen = (result as ExecutionResult.Success).value.screen
        if (!screen.complete) return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Incomplete tree cannot establish an unambiguous action target"))
        var targeted = request
        val selector = request.selector
        if (selector != null) {
            val matches = screen.nodes.filter { selector.matches(it, screen.windowId) && it.visible }
            if (matches.size > 1) return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.AmbiguousTarget, "Selector matches more than one visible node"))
            val node = matches.singleOrNull() ?: return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.ExpiredReference, "Target is absent from this screen"))
            if (!node.enabled || node.sensitive || node.textTruncated || node.bounds.right <= node.bounds.left || node.bounds.bottom <= node.bounds.top ||
                (operation == "ui.set_text" && !node.editable) || (operation == "ui.scroll" && !node.scrollable))
                return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.PermissionRequired, "Target is disabled, sensitive or does not support the requested action"))
            targeted = request.copy(selector = NodeSelector(nodeRef = node.ref))
        }
        val x = request.x; val y = request.y; val endX = request.endX; val endY = request.endY
        if (x != null && (!screen.windowBounds.contains(x, y!!) || !screen.displayBounds.contains(x, y)))
            return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.InvalidRequest, "Coordinates are outside the selected window"))
        if (endX != null && !screen.windowBounds.contains(endX, endY!!))
            return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.InvalidRequest, "Swipe ends outside the selected window"))
        guard()?.let { return UiEffect(UiEffectState.None, it) }
        return port.perform(screen, operation, targeted) { guard() }
    }

    suspend fun wait(context: ExecutionContext, scope: ExecutionScope, kind: String, selector: NodeSelector?,
        deadlineMillis: Long, cancellationId: String, guard: () -> ExecutionError?): ExecutionResult<SnapshotPage> = coroutineScope {
        val key = context.sessionId to cancellationId
        val task = async(start = CoroutineStart.LAZY) {
            try {
                val remaining = deadlineMillis - clock.nowMillis()
                if (remaining <= 0) return@async reject(ExecutionErrorCode.DeadlineExceeded, "Observation deadline passed")
                val baseline = if (kind == "changed") cached(context, scope) else null
                if (baseline is ExecutionResult.Rejected) return@async baseline
                var matched: ScreenSnapshot? = null
                withTimeout(minOf(remaining, 30_000)) {
                    port.changes.first {
                        guard()?.let { throw ObservationStopped(it) }
                        when (val observed = fresh(context, scope.copy(snapshotRef = null), guard)) {
                            is ExecutionResult.Rejected -> {
                                if (kind != "window" || observed.error.code != ExecutionErrorCode.ExpiredReference) throw ObservationStopped(observed.error)
                                false
                            }
                            is ExecutionResult.Success -> {
                                val snapshot = observed.value
                                val nodes = snapshot.screen.nodes.filter { it.visible && !it.sensitive && (selector?.matches(it, snapshot.screen.windowId) ?: true) }
                                val ready = when (kind) {
                                    "window" -> true
                                    "changed" -> snapshot.screen != (baseline as ExecutionResult.Success).value.screen
                                    else -> {
                                        if (nodes.size > 1) throw ObservationStopped(ExecutionError(ExecutionErrorCode.AmbiguousTarget, "Wait selector matches multiple nodes"))
                                        nodes.size == 1
                                    }
                                }
                                if (ready) matched = snapshot
                                ready
                            }
                        }
                    }
                }
                page(matched!!, selector, 0, 128)
            } catch (_: TimeoutCancellationException) { reject(ExecutionErrorCode.DeadlineExceeded, "No matching observation before the deadline") }
              catch (stopped: ObservationStopped) { ExecutionResult.Rejected(stopped.error) }
        }
        val registered = synchronized(waits) { if (key in waits) false else { waits[key] = task; true } }
        if (!registered) { task.cancel(); return@coroutineScope reject(ExecutionErrorCode.InvalidRequest, "Cancellation ID already has an active wait") }
        try { task.await() }
        catch (_: CancellationException) { reject(ExecutionErrorCode.Cancelled, "Observation wait cancelled; no device effect") }
        finally { synchronized(waits) { waits.remove(key) } }
    }

    fun cancelWait(sessionId: String, cancellationId: String) { synchronized(waits) { waits[sessionId to cancellationId]?.cancel() } }
    fun cancelWaits(sessionId: String?) { synchronized(waits) { waits.filterKeys { sessionId == null || it.first == sessionId }.values.forEach { it.cancel() } } }

    suspend fun capture(context: ExecutionContext, scope: ExecutionScope, language: String?, guard: () -> ExecutionError?): ExecutionResult<VisualObservation> {
        port.captureReadiness(language)?.let { return ExecutionResult.Rejected(it) }
        return when (val snapshot = current(context, scope, guard)) {
            is ExecutionResult.Rejected -> snapshot
            is ExecutionResult.Success -> when (val captured = port.capture(snapshot.value.screen, language)) {
                is ExecutionResult.Rejected -> captured
                is ExecutionResult.Success -> {
                    // Capture/OCR can suspend; discard private media if authority or screen changed.
                    when (val revalidated = current(context, scope, guard)) {
                        is ExecutionResult.Rejected -> revalidated
                        is ExecutionResult.Success -> ExecutionResult.Success(VisualObservation(ids.next(), snapshot.value, captured.value))
                    }
                }
            }
        }
    }

    suspend fun verify(context: ExecutionContext, scope: ExecutionScope, goal: UiGoal, guard: () -> ExecutionError?): ExecutionResult<UiVerification> =
        when (val result = fresh(context, scope.copy(snapshotRef = null), guard)) {
            is ExecutionResult.Rejected -> result
            is ExecutionResult.Success -> {
                val snapshot = result.value
                fun matches(selector: NodeSelector?) = snapshot.screen.nodes.filter { it.visible && !it.sensitive && !it.textTruncated && (selector?.matches(it, snapshot.screen.windowId) ?: false) }
                val account = matches(goal.accountSelector)
                val nodes = matches(goal.selector)
                if (nodes.size > 1 || account.size > 1) reject(ExecutionErrorCode.AmbiguousTarget, "Verification selector is ambiguous")
                else {
                    val accountMatches = scope.accountRef == null || account.size == 1
                    val goalMatches = snapshot.screen.complete && accountMatches && when (goal.kind) {
                        "package_visible" -> true
                        "node_present" -> nodes.size == 1
                        "node_text" -> nodes.singleOrNull()?.text == goal.expectedText
                        else -> false
                    }
                    ExecutionResult.Success(UiVerification(snapshot, if (goalMatches)
                        VerificationEvidence(ids.next(), goal.goalId, clock.nowMillis(), snapshot.ref) else null))
                }
            }
        }

    private suspend fun fresh(context: ExecutionContext, scope: ExecutionScope, guard: () -> ExecutionError?): ExecutionResult<ScreenSnapshot> {
        guard()?.let { return ExecutionResult.Rejected(it) }
        return when (val result = port.observe(scope.packageId!!)) {
            is ExecutionResult.Rejected -> result
            is ExecutionResult.Success -> {
                guard()?.let { return ExecutionResult.Rejected(it) }
                if (result.value.packageId != scope.packageId) return reject(ExecutionErrorCode.IdentityMismatch, "Adapter returned a different package")
                val now = clock.nowMillis()
                val snapshot = ScreenSnapshot(ids.next(), context, scope.copy(snapshotRef = null), now, now + 30_000, result.value)
                cacheLock.withLock {
                    snapshots.entries.removeAll { it.value.expiresAtMillis <= now }
                    while (snapshots.size >= 32) snapshots.remove(snapshots.keys.first())
                    snapshots[snapshot.ref] = snapshot
                }
                ExecutionResult.Success(snapshot)
            }
        }
    }
    private suspend fun cached(context: ExecutionContext, scope: ExecutionScope): ExecutionResult<ScreenSnapshot> = cacheLock.withLock {
        val value = snapshots[scope.snapshotRef]
        when {
            value == null || value.expiresAtMillis <= clock.nowMillis() -> reject(ExecutionErrorCode.StaleSnapshot, "Snapshot expired or is no longer available; observe again")
            value.context != context || value.scope != scope.copy(snapshotRef = null) -> reject(ExecutionErrorCode.IdentityMismatch, "Snapshot belongs to a different package, account or captured context")
            else -> ExecutionResult.Success(value)
        }
    }
    private suspend fun current(context: ExecutionContext, scope: ExecutionScope, guard: () -> ExecutionError?): ExecutionResult<ScreenSnapshot> {
        guard()?.let { return ExecutionResult.Rejected(it) }
        val old = cached(context, scope)
        if (old !is ExecutionResult.Success) return old
        val live = port.observe(scope.packageId!!)
        guard()?.let { return ExecutionResult.Rejected(it) }
        return if (live is ExecutionResult.Success && live.value == old.value.screen) old
            else reject(ExecutionErrorCode.StaleSnapshot, "Window, focus, keyboard, rotation or node state changed; observe again")
    }
    private fun page(snapshot: ScreenSnapshot, selector: NodeSelector?, offset: Int, limit: Int): ExecutionResult<SnapshotPage> {
        if (offset < 0 || limit !in 1..128) return reject(ExecutionErrorCode.InvalidRequest, "Invalid observation page")
        val all = snapshot.screen.nodes.filter { selector?.matches(it, snapshot.screen.windowId) ?: true }
        if (offset > all.size) return reject(ExecutionErrorCode.InvalidRequest, "Offset exceeds this snapshot query")
        val nodes = all.drop(offset).take(limit)
        return ExecutionResult.Success(SnapshotPage(snapshot, nodes, offset, all.size, (offset + nodes.size).takeIf { it < all.size }))
    }
    private class ObservationStopped(val error: ExecutionError) : Exception()
    private fun reject(code: ExecutionErrorCode, message: String) = ExecutionResult.Rejected(ExecutionError(code, message))
}
