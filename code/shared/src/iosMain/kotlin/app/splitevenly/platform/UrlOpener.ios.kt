package app.splitevenly.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

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

    // Deliberately not routed through [open]: canOpenURL reports false for app-prefs:, so the probe there
    // would reject the one URL iOS guarantees will work.
    actual fun openAppSettings(): Boolean {
        val settings = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
        UIApplication.sharedApplication.openURL(settings, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return true
    }
}
