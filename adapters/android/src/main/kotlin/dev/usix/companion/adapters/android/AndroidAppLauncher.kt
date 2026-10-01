package dev.usix.companion.adapters.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.usix.companion.application.AppLauncherPort
import dev.usix.companion.domain.MailDraft
import dev.usix.companion.domain.PackageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AndroidAppLauncher(private val context: Context, private val mainDispatcher: CoroutineDispatcher) : AppLauncherPort {
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

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
