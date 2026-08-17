package app.splitevenly.platform

import android.app.Application
import android.os.Build

/**
 * Context-free, via `ActivityThread.currentApplication()` — the same trick [isDebugBuild] uses, and for
 * the same reason: this is read from a repository that has no `Context` and adding one to the Koin
 * graph for a version string is not worth the coupling. Returns `""` on any failure, including the
 * plain-JVM unit tests where `ActivityThread` does not exist.
 */
actual fun appVersionLabel(): String =
    runCatching {
        val app =
            Class
                .forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application ?: return@runCatching ""
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        val code =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
        "${pkg.versionName} ($code)"
    }.getOrDefault("")
