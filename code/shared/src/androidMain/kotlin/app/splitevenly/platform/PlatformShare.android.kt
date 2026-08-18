package app.splitevenly.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android [PlatformShare]. Launched from the application `Context`, so both the send intent and the
 * chooser it wraps need `FLAG_ACTIVITY_NEW_TASK` (there is no Activity task to inherit — same reason as
 * [UrlOpener]).
 */
actual class PlatformShare(context: Context) {

    private val appContext = context.applicationContext

    actual fun shareText(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val chooser = Intent.createChooser(send, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(chooser)
    }

    actual fun shareFile(fileName: String, mimeType: String, content: String, subject: String?) {
        // cacheDir, because that is what the manifest's FileProvider `<cache-path>` already exposes.
        // Anywhere else needs a new path entry and silently throws IllegalArgumentException at share time.
        val file = File(appContext.cacheDir, fileName).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(appContext, "${'$'}{appContext.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            // The receiving app gets a one-shot read grant on this URI; without it every target fails
            // with a SecurityException it reports as "cannot open file".
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(chooser)
    }
}
