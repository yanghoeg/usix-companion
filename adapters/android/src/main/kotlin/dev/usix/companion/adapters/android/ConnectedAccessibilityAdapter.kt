package dev.usix.companion.adapters.android

import dev.usix.companion.application.DeviceStatePort
import dev.usix.companion.application.ScreenObservationPort
import dev.usix.companion.application.UiActionPort
import dev.usix.companion.application.RichObservationPort
import dev.usix.companion.domain.*
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The DI graph owns this connection. An old service cannot detach its replacement. */
class ConnectedAccessibilityAdapter(private val mainDispatcher: CoroutineDispatcher, private val ioDispatcher: CoroutineDispatcher = mainDispatcher) :
    DeviceStatePort, ScreenObservationPort, UiActionPort, RichObservationPort {
    @Volatile private var current: AccessibilityOperations? = null
    @Volatile private var rich: RichAccessibilityOperations? = null
    private val generation = MutableStateFlow(0L)
    override val changes = generation.asStateFlow()

    @Synchronized fun attach(operations: AccessibilityOperations) {
        current = operations; rich = RichAccessibilityOperations(operations.service) { generation.value }; generation.value++
    }
    @Synchronized fun detach(operations: AccessibilityOperations) {
        if (current === operations) { current = null; rich = null; generation.value++ }
    }
    @Synchronized fun onEvent(operations: AccessibilityOperations, type: Int) {
        if (current === operations && type != android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) generation.value++
    }
    override suspend fun observe(packageId: String): ExecutionResult<ObservedScreen> = withContext(mainDispatcher) {
        rich?.observe(packageId) ?: ExecutionResult.Rejected(ExecutionError(ExecutionErrorCode.AccessibilityDisconnected, "Accessibility service disconnected"))
    }
    override suspend fun perform(expected: ObservedScreen, operation: String, request: UiActionRequest, guard: suspend () -> ExecutionError?): UiEffect = withContext(mainDispatcher) {
        rich?.perform(expected, operation, request) { withContext(ioDispatcher) { guard() } } ?: UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.AccessibilityDisconnected, "Accessibility service disconnected"))
    }
    override fun captureReadiness(language: String?) = rich?.captureReadiness(language)
        ?: if (rich == null) ExecutionError(ExecutionErrorCode.AccessibilityDisconnected, "Accessibility service disconnected") else null
    override suspend fun capture(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual> = withContext(mainDispatcher) {
        rich?.capture(expected, language) ?: ExecutionResult.Rejected(ExecutionError(ExecutionErrorCode.AccessibilityDisconnected, "Accessibility service disconnected"))
    }

    override fun accessibilityConnected() = current != null
    override suspend fun observe(packageId: PackageId?): List<ScreenNode> = withContext(mainDispatcher) {
        current?.dumpScreen(packageId?.value) ?: emptyList()
    }
    override suspend fun tap(x: Int, y: Int): Boolean = withContext(mainDispatcher) { current?.tap(x, y) ?: false }
    override suspend fun type(text: String, packageId: PackageId?): Boolean = withContext(mainDispatcher) {
        current?.setFocusedText(text, packageId?.value) ?: false
    }
    override suspend fun back(): Boolean = withContext(mainDispatcher) { current?.back() ?: false }
    override suspend fun scroll(packageId: PackageId?, forward: Boolean): Boolean = withContext(mainDispatcher) {
        current?.scroll(packageId?.value, forward) ?: false
    }
}
