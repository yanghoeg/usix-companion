package dev.usix.companion.application

import dev.usix.companion.domain.ActionOutcome
import dev.usix.companion.domain.DeviceHealth
import dev.usix.companion.domain.DeviceResult
import dev.usix.companion.domain.MailDraft
import dev.usix.companion.domain.NotificationInfo
import dev.usix.companion.domain.NotificationRef
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode

interface DeviceQuery {
    fun health(): DeviceHealth
    suspend fun screen(packageId: PackageId?): DeviceResult<List<ScreenNode>>
    suspend fun notifications(): List<NotificationInfo>
}

interface ExecuteDeviceAction {
    suspend fun execute(action: DeviceAction): DeviceResult<ActionOutcome>
}

interface ScreenObservationPort {
    suspend fun observe(packageId: PackageId?): List<ScreenNode>
}

interface UiActionPort {
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun type(text: String, packageId: PackageId?): Boolean
    suspend fun back(): Boolean
    suspend fun scroll(packageId: PackageId?, forward: Boolean): Boolean
}

interface AppLauncherPort {
    suspend fun open(packageId: PackageId): Boolean
    suspend fun compose(draft: MailDraft): Boolean
}

interface NotificationPort {
    fun connected(): Boolean
    suspend fun snapshot(): List<NotificationInfo>
    suspend fun reply(reference: NotificationRef, text: String): Boolean
}

interface DeviceStatePort {
    fun accessibilityConnected(): Boolean
}

/** The transport parses the authorization scheme; the adapter compares the secret. */
fun interface CredentialVerifier {
    fun verify(bearer: String?): Boolean
}

/** Local setup only; credentials are never part of a device query/result. */
interface PairingCredentials {
    fun current(): String
    fun regenerate(): String
}
