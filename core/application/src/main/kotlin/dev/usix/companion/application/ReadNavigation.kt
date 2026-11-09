package dev.usix.companion.application

import dev.usix.companion.domain.*

/** Owner-authenticated reading navigation, limited to one explicitly selected visible popup. */
class ReadNavigation(private val port: RichObservationPort, private val clock: ExecutionClock, private val ids: ExecutionIds,
    private val readiness: ExecutionReadiness = ExecutionReadiness { _, _ -> null }) {
    private data class Selection(val packageId: String, val expiresAt: Long, var remaining: Int = 32)
    private var selection: Selection? = null
    private var selectionRevision = 0L
    private val windows = linkedMapOf<String, ReadWindow>()

    @Synchronized fun select(packageId: String) {
        selectionRevision++
        windows.clear()
        selection = Selection(packageId, clock.nowMillis() + 300_000)
    }
    @Synchronized fun clear() { selectionRevision++; selection = null; windows.clear() }

    /** Select the existing window by observation only: never launch or focus an activity. */
    suspend fun selectVisible(packageId: String): DeviceResult<ActionOutcome> {
        val revision = synchronized(this) { selectionRevision }
        val view = when (val result = observe(packageId)) {
            is DeviceResult.Rejected -> return result
            is DeviceResult.Success -> result.value
        }
        if (!view.compact || !view.screen.complete)
            return rejected(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, "A complete visible compact popup is required; the app will not be launched")
        return synchronized(this) {
            if (selectionRevision != revision)
                return@synchronized rejected(DeviceErrorCode.APPROVAL_REQUIRED, "Reading selection was paused or changed before selection completed")
            select(packageId)
            DeviceResult.Success(ActionOutcome(true, ActionKind.UI))
        }
    }

    suspend fun observe(packageId: String): DeviceResult<ReadWindow> {
        if (!PACKAGE.matches(packageId)) return rejected(DeviceErrorCode.INVALID_ARGUMENT, "package must be an explicit Android package name")
        readiness.rejection("ui.observe", packageId)?.let { return rejected(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, it.message) }
        val observed = when (val result = port.observe(packageId)) {
            is ExecutionResult.Rejected -> return rejected(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, result.error.message)
            is ExecutionResult.Success -> result.value
        }
        if (observed.packageId != packageId) return rejected(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, "Observation belongs to another package")
        val compact = compact(observed)
        val now = clock.nowMillis()
        return synchronized(this) {
            windows.entries.removeAll { it.value.expiresAtMillis <= now }
            val allowed = selection?.let { it.packageId == packageId && it.expiresAt > now && it.remaining > 0 } == true
            val ref = if (compact && observed.complete && allowed) ids.next() else null
            val view = ReadWindow(ref, now, now + 30_000, observed, compact)
            if (ref != null) {
                while (windows.size >= 32) windows.remove(windows.keys.first())
                windows[ref] = view
            }
            DeviceResult.Success(view)
        }
    }

    suspend fun scroll(action: DeviceAction.ReadScroll): DeviceResult<ActionOutcome> {
        if (!PACKAGE.matches(action.packageName) || action.direction !in setOf("up", "down"))
            return rejected(DeviceErrorCode.INVALID_ARGUMENT, "Explicit package and direction up/down are required")
        val binding = synchronized(this) {
            val window = windows[action.windowRef]
                ?: return rejected(DeviceErrorCode.STALE_READ_WINDOW, "Read window expired or was consumed; observe again")
            if (window.expiresAtMillis <= clock.nowMillis()) {
                windows.remove(action.windowRef)
                return rejected(DeviceErrorCode.STALE_READ_WINDOW, "Read window expired; observe again")
            }
            if (window.screen.packageId != action.packageName)
                return rejected(DeviceErrorCode.APPROVAL_REQUIRED, "Read window belongs to a different package")
            if (!authorized(action.packageName))
                return rejected(DeviceErrorCode.APPROVAL_REQUIRED, "Reading navigation expired or was paused; select the existing popup again")
            val node = window.screen.nodes.singleOrNull { it.ref == action.nodeRef }
            if (node == null || !node.scrollable || !node.enabled || !node.visible || node.sensitive || !inside(node.bounds, window.screen.windowBounds))
                return rejected(DeviceErrorCode.INVALID_ARGUMENT, "Target must be an observed visible scroll container inside the selected popup")
            // Consume before dispatch, including uncertain acknowledgements. Never replay a scroll.
            windows.clear()
            selection!!.remaining--
            window.screen to selection!!
        }
        val effect = port.perform(binding.first, "ui.scroll", UiActionRequest(
            selector = NodeSelector(nodeRef = action.nodeRef), forward = action.direction == "down", verticalDirection = action.direction,
            windowScoped = true)) {
            if (synchronized(this) { selection === binding.second && authorized(action.packageName, allowSpent = true) })
                readiness.rejection("ui.scroll", action.packageName)
            else ExecutionError(ExecutionErrorCode.AuthorityRevoked, "Reading navigation was paused or expired before dispatch")
        }
        if (effect.state == UiEffectState.None) return rejected(
            if (effect.error?.code == ExecutionErrorCode.UnsupportedCapability) DeviceErrorCode.COMPACT_WINDOW_UNAVAILABLE else DeviceErrorCode.STALE_READ_WINDOW,
            effect.error?.message ?: "Scroll was not dispatched; observe again")
        return DeviceResult.Success(ActionOutcome(effect.state == UiEffectState.Dispatched, ActionKind.UI,
            if (effect.state == UiEffectState.Dispatched) null else "Scroll effect is unknown; inspect the window before another attempt"))
    }

    private fun authorized(packageId: String, allowSpent: Boolean = false) = selection?.let {
        it.packageId == packageId && it.expiresAt > clock.nowMillis() && (allowSpent || it.remaining > 0)
    } == true
    private fun compact(screen: ObservedScreen): Boolean {
        fun area(b: ScreenBounds) = (b.right.toLong() - b.left).coerceAtLeast(0) * (b.bottom.toLong() - b.top).coerceAtLeast(0)
        val display = area(screen.displayBounds); val window = area(screen.windowBounds)
        return !screen.windowSignature.startsWith("active:") && display > 0 && window > 0 && window <= display / 2 && inside(screen.windowBounds, screen.displayBounds)
    }
    private fun inside(b: ScreenBounds, outer: ScreenBounds) = b.right > b.left && b.bottom > b.top &&
        b.left >= outer.left && b.top >= outer.top && b.right <= outer.right && b.bottom <= outer.bottom
    private fun rejected(code: DeviceErrorCode, message: String) = DeviceResult.Rejected(DeviceError(code, message))
    companion object { private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") }
}
