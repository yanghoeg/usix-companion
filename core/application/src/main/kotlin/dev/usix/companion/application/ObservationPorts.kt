package dev.usix.companion.application

import dev.usix.companion.domain.*
import kotlinx.coroutines.flow.Flow

interface RichObservationPort {
    /** Emits an initial generation and changes; waits subscribe without polling or lost events. */
    val changes: Flow<Long>
    suspend fun observe(packageId: String): ExecutionResult<ObservedScreen>
    suspend fun perform(expected: ObservedScreen, operation: String, request: UiActionRequest,
        guard: suspend () -> ExecutionError?): UiEffect
    fun captureReadiness(language: String?): ExecutionError?
    suspend fun capture(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual>
}
