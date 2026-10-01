package dev.usix.companion.adapters.android

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import dev.usix.companion.application.ExecutionReadiness
import dev.usix.companion.domain.ExecutionError
import dev.usix.companion.domain.ExecutionErrorCode

class AndroidExecutionReadiness(private val context: Context) : ExecutionReadiness {
    override fun rejection(operation: String, packageId: String?): ExecutionError? {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return ExecutionError(ExecutionErrorCode.DeviceLocked, "Unlock the device before opening an app")
        if (packageId == null || context.packageManager.getLaunchIntentForPackage(packageId) == null)
            return ExecutionError(ExecutionErrorCode.AppMissing, "Selected package has no available launch activity")
        return null
    }
}
