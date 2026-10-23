package app.splitevenly.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.splitevenly.core.log.Log

/**
 * Android [UrlOpener] (06 §5.6). Launched from the application `Context`, so the intent needs
 * `FLAG_ACTIVITY_NEW_TASK` (there is no Activity task to inherit). `ActivityNotFoundException` — no app
 * registered for the scheme (e.g. Venmo not installed) — is the expected "can't handle" signal and
 * resolves to `false` so the caller can fall back (03 §5.2).
 */
actual class UrlOpener(context: Context) {

    private val appContext = context.applicationContext

    actual fun open(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("UrlOpener: no handler for url scheme")
        false
    }

    actual fun openAppSettings(): Boolean = try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        appContext.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("UrlOpener: no app-details settings activity")
        false
    }
}
