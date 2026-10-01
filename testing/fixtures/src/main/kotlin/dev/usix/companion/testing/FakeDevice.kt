package dev.usix.companion.testing

import dev.usix.companion.application.AppLauncherPort
import dev.usix.companion.application.DeviceApplication
import dev.usix.companion.application.DeviceStatePort
import dev.usix.companion.application.NotificationPort
import dev.usix.companion.application.ScreenObservationPort
import dev.usix.companion.application.UiActionPort
import dev.usix.companion.domain.MailDraft
import dev.usix.companion.domain.NotificationInfo
import dev.usix.companion.domain.NotificationRef
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode

/** Explicit per-test state; never included in an APK production dependency. */
class FakeDevice : DeviceStatePort, ScreenObservationPort, UiActionPort, AppLauncherPort, NotificationPort {
    var accessibility = true
    var listener = false
    var accept = true
    var nodes = emptyList<ScreenNode>()
    var items = emptyList<NotificationInfo>()
    val calls = mutableListOf<String>()
    var draft: MailDraft? = null
    var selectedPackage: PackageId? = null
    var tapHandler: (suspend () -> Boolean)? = null

    fun application() = DeviceApplication(this, this, this, this, this)
    override fun accessibilityConnected() = accessibility
    override fun connected() = listener
    override suspend fun snapshot() = items
    override suspend fun observe(packageId: PackageId?): List<ScreenNode> {
        calls.add("screen")
        selectedPackage = packageId
        return nodes
    }
    override suspend fun tap(x: Int, y: Int): Boolean {
        calls.add("tap:$x,$y")
        return tapHandler?.invoke() ?: accept
    }
    override suspend fun type(text: String, packageId: PackageId?): Boolean {
        calls.add("type:$text")
        selectedPackage = packageId
        return accept
    }
    override suspend fun back(): Boolean { calls.add("back"); return accept }
    override suspend fun scroll(packageId: PackageId?, forward: Boolean): Boolean {
        calls.add("scroll:$forward")
        selectedPackage = packageId
        return accept
    }
    override suspend fun open(packageId: PackageId): Boolean {
        calls.add("open")
        selectedPackage = packageId
        return accept
    }
    override suspend fun compose(draft: MailDraft): Boolean {
        calls.add("compose")
        this.draft = draft
        return accept
    }
    override suspend fun reply(reference: NotificationRef, text: String): Boolean {
        calls.add("reply:${reference.value}:$text")
        return accept
    }
}
