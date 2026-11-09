package dev.usix.companion.application

import dev.usix.companion.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadNavigationTest {
    private var now = 1_000L
    private var sequence = 0
    private val pkg = "com.example.reader"
    private val container = ObservedNode("n/0", "n", emptyList(), "chat_list", "RecyclerView", "scroll_container",
        ScreenBounds(600, 100, 900, 650), "visible preview", "", true, true, false, false, true, false, false, false)
    private var screen = ObservedScreen(pkg, 42, 1, ScreenBounds(0, 0, 1000, 1000), ScreenBounds(550, 50, 950, 700),
        0, null, false, "actual-window-42", listOf(container), true)
    private var acknowledgement = UiEffectState.Dispatched
    private var readinessError: ExecutionError? = null
    private var dispatches = 0
    private var onObservation: (() -> Unit)? = null
    private val requests = mutableListOf<UiActionRequest>()
    private val port = object : RichObservationPort {
        override val changes = MutableStateFlow(1L)
        override suspend fun observe(packageId: String): ExecutionResult<ObservedScreen> {
            onObservation?.invoke()
            return if (packageId == screen.packageId) ExecutionResult.Success(screen)
                else ExecutionResult.Rejected(ExecutionError(ExecutionErrorCode.ExpiredReference, "No visible selected window"))
        }
        override suspend fun perform(expected: ObservedScreen, operation: String, request: UiActionRequest, guard: suspend () -> ExecutionError?): UiEffect {
            guard()?.let { return UiEffect(UiEffectState.None, it) }
            if (screen != expected) return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Window changed"))
            assertEquals("ui.scroll", operation)
            requests.add(request); dispatches++
            return UiEffect(acknowledgement)
        }
        override fun captureReadiness(language: String?) = ExecutionError(ExecutionErrorCode.UnsupportedCapability, "No capture")
        override suspend fun capture(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual> = ExecutionResult.Rejected(captureReadiness(language))
    }
    val navigation = ReadNavigation(port, ExecutionClock { now }, ExecutionIds { "window-${sequence++}" }, ExecutionReadiness { _, _ -> readinessError })
    private suspend fun view() = (navigation.observe(pkg) as DeviceResult.Success).value
    private fun action(view: ReadWindow, direction: String = "up") = DeviceAction.ReadScroll(pkg, direction, view.ref!!, container.ref)
    private fun code(result: DeviceResult<*>) = (result as DeviceResult.Rejected).error.code

    @Test fun observationDoesNotGrantScrollUntilTheAppWasExplicitlySelected() = runBlocking {
        assertNull(view().ref)
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(DeviceAction.ReadScroll(pkg, "up", "invented", "n/0"))))
        assertEquals(0, dispatches)
    }
    @Test fun existingPopupCanBeSelectedAndRenewedUsingOnlyObservation() = runBlocking {
        assertTrue((navigation.selectVisible(pkg) as DeviceResult.Success).value.accepted)
        val first = action(view())
        assertTrue((navigation.selectVisible(pkg) as DeviceResult.Success).value.accepted)
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(first)))
        assertNotNull(view().ref)
        assertEquals(0, dispatches); assertTrue(requests.isEmpty())
    }
    @Test fun selectionRejectsMissingFullscreenFallbackIncompleteAndLockedWindows() = runBlocking {
        assertEquals(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, code(navigation.selectVisible("com.other.app")))
        val valid = screen
        for (invalid in listOf(valid.copy(windowBounds = valid.displayBounds), valid.copy(windowSignature = "active:42"), valid.copy(complete = false))) {
            screen = invalid
            assertEquals(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, code(navigation.selectVisible(pkg)))
            assertNull(view().ref)
        }
        screen = valid; readinessError = ExecutionError(ExecutionErrorCode.DeviceLocked, "Unlock the device")
        assertEquals(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, code(navigation.selectVisible(pkg)))
        assertEquals(0, dispatches)
    }
    @Test fun pauseDuringSelectionCannotRestoreNavigation() = runBlocking {
        onObservation = { navigation.clear() }
        assertEquals(DeviceErrorCode.APPROVAL_REQUIRED, code(navigation.selectVisible(pkg)))
        onObservation = null
        assertNull(view().ref); assertEquals(0, dispatches)
    }
    @Test fun upAndDownOnlyDispatchOnTheObservedContainer() = runBlocking {
        navigation.select(pkg)
        for (direction in listOf("up", "down")) assertTrue((navigation.scroll(action(view(), direction)) as DeviceResult.Success).value.accepted)
        assertEquals(listOf(false, true), requests.map { it.forward })
        assertEquals(listOf("up", "down"), requests.map { it.verticalDirection })
        assertEquals(listOf("n/0", "n/0"), requests.map { it.selector!!.nodeRef })
        assertTrue(requests.all { it.windowScoped })
    }
    @Test fun replayAndOtherPreDispatchReferencesCannotScrollTwice() = runBlocking {
        navigation.select(pkg); val first = action(view()); val other = action(view())
        navigation.scroll(first)
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(first)))
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(other)))
        assertEquals(1, dispatches)
    }
    @Test fun anotherPackageAndUnobservedTargetsProduceNoEffect() = runBlocking {
        navigation.select(pkg); val command = action(view())
        assertEquals(DeviceErrorCode.APPROVAL_REQUIRED, code(navigation.scroll(command.copy(packageName = "com.other.app"))))
        assertEquals(DeviceErrorCode.INVALID_ARGUMENT, code(navigation.scroll(command.copy(nodeRef = "n/unobserved"))))
        assertEquals(DeviceErrorCode.INVALID_ARGUMENT, code(navigation.scroll(command.copy(direction = "sideways"))))
        assertEquals(0, dispatches)
    }
    @Test fun fullscreenRootFallbackAndIncompleteTreesNeverIssueScrollReferences() = runBlocking {
        navigation.select(pkg)
        screen = screen.copy(windowBounds = screen.displayBounds)
        assertFalse(view().compact); assertNull(view().ref)
        screen = screen.copy(windowBounds = ScreenBounds(550, 50, 950, 700), windowSignature = "active:42")
        assertFalse(view().compact); assertNull(view().ref)
        screen = screen.copy(windowSignature = "actual-window-42", complete = false)
        assertNull(view().ref)
    }
    @Test fun resizedOrChangedWindowCannotUseTheOldObservation() = runBlocking {
        navigation.select(pkg); val command = action(view())
        screen = screen.copy(windowBounds = screen.displayBounds)
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(command)))
        assertEquals(0, dispatches)
    }
    @Test fun expiredReferenceAndPauseInvalidateNavigation() = runBlocking {
        navigation.select(pkg); val command = action(view()); now += 30_000
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(command)))
        val fresh = action(view()); navigation.clear()
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(fresh)))
        assertNull(view().ref); assertEquals(0, dispatches)
    }
    @Test fun finiteNavigationBudgetAndSelectionLifetimeCannotBeRenewedByReading() = runBlocking {
        navigation.select(pkg)
        repeat(32) { navigation.scroll(action(view())) }
        assertNull(view().ref); assertEquals(32, dispatches)
        navigation.select(pkg); now += 300_000
        assertNull(view().ref)
    }
    @Test fun unknownAcknowledgementIsNotSuccessAndStillConsumesTheReference() = runBlocking {
        navigation.select(pkg); val command = action(view()); acknowledgement = UiEffectState.Possible
        assertFalse((navigation.scroll(command) as DeviceResult.Success).value.accepted)
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(command)))
        assertEquals(1, dispatches)
    }
    @Test fun sensitiveOrOutsideWindowContainersNeverDispatch() = runBlocking {
        navigation.select(pkg)
        for (node in listOf(container.copy(sensitive = true), container.copy(bounds = screen.displayBounds), container.copy(scrollable = false))) {
            screen = screen.copy(nodes = listOf(node))
            assertEquals(DeviceErrorCode.INVALID_ARGUMENT, code(navigation.scroll(action(view()))))
        }
        assertEquals(0, dispatches)
    }
    @Test fun lockedOrUnavailableDeviceCannotIssueOrUseNavigationReferences() = runBlocking {
        navigation.select(pkg); val command = action(view())
        readinessError = ExecutionError(ExecutionErrorCode.DeviceLocked, "Unlock the device")
        assertEquals(DeviceErrorCode.READ_WINDOW_UNAVAILABLE, code(navigation.observe(pkg)))
        assertEquals(DeviceErrorCode.STALE_READ_WINDOW, code(navigation.scroll(command)))
        assertEquals(0, dispatches)
    }
}
