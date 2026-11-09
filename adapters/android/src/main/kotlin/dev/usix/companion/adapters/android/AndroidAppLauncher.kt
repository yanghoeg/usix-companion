package dev.usix.companion.adapters.android

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import dev.usix.companion.application.AppLauncherPort
import dev.usix.companion.domain.DeviceError
import dev.usix.companion.domain.DeviceErrorCode
import dev.usix.companion.domain.MailDraft
import dev.usix.companion.domain.PackageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AndroidAppLauncher(private val context: Context, private val mainDispatcher: CoroutineDispatcher) : AppLauncherPort {
    override fun launchRejection(): DeviceError? = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) null else
        DeviceError(DeviceErrorCode.COMPACT_WINDOW_UNAVAILABLE,
            "Compact app launch is unavailable; use a background data integration or an existing popup window")

    override suspend fun open(packageId: PackageId): Boolean = withContext(mainDispatcher) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId.value) ?: return@withContext false
        launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun compose(draft: MailDraft): Boolean = withContext(mainDispatcher) {
        val uri = Uri.parse("mailto:${Uri.encode(draft.to)}?subject=${Uri.encode(draft.subject)}&body=${Uri.encode(draft.body)}")
        launch(Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(draft.to))
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            setPackage(draft.packageId.value)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun launch(intent: Intent): Boolean {
        return try {
            if (launchRejection() != null) return false
            val manager = context.getSystemService(WindowManager::class.java) ?: return false
            val display = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds else Rect().also {
                @Suppress("DEPRECATION")
                manager.defaultDisplay.getRectSize(it)
            }
            if (display.width() < 2 || display.height() < 2) return false
            val width = minOf((360 * context.resources.displayMetrics.density).toInt(), display.width() * 3 / 5).coerceAtLeast(1)
            val height = minOf((540 * context.resources.displayMetrics.density).toInt(), display.height() * 3 / 5).coerceAtLeast(1)
            val bounds = Rect(display.right - width, display.top, display.right, display.top + height)
            val options = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
            // Bounds are a platform request, not evidence that the resulting window is compact.
            // A separate task avoids inheriting the caller's fullscreen task. Never retry without options.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), options)
            true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }
}
