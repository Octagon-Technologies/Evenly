package app.splitevenly.platform

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun isIOS(): Boolean = false

// Context-free debuggable probe: read the running Application's FLAG_DEBUGGABLE via ActivityThread.
// Returns false on any failure (e.g. plain-JVM unit tests where ActivityThread isn't available).
actual fun isDebugBuild(): Boolean = runCatching {
    val app = Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication").invoke(null) as? Application
    app != null && (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}.getOrDefault(false)
