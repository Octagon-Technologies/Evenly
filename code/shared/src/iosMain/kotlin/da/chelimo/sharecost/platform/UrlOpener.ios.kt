package da.chelimo.sharecost.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS [UrlOpener] (06 §5.6). `canOpenURL` is the up-front can-handle probe (its `false` is our `false`),
 * then `openURL` actually launches it. For custom schemes (`venmo://`, `cashapp://`) `canOpenURL` only
 * returns `true` if the scheme is whitelisted in `Info.plist` `LSApplicationQueriesSchemes` — an `iosApp`
 * host concern (handled with U-7). Call on the main thread (it touches `UIApplication`).
 */
actual class UrlOpener {

    actual fun open(url: String): Boolean {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(nsUrl)) return false
        application.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return true
    }
}
