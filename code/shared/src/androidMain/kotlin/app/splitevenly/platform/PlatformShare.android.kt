package app.splitevenly.platform

import android.content.Context
import android.content.Intent

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
}
