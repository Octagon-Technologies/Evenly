package app.splitevenly.platform

import platform.Foundation.NSLocale
import platform.Foundation.currencyCode
import platform.Foundation.currentLocale
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi

class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun isIOS(): Boolean = true

@OptIn(ExperimentalNativeApi::class)
actual fun isDebugBuild(): Boolean = kotlin.native.Platform.isDebugBinary

actual fun isReduceMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()

actual fun deviceCurrencyCode(): String? = NSLocale.currentLocale.currencyCode
