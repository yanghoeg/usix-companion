package dev.usix.companion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Uses the account already signed in to Thunderbird. Opening a composer never sends mail. */
object EmailController {
    const val THUNDERBIRD_PACKAGE = "net.thunderbird.android"

    fun open(pkg: String): Boolean = try {
        UiController.openApp(pkg)
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun compose(to: String, subject: String, body: String, pkg: String): Boolean {
        val ctx = NotifStore.appContext ?: return false
        // Encode each value separately; query delimiters in the body must not add recipients.
        val uri = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
