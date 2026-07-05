package da.chelimo.sharecost.platform

import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * iOS [PlatformShare]. Presents `UIActivityViewController` from the top view controller (same lookup as
 * [FilePicker]). ShareCost is phone-first, so this presents as a sheet — no iPad popover anchor is
 * configured. Call on the main thread (it touches `UIApplication`).
 */
actual class PlatformShare {

    actual fun shareText(text: String, subject: String?) {
        val presenter = topViewController() ?: return
        val activityVC = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
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
