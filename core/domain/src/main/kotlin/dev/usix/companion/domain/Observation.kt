package dev.usix.companion.domain

const val CONTROLLED_UI_PACKAGE = "dev.usix.companion.fixture"

data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

/** References identify a path in one snapshot, never a durable Android node handle. */
data class ObservedNode(
    val ref: String, val parentRef: String?, val childRefs: List<String>,
    val resourceId: String?, val className: String, val role: String,
    val bounds: ScreenBounds, val text: String, val description: String,
    val enabled: Boolean, val visible: Boolean, val editable: Boolean,
    val clickable: Boolean, val scrollable: Boolean, val focused: Boolean,
    val selected: Boolean, val sensitive: Boolean,
    val textTruncated: Boolean = false,
)
data class ObservedScreen(
    val packageId: String, val windowId: Int, val generation: Long,
    val displayBounds: ScreenBounds, val windowBounds: ScreenBounds, val rotation: Int,
    val focusRef: String?, val inputWindowVisible: Boolean, val windowSignature: String,
    val nodes: List<ObservedNode>, val complete: Boolean,
)
data class ScreenSnapshot(
    val ref: String, val context: ExecutionContext, val scope: ExecutionScope,
    val capturedAtMillis: Long, val expiresAtMillis: Long, val screen: ObservedScreen,
)
data class SnapshotPage(
    val snapshot: ScreenSnapshot, val nodes: List<ObservedNode>, val offset: Int,
    val totalMatching: Int, val nextOffset: Int?,
)
data class NodeSelector(
    val nodeRef: String? = null, val resourceId: String? = null, val text: String? = null,
    val description: String? = null, val className: String? = null, val role: String? = null,
    val windowId: Int? = null, val editable: Boolean? = null, val scrollable: Boolean? = null,
) {
    fun matches(node: ObservedNode, window: Int) =
        (!node.textTruncated || (text == null && description == null)) &&
        (nodeRef == null || node.ref == nodeRef) && (resourceId == null || node.resourceId == resourceId) &&
        (text == null || node.text == text) && (description == null || node.description == description) &&
        (className == null || node.className == className) && (role == null || node.role == role) &&
        (windowId == null || windowId == window) && (editable == null || editable == node.editable) &&
        (scrollable == null || scrollable == node.scrollable)
    fun hasIdentity() = nodeRef != null || resourceId != null || text != null || description != null || className != null || role != null || editable != null || scrollable != null
}
data class UiActionRequest(
    val selector: NodeSelector? = null, val text: String? = null,
    val x: Int? = null, val y: Int? = null, val endX: Int? = null, val endY: Int? = null,
    val durationMillis: Long = 250,
    val forward: Boolean = true,
)
/** These criteria establish UI state only; they cannot prove sending or delivery. */
data class UiGoal(
    val goalId: String, val kind: String, val selector: NodeSelector? = null,
    val expectedText: String? = null, val accountSelector: NodeSelector? = null,
)
data class VerificationEvidence(
    val evidenceRef: String, val goalId: String, val verifiedAtMillis: Long,
    val snapshotRef: String, val method: String = "observed_state", val purpose: String = "ui_state",
)
enum class UiEffectState { None, Dispatched, Possible }
data class UiEffect(val state: UiEffectState, val error: ExecutionError? = null)
data class OcrBlock(val text: String, val bounds: ScreenBounds)
data class CapturedVisual(
    val png: ByteArray, val width: Int, val height: Int, val contentHash: String,
    val language: String?, val blocks: List<OcrBlock>, val redacted: Boolean,
    val ocrComplete: Boolean = true,
)
data class VisualObservation(val mediaRef: String, val snapshot: ScreenSnapshot, val visual: CapturedVisual)
data class UiVerification(val snapshot: ScreenSnapshot, val evidence: VerificationEvidence?)
