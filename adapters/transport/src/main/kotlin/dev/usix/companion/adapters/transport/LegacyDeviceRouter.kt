package dev.usix.companion.adapters.transport

import dev.usix.companion.application.DeviceAction
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.ExecuteDeviceAction
import dev.usix.companion.application.InputField
import dev.usix.companion.domain.ActionKind
import dev.usix.companion.domain.DeviceErrorCode
import dev.usix.companion.domain.DeviceResult
import dev.usix.companion.domain.PackageId
import dev.usix.companion.protocol.DecodeFailure
import dev.usix.companion.protocol.LegacyCodec
import dev.usix.companion.protocol.LegacyCommand
import dev.usix.companion.protocol.LegacyNotification
import dev.usix.companion.protocol.LegacyResponse
import dev.usix.companion.protocol.LegacyScreenNode
import dev.usix.companion.protocol.WireField

class LegacyDeviceRouter(private val query: DeviceQuery, private val actions: ExecuteDeviceAction) {
    suspend fun route(method: String, path: String, body: String, paired: Boolean): LegacyResponse {
        val command = try { LegacyCodec.decode(method, path, body) } catch (_: DecodeFailure) {
            return LegacyCodec.error("400 Bad Request", "bad json")
        }
        when (command) {
            LegacyCommand.Health -> return query.health().let { LegacyCodec.health(paired, it.listenerConnected, it.accessibilityConnected) }
            is LegacyCommand.Screen -> return when (val result = query.screen(command.packageName?.let(::PackageId))) {
                is DeviceResult.Rejected -> rejection(result)
                is DeviceResult.Success -> LegacyCodec.screen(result.value.map {
                    LegacyScreenNode(it.text, it.x, it.y, it.clickable, it.editable, it.scrollable)
                })
            }
            LegacyCommand.Notifications -> return LegacyCodec.notifications(query.notifications().map {
                LegacyNotification(it.reference.value, it.packageId.value, it.title, it.text, it.postTime, it.canReply)
            })
            LegacyCommand.NotFound -> return LegacyCodec.error("404 Not Found", "not found")
            else -> Unit
        }
        val action = when (command) {
            is LegacyCommand.Tap -> DeviceAction.Tap(command.x, command.y)
            is LegacyCommand.Type -> DeviceAction.Type(command.text, command.packageName.input())
            LegacyCommand.Back -> DeviceAction.Back
            is LegacyCommand.Open -> DeviceAction.Open(command.packageName)
            is LegacyCommand.Scroll -> DeviceAction.Scroll(command.direction, command.packageName.input())
            is LegacyCommand.OpenEmail -> DeviceAction.OpenEmail(command.packageName.input())
            is LegacyCommand.ComposeEmail -> DeviceAction.ComposeEmail(command.to, command.subject.input(), command.body.input(), command.packageName.input())
            is LegacyCommand.Reply -> DeviceAction.Reply(command.key, command.text)
            else -> error("query command already handled")
        }
        return when (val result = actions.execute(action)) {
            is DeviceResult.Rejected -> {
                // Legacy /type reported optional-package validation as bad JSON.
                if (command is LegacyCommand.Type && result.error.code == DeviceErrorCode.INVALID_ARGUMENT &&
                    result.error.message == "package must be a package name") LegacyCodec.error("400 Bad Request", "bad json")
                else rejection(result)
            }
            is DeviceResult.Success -> LegacyCodec.action(result.value.accepted, result.value.kind == ActionKind.MAIL_DRAFT, result.value.detail)
        }
    }

    private fun rejection(result: DeviceResult.Rejected) = LegacyCodec.error(when (result.error.code) {
        DeviceErrorCode.INVALID_ARGUMENT -> "400 Bad Request"
        DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE -> "503 Service Unavailable"
        DeviceErrorCode.CONTROLLER_CONFLICT -> "409 Conflict"
        DeviceErrorCode.APPROVAL_REQUIRED -> "403 Forbidden"
    }, result.error.message)

    private fun WireField.input(): InputField<String> = when (this) {
        WireField.Missing -> InputField.Missing
        WireField.Invalid -> InputField.Invalid
        is WireField.Text -> InputField.Value(value)
    }
}
