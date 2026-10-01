package dev.usix.companion.application

import dev.usix.companion.domain.ActionKind
import dev.usix.companion.domain.ActionOutcome
import dev.usix.companion.domain.DeviceError
import dev.usix.companion.domain.DeviceErrorCode
import dev.usix.companion.domain.DeviceHealth
import dev.usix.companion.domain.DeviceResult
import dev.usix.companion.domain.MailDraft
import dev.usix.companion.domain.NotificationInfo
import dev.usix.companion.domain.NotificationRef
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceApplication(
    private val state: DeviceStatePort,
    private val observations: ScreenObservationPort,
    private val ui: UiActionPort,
    private val launcher: AppLauncherPort,
    private val notifications: NotificationPort,
) : DeviceQuery, ExecuteDeviceAction {
    private val uiMutex = Mutex()

    override fun health() = DeviceHealth(notifications.connected(), state.accessibilityConnected())

    override suspend fun screen(packageId: PackageId?): DeviceResult<List<ScreenNode>> =
        if (state.accessibilityConnected()) DeviceResult.Success(observations.observe(packageId)) else unavailable()

    override suspend fun notifications(): List<NotificationInfo> = notifications.snapshot()

    override suspend fun execute(action: DeviceAction): DeviceResult<ActionOutcome> = try {
        when (action) {
            is DeviceAction.Tap -> {
                require(action.x != null && action.y != null) { "x/y required" }
                uiAction { ActionOutcome(ui.tap(action.x, action.y), ActionKind.UI) }
            }
            is DeviceAction.Type -> {
                val pkg = optionalPackage(action.packageName)
                require(!action.text.isNullOrEmpty()) { "text required" }
                uiAction { ActionOutcome(ui.type(action.text, pkg), ActionKind.UI) }
            }
            DeviceAction.Back -> uiAction { ActionOutcome(ui.back(), ActionKind.UI) }
            is DeviceAction.Scroll -> {
                val pkg = optionalPackage(action.packageName)
                require(action.direction == "down" || action.direction == "up") { "direction must be down or up" }
                uiAction {
                    val ok = ui.scroll(pkg, action.direction == "down")
                    ActionOutcome(ok, ActionKind.UI, if (ok) null else
                        "no scrollable content moved; the app may not be visible or the end is reached")
                }
            }
            is DeviceAction.Open -> {
                require(!action.packageName.isNullOrEmpty()) { "package required" }
                launch(PackageId(action.packageName), false)
            }
            is DeviceAction.OpenEmail -> launch(optionalPackage(action.packageName) ?: THUNDERBIRD, true)
            is DeviceAction.ComposeEmail -> {
                val pkg = optionalPackage(action.packageName) ?: THUNDERBIRD
                // Same single-address grammar as legacy Android Patterns.EMAIL_ADDRESS.
                // Thunderbird decodes recipients twice, so percent escapes are forbidden.
                require(action.to != null && '%' !in action.to && EMAIL.matches(action.to)) {
                    "to must be one plain email address without percent escapes"
                }
                val subject = textOrEmpty(action.subject)
                val body = textOrEmpty(action.body)
                val ok = uiMutex.withLock { launcher.compose(MailDraft(action.to, subject, body, pkg)) }
                DeviceResult.Success(ActionOutcome(ok, ActionKind.MAIL_DRAFT, if (ok) null else
                    "email app could not open the composer; install Thunderbird or specify its package"))
            }
            is DeviceAction.Reply -> {
                require(!action.key.isNullOrEmpty() && !action.text.isNullOrEmpty()) { "key/text required" }
                val ok = notifications.reply(NotificationRef(action.key), action.text)
                DeviceResult.Success(ActionOutcome(ok, ActionKind.NOTIFICATION_REPLY, if (ok) null else
                    "no reply action for key (expired or not repliable)"))
            }
        }
    } catch (error: IllegalArgumentException) {
        DeviceResult.Rejected(DeviceError(DeviceErrorCode.INVALID_ARGUMENT, error.message ?: "invalid arguments"))
    }

    private suspend fun uiAction(block: suspend () -> ActionOutcome): DeviceResult<ActionOutcome> = uiMutex.withLock {
        if (!state.accessibilityConnected()) unavailable() else DeviceResult.Success(block())
    }

    private suspend fun launch(pkg: PackageId, email: Boolean): DeviceResult<ActionOutcome> {
        val ok = uiMutex.withLock { launcher.open(pkg) }
        return DeviceResult.Success(ActionOutcome(ok, ActionKind.APP_LAUNCH, if (ok) null else if (email)
            "email app could not be opened; install Thunderbird or specify its package" else "no launch intent for package"))
    }

    private fun optionalPackage(field: InputField<String>): PackageId? = when (field) {
        InputField.Missing -> null
        InputField.Invalid -> throw IllegalArgumentException("package must be a package name")
        is InputField.Value -> {
            require(PACKAGE.matches(field.value)) { "package must be a package name" }
            PackageId(field.value)
        }
    }

    private fun textOrEmpty(field: InputField<String>): String = when (field) {
        InputField.Missing -> ""
        InputField.Invalid -> throw IllegalArgumentException("subject/body must be strings")
        is InputField.Value -> field.value
    }

    private fun unavailable() = DeviceResult.Rejected(DeviceError(
        DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE, "accessibility service not enabled"))

    companion object {
        private val THUNDERBIRD = PackageId("net.thunderbird.android")
        private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val EMAIL = Regex("[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}(?:\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+")
    }
}
