package app.splitevenly.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * Tracks the foreground [ComponentActivity] so platform actuals that must launch from an Activity —
 * [FilePicker] via `ActivityResultContracts`, and later the auth surfaces (06 §5.5) — can reach it
 * without the UI layer threading an Activity reference through every call site.
 *
 * The reference is **weak**: the tracker never keeps a destroyed Activity alive, and it is cleared on
 * pause so a backgrounded Activity isn't used to present UI. Install once from `Application.onCreate`
 * via [installActivityTracking].
 */
object CurrentActivity {
    private var ref: WeakReference<ComponentActivity>? = null

    fun get(): ComponentActivity? = ref?.get()

    internal fun set(activity: ComponentActivity?) {
        ref = activity?.let { WeakReference(it) }
    }
}

/** Wire [CurrentActivity] to the process's Activity lifecycle. Call once from `Application.onCreate`. */
fun installActivityTracking(application: Application) {
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            (activity as? ComponentActivity)?.let { CurrentActivity.set(it) }
        }

        override fun onActivityPaused(activity: Activity) {
            if (CurrentActivity.get() === activity) CurrentActivity.set(null)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
}
