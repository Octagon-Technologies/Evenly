package app.splitevenly.platform

import objcnames.classes.LPLinkMetadata as ForwardLinkMetadata
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.LinkPresentation.LPLinkMetadata
import platform.UIKit.UIActivityItemSourceProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * iOS [PlatformShare]. Presents `UIActivityViewController` from the top view controller (same lookup as
 * [FilePicker]). Evenly is phone-first, so this presents as a sheet — no iPad popover anchor is
 * configured. Call on the main thread (it touches `UIApplication`).
 */
actual class PlatformShare {

    actual fun shareText(text: String, subject: String?, onShown: () -> Unit) {
        val presenter = topViewController() ?: return onShown()
        // The item carries its own link preview: handed a bare string with a URL in it, the sheet fetches
        // that page over the network to build its header, which added seconds before the header settled.
        val item = ShareTextItem(text, subject)
        val activityVC = UIActivityViewController(activityItems = listOf(item), applicationActivities = null)
        presenter.presentViewController(activityVC, animated = true, completion = onShown)
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    actual fun shareFile(fileName: String, mimeType: String, content: String, subject: String?) {
        val presenter = topViewController() ?: return
        // NSTemporaryDirectory, not Documents: this is a copy of the group's money data, and Documents is
        // user-visible in Files and included in iCloud backup. The OS reclaims temp on its own schedule.
        val path = NSTemporaryDirectory() + fileName
        val written = (content as NSString).writeToFile(
            path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        // A failed write must not present an activity sheet over a file that isn't there, which shows the
        // user a share sheet whose every target then fails.
        if (!written) return
        val url = NSURL.fileURLWithPath(path)
        val activityVC = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        presenter.presentViewController(activityVC, animated = true, completion = null)
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

/** A share-sheet text item that supplies the link preview itself, so the sheet never fetches the page. */
private class ShareTextItem(
    private val text: String,
    private val previewTitle: String?,
) : NSObject(), UIActivityItemSourceProtocol {
    override fun activityViewControllerPlaceholderItem(activityViewController: UIActivityViewController): Any = text

    override fun activityViewController(
        activityViewController: UIActivityViewController,
        itemForActivityType: String?,
    ): Any = text

    // UIKit only forward-declares LPLinkMetadata, so the protocol speaks the `objcnames` stand-in type; the
    // object is the real LinkPresentation class, so the cast is safe at runtime.
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun activityViewControllerLinkMetadata(activityViewController: UIActivityViewController): ForwardLinkMetadata? {
        val link = text.split(Regex("\\s+")).firstOrNull { it.startsWith("https://") }?.let { NSURL.URLWithString(it) }
        val metadata =
            LPLinkMetadata().apply {
                originalURL = link
                URL = link
                title = previewTitle ?: text.lineSequence().firstOrNull()
            }
        return metadata as ForwardLinkMetadata
    }
}
