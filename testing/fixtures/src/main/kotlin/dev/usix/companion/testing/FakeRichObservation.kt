package dev.usix.companion.testing

import dev.usix.companion.application.RichObservationPort
import dev.usix.companion.domain.*
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRichObservation : RichObservationPort {
    override val changes = MutableStateFlow(1L)
    var observations = 0
    var effects = 0
    var nextEffect = UiEffectState.Dispatched
    var screen = ObservedScreen(CONTROLLED_UI_PACKAGE, 1, 1, ScreenBounds(0, 0, 500, 900), ScreenBounds(0, 0, 500, 900),
        0, null, false, "fixture-window", listOf(node("n/0", "account", "eval-account"), node("n/1", "input", "Initial", editable = true),
            node("n/2", "duplicate_a", "Duplicate"), node("n/3", "duplicate_b", "Duplicate"),
            node("n/4", "password", "[redacted]", sensitive = true)), true)
    fun change(transform: (ObservedScreen) -> ObservedScreen) {
        screen = transform(screen).copy(generation = changes.value + 1)
        changes.value = screen.generation
    }
    override suspend fun observe(packageId: String): ExecutionResult<ObservedScreen> {
        observations++
        return if (screen.packageId == packageId) ExecutionResult.Success(screen)
            else ExecutionResult.Rejected(ExecutionError(ExecutionErrorCode.ExpiredReference, "Fixture is no longer foreground"))
    }
    override suspend fun perform(expected: ObservedScreen, operation: String, request: UiActionRequest, guard: suspend () -> ExecutionError?): UiEffect {
        guard()?.let { return UiEffect(UiEffectState.None, it) }
        if (expected != screen) return UiEffect(UiEffectState.None, ExecutionError(ExecutionErrorCode.StaleSnapshot, "Changed fixture"))
        effects++
        if (operation == "ui.set_text") change { state -> state.copy(nodes = state.nodes.map { if (it.ref == request.selector?.nodeRef) it.copy(text = request.text!!) else it }) }
        return UiEffect(nextEffect)
    }
    override fun captureReadiness(language: String?) = ExecutionError(ExecutionErrorCode.UnsupportedCapability, "Fake never captures a device")
    override suspend fun capture(expected: ObservedScreen, language: String?): ExecutionResult<CapturedVisual> = ExecutionResult.Rejected(captureReadiness(language))
    companion object {
        fun node(ref: String, id: String, text: String, editable: Boolean = false, sensitive: Boolean = false) = ObservedNode(ref, "n", emptyList(), id,
            "android.widget.TextView", if (editable) "textbox" else "button", ScreenBounds(10, 10, 100, 80), text, if (sensitive) "[redacted]" else "",
            true, true, editable, true, false, false, false, sensitive)
    }
}
