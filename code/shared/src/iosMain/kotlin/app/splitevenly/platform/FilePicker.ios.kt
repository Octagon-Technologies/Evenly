@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.splitevenly.platform

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS [FilePicker] (06 §5.1). [PickSource.Photos] → `PHPickerViewController` (multi-select images),
 * [PickSource.Files] → `UIDocumentPickerViewController` (multi-select, security-scoped reads),
 * [PickSource.Camera] → `UIImagePickerController`. All UIKit work hops to [Dispatchers.Main]; the active
 * delegate is held strongly for the (single, suspended) pick so ARC doesn't drop it before the callback.
 */
actual class FilePicker {

    private var activeDelegate: NSObject? = null

    actual suspend fun pick(source: PickSource, kind: PickKind): AppResult<List<PickedFile>> =
        withContext(Dispatchers.Main) {
            val presenter = topViewController()
                ?: return@withContext AppResult.Err(AppError.Unexpected(IllegalStateException("No view controller to present the picker")))
            when (source) {
                PickSource.Photos -> pickPhotos(presenter)
                PickSource.Files -> pickFiles(presenter, kind)
                PickSource.Camera -> capture(presenter)
            }
        }

    private suspend fun pickPhotos(presenter: UIViewController): AppResult<List<PickedFile>> =
        suspendCancellableCoroutine { cont ->
            val config = PHPickerConfiguration().apply {
                selectionLimit = 0 // 0 = unlimited (multi-select)
                filter = PHPickerFilter.imagesFilter()
            }
            val delegate = PhotoPickerDelegate { files ->
                activeDelegate = null
                cont.resume(AppResult.Ok(files))
            }
            activeDelegate = delegate
            val picker = PHPickerViewController(configuration = config)
            picker.delegate = delegate
            cont.invokeOnCancellation { activeDelegate = null }
            presenter.presentViewController(picker, animated = true, completion = null)
        }

    private suspend fun pickFiles(presenter: UIViewController, kind: PickKind): AppResult<List<PickedFile>> =
        suspendCancellableCoroutine { cont ->
            val delegate = DocumentPickerDelegate { urls ->
                activeDelegate = null
                cont.resume(AppResult.Ok(urls.mapNotNull { readPickedFile(it) }))
            }
            activeDelegate = delegate
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypesFor(kind))
            picker.delegate = delegate
            picker.allowsMultipleSelection = true
            cont.invokeOnCancellation { activeDelegate = null }
            presenter.presentViewController(picker, animated = true, completion = null)
        }

    private suspend fun capture(presenter: UIViewController): AppResult<List<PickedFile>> =
        suspendCancellableCoroutine { cont ->
            if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) {
                cont.resume(AppResult.Ok(emptyList())) // no camera (e.g. simulator)
                return@suspendCancellableCoroutine
            }
            val picker = UIImagePickerController()
            val delegate = CameraDelegate { file ->
                activeDelegate = null
                cont.resume(AppResult.Ok(listOfNotNull(file)))
            }
            activeDelegate = delegate
            picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            picker.setDelegate(delegate)
            cont.invokeOnCancellation { activeDelegate = null }
            presenter.presentViewController(picker, animated = true, completion = null)
        }

    private fun readPickedFile(url: NSURL): PickedFile? {
        val scoped = url.startAccessingSecurityScopedResource()
        return try {
            val data = NSData.dataWithContentsOfURL(url) ?: return null
            PickedFile(
                name = url.lastPathComponent ?: "file",
                mimeType = mimeForExtension(url.pathExtension),
                bytes = data.toByteArray(),
            )
        } catch (e: Throwable) {
            null
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

/**
 * PHPicker delegate. Each picked asset's bytes load asynchronously; we fan them in on the main queue
 * (serialized) and resume once every load has finished. Images come back in their native type — the
 * [ImageProcessor] re-encodes to JPEG on upload anyway, so the mime is reported as image/jpeg.
 */
private class PhotoPickerDelegate(
    private val onResult: (List<PickedFile>) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    @Suppress("UNCHECKED_CAST")
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val results = didFinishPicking as List<PHPickerResult>
        if (results.isEmpty()) {
            onResult(emptyList())
            return
        }
        val collected = mutableListOf<PickedFile>()
        var remaining = results.size
        results.forEachIndexed { index, result ->
            val provider: NSItemProvider = result.itemProvider
            provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data: NSData?, _: NSError? ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (data != null) {
                        collected.add(PickedFile("photo_$index.jpg", "image/jpeg", data.toByteArray()))
                    }
                    remaining -= 1
                    if (remaining == 0) onResult(collected.toList())
                }
            }
        }
    }
}

/** Document-picker delegate bridging multi-select pick/cancel to a single [onResult]. */
private class DocumentPickerDelegate(
    private val onResult: (List<NSURL>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    @Suppress("UNCHECKED_CAST")
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onResult(didPickDocumentsAtURLs as List<NSURL>)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(emptyList())
    }
}

/** Camera delegate: one captured photo → JPEG bytes, dismissing the controller on finish/cancel. */
private class CameraDelegate(
    private val onResult: (PickedFile?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val data = image?.let { UIImageJPEGRepresentation(it, 0.9) }
        onResult(data?.let { PickedFile("camera.jpg", "image/jpeg", it.toByteArray()) })
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onResult(null)
    }
}
