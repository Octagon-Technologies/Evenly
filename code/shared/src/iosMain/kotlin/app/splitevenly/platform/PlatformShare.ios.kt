package app.splitevenly.platform

import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * iOS [PlatformShare]. Presents `UIActivityViewController` from the top view controller (same lookup as
 * [FilePicker]). Evenly is phone-first, so this presents as a sheet — no iPad popover anchor is
 * configured. Call on the main thread (it touches `UIApplication`).
 */
actual class PlatformShare {

    actual fun shareText(text: String, subject: String?) {
        val presenter = topViewController() ?: return
        val activityVC = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        presenter.presentViewController(activityVC, animated = true, completion = null)
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
