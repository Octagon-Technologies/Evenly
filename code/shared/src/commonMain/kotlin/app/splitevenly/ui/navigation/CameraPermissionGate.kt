package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.splitevenly.platform.AppForeground
import app.splitevenly.platform.CameraPermission
import app.splitevenly.platform.CameraPermissionStatus
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.UrlOpener
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.CameraPermissionSheet
import app.splitevenly.ui.components.CameraPromptKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The gate every camera entry point goes through.
 *
 * Wrap a source handler with [wrap] and it keeps working exactly as before for Photos and Files. Only
 * [PickSource.Camera] is intercepted, and only when the OS has something to say: never-asked opens the
 * primer, refused opens the recovery. Granted (and Android, which has no grant) falls straight through, so
 * the common case costs one status read and no UI.
 *
 * Why a wrapper rather than a check at each call site: there are four camera entry points across two route
 * files, and the failure mode of missing one is invisible in code review and very visible on a device.
 */
@Stable
class CameraPermissionGate internal constructor(
    private val permission: CameraPermission,
    private val urls: UrlOpener,
    private val scope: CoroutineScope,
) {
    internal var showing by mutableStateOf<CameraPromptKind?>(null)
        private set
    internal var settingsFailed by mutableStateOf(false)
        private set

    private var pending: ((PickSource) -> Unit)? = null

    /** Returns [handler] guarded by the camera check. Safe to call on every recomposition. */
    fun wrap(handler: (PickSource) -> Unit): (PickSource) -> Unit = { source ->
        if (source != PickSource.Camera) {
            handler(source)
        } else {
            scope.launch {
                when (permission.status()) {
                    CameraPermissionStatus.Granted, CameraPermissionStatus.NotApplicable -> handler(source)
                    CameraPermissionStatus.NotDetermined -> open(CameraPromptKind.Primer, handler)
                    CameraPermissionStatus.Denied -> open(CameraPromptKind.Blocked, handler)
                }
            }
        }
    }

    private fun open(kind: CameraPromptKind, handler: (PickSource) -> Unit) {
        pending = handler
        settingsFailed = false
        showing = kind
    }

    internal fun allow() {
        val handler = pending
        scope.launch {
            val granted = permission.request()
            dismiss()
            // A refusal closes the sheet and says nothing more. The user answered the question they were
            // shown; re-explaining on the spot would be nagging. The recovery half is what they get the
            // next time they reach for the camera themselves.
            if (granted) handler?.invoke(PickSource.Camera)
        }
    }

    internal fun openSettings() {
        // Deliberately leaves the sheet up. The user is coming back, and on return the foreground watcher
        // below re-checks and carries them into the camera they were after.
        settingsFailed = !urls.openAppSettings()
    }

    internal fun usePhotos() {
        val handler = pending
        dismiss()
        handler?.invoke(PickSource.Photos)
    }

    internal fun dismiss() {
        showing = null
        settingsFailed = false
        pending = null
    }

    /** Called when the app comes back on screen with the recovery sheet still up. */
    internal fun recheckAfterSettings() {
        val handler = pending
        scope.launch {
            if (permission.status() == CameraPermissionStatus.Granted) {
                dismiss()
                handler?.invoke(PickSource.Camera)
            }
        }
    }
}

/**
 * The gate's state. Pair it with [CameraPermissionGateHost] at the **end** of the same route wrapper,
 * where `PassSheet` and `ProPaywallHost` already sit: an overlay emitted before the screen is drawn
 * underneath it.
 */
@Composable
fun rememberCameraPermissionGate(): CameraPermissionGate {
    val permission = koinInject<CameraPermission>()
    val urls = koinInject<UrlOpener>()
    val scope = rememberCoroutineScope()
    return remember(permission, urls, scope) { CameraPermissionGate(permission, urls, scope) }
}

/** Renders whichever half of the gate is open. Emit last in the route, so it lands over the screen. */
@Composable
fun CameraPermissionGateHost(gate: CameraPermissionGate) {
    val foreground = koinInject<AppForeground>()

    // Coming back from Settings is the only way the answer changes while the recovery is up, and landing
    // back on the sheet that sent you there reads as if the trip failed.
    LaunchedEffect(gate.showing) {
        if (gate.showing == CameraPromptKind.Blocked) {
            // Both halves matter: the flow is already true when the sheet opens, so waiting only for true
            // would resolve instantly and then never see the actual return.
            foreground.state.first { !it }
            foreground.state.first { it }
            gate.recheckAfterSettings()
        }
    }

    gate.showing?.let { kind ->
        CameraPermissionSheet(
            kind = kind,
            photosLabel = if (isIOS()) "Select receipt from Photos" else "Select receipt from Gallery",
            onAllow = gate::allow,
            onOpenSettings = gate::openSettings,
            onUsePhotos = gate::usePhotos,
            onDismiss = gate::dismiss,
            settingsFailed = gate.settingsFailed,
        )
    }
}
