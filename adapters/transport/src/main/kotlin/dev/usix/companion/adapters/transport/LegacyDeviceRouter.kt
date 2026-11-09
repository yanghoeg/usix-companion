package dev.usix.companion.adapters.transport

import dev.usix.companion.application.DeviceAction
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.ExecuteDeviceAction
import dev.usix.companion.application.InputField
import dev.usix.companion.application.ReadNavigation
import dev.usix.companion.application.NotificationHistory
import dev.usix.companion.domain.NotificationHistoryQuery
import dev.usix.companion.domain.NotificationHistoryPage
import dev.usix.companion.domain.NotificationRecordSource
import dev.usix.companion.domain.ReadWindow
import dev.usix.companion.domain.ScreenBounds
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
import dev.usix.companion.protocol.DeviceV2Codec
import dev.usix.companion.protocol.WireField

class LegacyDeviceRouter(private val query: DeviceQuery, private val actions: ExecuteDeviceAction, private val readNavigation: ReadNavigation? = null,
    private val notificationHistory: NotificationHistory? = null) {
    suspend fun route(method: String, path: String, body: String, paired: Boolean): LegacyResponse {
        val command = try { LegacyCodec.decode(method, path, body) } catch (_: DecodeFailure) {
            return LegacyCodec.error("400 Bad Request", "bad json")
        }
        when (command) {
            is LegacyCommand.NotificationHistory -> {
                if (!paired) return LegacyCodec.error("401 Unauthorized", "Pairing required")
                val history = notificationHistory ?: return LegacyCodec.error("503 Service Unavailable", "Notification history is unavailable")
                return when (val result = history.query(NotificationHistoryQuery(command.packageName, command.query,
                    command.since, command.until, command.limit, command.before))) {
                    is DeviceResult.Rejected -> rejection(result)
                    is DeviceResult.Success -> historyPage(result.value)
                }
            }
            is LegacyCommand.ReadWindow -> {
                if (!paired) return LegacyCodec.error("401 Unauthorized", "Pairing required")
                val packageId = command.packageName ?: return LegacyCodec.error("400 Bad Request", "Explicit package required")
                val navigation = readNavigation ?: return LegacyCodec.error("503 Service Unavailable", "Reading navigation is unavailable")
                return when (val result = navigation.observe(packageId)) {
                    is DeviceResult.Rejected -> rejection(result)
                    is DeviceResult.Success -> readWindow(result.value)
                }
            }
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
        if ((command is LegacyCommand.ReadSelect || command is LegacyCommand.ReadScroll || command is LegacyCommand.ClearNotificationHistory) && !paired)
            return LegacyCodec.error("401 Unauthorized", "Pairing required")
        val action = when (command) {
            is LegacyCommand.ClearNotificationHistory -> DeviceAction.ClearNotificationHistory(command.packageName)
            is LegacyCommand.ReadSelect -> DeviceAction.ReadSelect(command.packageName)
            is LegacyCommand.ReadScroll -> DeviceAction.ReadScroll(command.packageName, command.direction, command.windowRef, command.nodeRef)
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
            is DeviceResult.Success -> if (command is LegacyCommand.ReadSelect) {
                val navigation = readNavigation ?: return LegacyCodec.error("503 Service Unavailable", "Reading navigation is unavailable")
                when (val view = navigation.observe(command.packageName)) {
                    is DeviceResult.Rejected -> rejection(view)
                    is DeviceResult.Success -> readWindow(view.value)
                }
            } else if (command is LegacyCommand.ReadScroll) DeviceV2Codec.response(mapOf(
                "ok" to result.value.accepted, "effect" to if (result.value.accepted) "dispatched" else "unknown",
                "windowRefConsumed" to true, "readRequired" to true, "error" to result.value.detail))
                else if (command is LegacyCommand.ClearNotificationHistory) DeviceV2Codec.response(mapOf(
                    "ok" to result.value.accepted, "cleared" to result.value.accepted, "source" to "notification_history", "appDataChanged" to false))
                else LegacyCodec.action(result.value.accepted, result.value.kind == ActionKind.MAIL_DRAFT, result.value.detail)
        }
    }

    private fun rejection(result: DeviceResult.Rejected) = LegacyCodec.error(when (result.error.code) {
        DeviceErrorCode.INVALID_ARGUMENT -> "400 Bad Request"
        DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE -> "503 Service Unavailable"
        DeviceErrorCode.NOTIFICATION_HISTORY_UNAVAILABLE -> "503 Service Unavailable"
        DeviceErrorCode.COMPACT_WINDOW_UNAVAILABLE -> "503 Service Unavailable"
        DeviceErrorCode.CONTROLLER_CONFLICT -> "409 Conflict"
        DeviceErrorCode.APPROVAL_REQUIRED -> "403 Forbidden"
        DeviceErrorCode.READ_WINDOW_UNAVAILABLE, DeviceErrorCode.STALE_READ_WINDOW -> "409 Conflict"
    }, result.error.message)

    private fun historyPage(page: NotificationHistoryPage): LegacyResponse {
        var bytes = 0
        val records = page.records.map { record -> mapOf("id" to record.id, "package" to record.packageId,
            "title" to record.title, "sender" to record.sender, "text" to record.text,
            "eventTime" to record.eventTimeMillis, "capturedAt" to record.capturedAtMillis,
            "source" to if (record.source == NotificationRecordSource.MessagingStyle) "notification_message" else "notification",
            "truncated" to record.truncated, "attachment" to record.attachment, "recoveredActive" to record.recoveredActive)
        }.takeWhile { item ->
            bytes += DeviceV2Codec.response(item).json.toByteArray(Charsets.UTF_8).size
            bytes <= 512 * 1024
        }
        val limited = records.size < page.records.size
        return DeviceV2Codec.response(mapOf("ok" to true, "schema" to "usix-companion.notification-history/v1",
            "source" to "notification_history", "package" to NotificationHistory.KAKAO_PACKAGE,
            "conversationComplete" to false, "order" to "captured_desc", "records" to records,
            "hasMore" to (limited || page.hasMore), "nextBefore" to if (limited) records.last()["id"] else page.nextBefore,
            "collectionStartedAt" to page.startedAtMillis, "oldestEventTime" to page.oldestEventTimeMillis,
            "storedRecords" to page.storedRecords, "retentionDays" to page.retentionMillis / 86_400_000,
            "maxRecords" to page.maxRecords, "listenerConnected" to page.capture.listenerConnected,
            "pendingBatches" to page.capture.pendingBatches, "droppedBatchesSinceProcessStart" to page.capture.droppedBatches,
            "failedBatchesSinceProcessStart" to page.capture.failedBatches, "dataLossPossible" to page.dataLossPossible,
            "timeZone" to java.util.TimeZone.getDefault().id))
    }

    private fun readWindow(view: ReadWindow): LegacyResponse {
        fun bounds(b: ScreenBounds) = mapOf("left" to b.left, "top" to b.top, "right" to b.right, "bottom" to b.bottom)
        val screen = view.screen
        val visible = if (view.compact) screen.nodes.filter { it.visible &&
            (it.text.isNotBlank() || it.description.isNotBlank() || it.scrollable || it.editable) } else emptyList()
        return DeviceV2Codec.response(mapOf("ok" to true, "schema" to "usix-companion.read-window/v1",
            "package" to screen.packageId, "windowId" to screen.windowId, "windowRef" to view.ref,
            "windowFocused" to screen.windowFocused, "focusedWindowId" to screen.focusedWindowId,
            "capturedAt" to view.capturedAtMillis, "expiresAt" to view.expiresAtMillis,
            "compact" to view.compact, "windowBounds" to bounds(screen.windowBounds), "displayBounds" to bounds(screen.displayBounds),
            "navigationAvailable" to (view.ref != null), "navigationReason" to when {
                !view.compact -> "A verified visible popup occupying at most half the display is required"
                !screen.complete -> "Incomplete accessibility tree; scrolling is unavailable"
                view.ref == null -> "Use read-select to select the existing popup without launching it; selection lasts five minutes and 32 scrolls"
                else -> null
            },
            "complete" to (screen.complete && visible.size <= 128), "totalVisibleNodes" to visible.size,
            "scrollTargets" to visible.filter { it.scrollable && it.enabled && !it.sensitive &&
                it.bounds.left >= screen.windowBounds.left && it.bounds.top >= screen.windowBounds.top &&
                it.bounds.right <= screen.windowBounds.right && it.bounds.bottom <= screen.windowBounds.bottom &&
                it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top }.map { mapOf("nodeRef" to it.ref, "resourceId" to it.resourceId, "bounds" to bounds(it.bounds)) },
            "nodes" to visible.take(128).map { mapOf("nodeRef" to it.ref, "text" to it.text, "description" to it.description,
                "resourceId" to it.resourceId, "bounds" to bounds(it.bounds), "scrollable" to it.scrollable,
                "editable" to it.editable, "sensitive" to it.sensitive, "textTruncated" to it.textTruncated) }))
    }

    private fun WireField.input(): InputField<String> = when (this) {
        WireField.Missing -> InputField.Missing
        WireField.Invalid -> InputField.Invalid
        is WireField.Text -> InputField.Value(value)
    }
}
