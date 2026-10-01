package dev.usix.companion.adapters.android

import dev.usix.companion.application.DeviceStatePort
import dev.usix.companion.application.ScreenObservationPort
import dev.usix.companion.application.UiActionPort
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The DI graph owns this connection. An old service cannot detach its replacement. */
class ConnectedAccessibilityAdapter(private val mainDispatcher: CoroutineDispatcher) :
    DeviceStatePort, ScreenObservationPort, UiActionPort {
    @Volatile private var current: AccessibilityOperations? = null

    @Synchronized fun attach(operations: AccessibilityOperations) { current = operations }
    @Synchronized fun detach(operations: AccessibilityOperations) {
        if (current === operations) current = null
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
