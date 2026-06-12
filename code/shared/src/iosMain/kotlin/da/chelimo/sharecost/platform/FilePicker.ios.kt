@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package da.chelimo.sharecost.platform

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [FilePicker] (06 §5.1) over `UIDocumentPickerViewController` (open-in-place). The picker is presented
 * on the top-most view controller and constrained to [PickKind]'s content types; its delegate resumes the
 * coroutine with the chosen file or `Ok(null)` on cancel. Bytes are read from the security-scoped URL
 * (`start/stopAccessingSecurityScopedResource`). All UIKit work hops to [Dispatchers.Main].
 *
 * The document picker reaches the Photos library too (via the Files "Photos" provider), so it covers the
 * image case without a separate `PHPickerViewController`; richer photo-library UX can be layered on later.
 */
actual class FilePicker {

    // UIDocumentPickerViewController.delegate is a weak reference — hold the delegate strongly here for the
    // lifetime of the (single, suspended) pick so ARC doesn't deallocate it before the callback fires.
    private var activeDelegate: NSObject? = null

    actual suspend fun pick(kind: PickKind): AppResult<PickedFile?> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val presenter = topViewController()
            if (presenter == null) {
                cont.resume(AppResult.Err(AppError.Unexpected(IllegalStateException("No view controller to present the file picker"))))
                return@suspendCancellableCoroutine
            }

            val delegate = PickerDelegate { url ->
                activeDelegate = null
                cont.resume(readPickedFile(url))
            }
            activeDelegate = delegate

            val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypesFor(kind))
            picker.delegate = delegate
            picker.allowsMultipleSelection = false

            cont.invokeOnCancellation { activeDelegate = null }
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    private fun readPickedFile(url: NSURL?): AppResult<PickedFile?> {
        if (url == null) return AppResult.Ok(null) // cancelled / nothing chosen
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = NSData.dataWithContentsOfURL(url)
                ?: return AppResult.Err(AppError.Unexpected(IllegalStateException("Could not read picked file")))
            val name = url.lastPathComponent ?: "file"
            val mime = mimeForExtension(url.pathExtension)
            return AppResult.Ok(PickedFile(name = name, mimeType = mime, bytes = data.toByteArray()))
        } catch (e: Throwable) {
            return AppResult.Err(AppError.Unexpected(e))
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun contentTypesFor(kind: PickKind): List<UTType> = when (kind) {
        PickKind.Image -> listOf(UTTypeImage)
        PickKind.Pdf -> listOf(UTTypePDF)
        PickKind.ImageOrPdf -> listOf(UTTypeImage, UTTypePDF)
    }

    private fun mimeForExtension(extension: String?): String = when (extension?.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun topViewController(): UIViewController? {
        val application = UIApplication.sharedApplication
        var controller = (application.keyWindow ?: application.windows.firstOrNull() as? UIWindow)?.rootViewController
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }
}

/** Obj-C delegate bridging the document picker's pick/cancel callbacks to a single [onResult] call. */
private class PickerDelegate(
    private val onResult: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onResult(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}
