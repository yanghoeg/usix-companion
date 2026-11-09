package dev.usix.companion.adapters.android

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import dev.usix.companion.application.ExecutionReadiness
import dev.usix.companion.domain.ExecutionError
import dev.usix.companion.domain.ExecutionErrorCode

class AndroidExecutionReadiness(private val context: Context, private val accessibility: ConnectedAccessibilityAdapter? = null,
    private val launcher: AndroidAppLauncher? = null) : ExecutionReadiness {
    override fun rejection(operation: String, packageId: String?): ExecutionError? {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return ExecutionError(ExecutionErrorCode.DeviceLocked, "Unlock the device before observing or acting on an app")
        if (operation.startsWith("ui.") && accessibility?.accessibilityConnected() != true)
            return ExecutionError(ExecutionErrorCode.AccessibilityDisconnected, "Enable and connect Companion accessibility")
        if (packageId == null || context.packageManager.getLaunchIntentForPackage(packageId) == null)
            return ExecutionError(ExecutionErrorCode.AppMissing, "Selected package has no available launch activity")
        if (operation == "app.open") launcher?.launchRejection()?.let {
            return ExecutionError(ExecutionErrorCode.UnsupportedCapability, it.message)
        }
        return null
    }
}
