package app.splitevenly.platform

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import java.util.Currency
import java.util.Locale

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun isIOS(): Boolean = false

// Context-free debuggable probe: read the running Application's FLAG_DEBUGGABLE via ActivityThread.
// Returns false on any failure (e.g. plain-JVM unit tests where ActivityThread isn't available).
actual fun isDebugBuild(): Boolean =
    runCatching {
        val app = currentApplication()
        app != null && (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(false)

// "Remove animations" in Developer options / battery-saver drives this global scale to 0, which is the
// system's own signal for reduce-motion on Android (there is no separate accessibility flag for it).
actual fun isReduceMotionEnabled(): Boolean =
    runCatching {
        val app = currentApplication() ?: return false
        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

// Throws for a region-less locale ("en"), which is exactly the "unknown" answer the caller wants as null.
actual fun deviceCurrencyCode(): String? =
    runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()

private fun currentApplication(): Application? =
    runCatching {
        Class
            .forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()
