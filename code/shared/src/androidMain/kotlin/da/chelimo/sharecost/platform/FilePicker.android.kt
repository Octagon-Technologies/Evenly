package da.chelimo.sharecost.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Android [FilePicker] (06 §5.1). Each [PickSource] maps to its modern contract: the system Photo Picker
 * ([ActivityResultContracts.PickMultipleVisualMedia], multi-select images), [ActivityResultContracts.OpenMultipleDocuments]
 * (multi-select files/PDFs), and [ActivityResultContracts.TakePicture] (camera into a [FileProvider] temp
 * file). Launchers are registered dynamically against the foreground activity and unregistered on result.
 */
actual class FilePicker(private val activityProvider: () -> ComponentActivity?) {

    actual suspend fun pick(source: PickSource, kind: PickKind): AppResult<List<PickedFile>> {
        val activity = activityProvider()
            ?: return AppResult.Err(AppError.Unexpected(IllegalStateException("No foreground activity to launch the file picker")))
        return try {
            when (source) {
                PickSource.Photos -> {
                    val uris = launch(
                        activity,
                        ActivityResultContracts.PickMultipleVisualMedia(),
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                    AppResult.Ok(uris.mapNotNull { readUri(activity, it) })
                }
                PickSource.Files -> {
                    val uris = launch(activity, ActivityResultContracts.OpenMultipleDocuments(), mimeTypesFor(kind))
                    AppResult.Ok(uris.mapNotNull { readUri(activity, it) })
                }
                PickSource.Camera -> capture(activity)
            }
        } catch (e: Throwable) {
            AppResult.Err(AppError.Unexpected(e))
        }
    }

    /** Register a one-shot launcher for [contract], fire it with [input], and suspend for the result. */
    private suspend fun <I, O> launch(
        activity: ComponentActivity,
        contract: ActivityResultContract<I, O>,
        input: I,
    ): O = suspendCancellableCoroutine { cont ->
        val key = "sharecost_picker_${counter.incrementAndGet()}"
        var launcher: ActivityResultLauncher<I>? = null
        launcher = activity.activityResultRegistry.register(key, contract) { result ->
            launcher?.unregister()
            cont.resume(result)
        }
        cont.invokeOnCancellation { launcher.unregister() }
        launcher.launch(input)
    }

    /** Camera capture into an app-owned temp file shared via [FileProvider]; one image back. */
    private suspend fun capture(activity: ComponentActivity): AppResult<List<PickedFile>> {
        val file = File(activity.cacheDir, "camera_${counter.incrementAndGet()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val taken = launch(activity, ActivityResultContracts.TakePicture(), uri)
        if (!taken || !file.exists() || file.length() == 0L) {
            file.delete()
            return AppResult.Ok(emptyList()) // user backed out, or no photo captured
        }
        val bytes = file.readBytes()
        file.delete()
        return AppResult.Ok(listOf(PickedFile(name = file.name, mimeType = "image/jpeg", bytes = bytes)))
    }

    private fun readUri(context: Context, uri: Uri): PickedFile? = try {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "file"
        PickedFile(name = name, mimeType = mime, bytes = bytes)
    } catch (e: Throwable) {
        null // skip an unreadable item rather than failing the whole batch
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun mimeTypesFor(kind: PickKind): Array<String> = when (kind) {
        PickKind.Image -> arrayOf("image/*")
        PickKind.Pdf -> arrayOf("application/pdf")
        PickKind.ImageOrPdf -> arrayOf("image/*", "application/pdf")
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
