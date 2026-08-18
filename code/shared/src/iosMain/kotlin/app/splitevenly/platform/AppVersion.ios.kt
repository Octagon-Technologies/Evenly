package app.splitevenly.platform

import platform.Foundation.NSBundle

/**
 * `CFBundleShortVersionString` is the marketing version ("1.4.0"); `CFBundleVersion` is the build
 * number App Store Connect increments. Both are needed: two TestFlight builds share a marketing
 * version, and "which build" is exactly the question a bug report has to answer.
 */
actual fun appVersionLabel(): String {
    val info = NSBundle.mainBundle.infoDictionary ?: return ""
    val short = info["CFBundleShortVersionString"] as? String
    val build = info["CFBundleVersion"] as? String
    return when {
        short != null && build != null -> "$short ($build)"
        short != null -> short
        else -> ""
    }
}
