package da.chelimo.sharecost.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Android [FilePicker] (06 §5.1) over `ActivityResultContracts.OpenDocument`. The launcher is registered
 * dynamically against the foreground [ComponentActivity] (via [activityProvider]) using the no-lifecycle
 * `register` overload, and unregistered as soon as the result arrives, so there is no Activity to keep
 * alive between picks. Bytes are read through the `ContentResolver` for the returned `content://` URI.
 */
actual class FilePicker(private val activityProvider: () -> ComponentActivity?) {

    actual suspend fun pick(kind: PickKind): AppResult<PickedFile?> =
        suspendCancellableCoroutine { cont ->
            val activity = activityProvider()
            if (activity == null) {
                cont.resume(AppResult.Err(AppError.Unexpected(IllegalStateException("No foreground activity to launch the file picker"))))
                return@suspendCancellableCoroutine
            }

            val key = "sharecost_filepicker_${counter.incrementAndGet()}"
            var launcher: ActivityResultLauncher<Array<String>>? = null
            launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                launcher?.unregister()
                cont.resume(readPickedFile(activity, uri))
            }

            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(mimeTypesFor(kind))
        }

    private fun readPickedFile(context: Context, uri: Uri?): AppResult<PickedFile?> {
        if (uri == null) return AppResult.Ok(null) // user cancelled
        return try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return AppResult.Err(AppError.Unexpected(IllegalStateException("Could not open picked file")))
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = displayName(context, uri) ?: uri.lastPathSegment ?: "file"
            AppResult.Ok(PickedFile(name = name, mimeType = mime, bytes = bytes))
        } catch (e: Throwable) {
            AppResult.Err(AppError.Unexpected(e))
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun mimeTypesFor(kind: PickKind): Array<String> = when (kind) {
        PickKind.Image -> arrayOf("image/*")
        PickKind.Pdf -> arrayOf("application/pdf")
        PickKind.ImageOrPdf -> arrayOf("image/*", "application/pdf")
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
