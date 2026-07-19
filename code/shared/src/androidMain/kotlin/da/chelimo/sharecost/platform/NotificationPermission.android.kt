package da.chelimo.sharecost.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Android [NotificationPermission]. `POST_NOTIFICATIONS` is a runtime grant only on 13+ (TIRAMISU);
 * below that the manifest entry alone is enough, so [status] reports [NotificationPermissionStatus.NotApplicable]
 * and [request] succeeds without a dialog.
 *
 * The launcher is registered dynamically against the foreground activity and unregistered on result —
 * the same one-shot pattern as [FilePicker], which is what lets a plain (non-Activity) caller like the
 * onboarding route drive the prompt.
 */
actual class NotificationPermission(private val activityProvider: () -> ComponentActivity?) {

    actual suspend fun status(): NotificationPermissionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermissionStatus.NotApplicable
        val activity = activityProvider() ?: return NotificationPermissionStatus.NotDetermined
        if (granted(activity)) return NotificationPermissionStatus.Granted
        // Android can't distinguish "never asked" from "permanently denied": shouldShowRationale is true
        // only in the middle state (asked once, refused). Report that as Denied and everything else as
        // NotDetermined — over-reporting NotDetermined is safe, since requesting against a spent prompt
        // just resolves false without showing UI.
        return if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationPermissionStatus.Denied
        } else {
            NotificationPermissionStatus.NotDetermined
        }
    }

    actual suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val activity = activityProvider() ?: return false
        if (granted(activity)) return true
        return suspendCancellableCoroutine { cont ->
            val key = "sharecost_notif_permission_${counter.incrementAndGet()}"
            var launcher: ActivityResultLauncher<String>? = null
            launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                launcher?.unregister()
                cont.resume(isGranted)
            }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun granted(activity: ComponentActivity): Boolean =
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val counter = AtomicInteger(0)
    }
}
