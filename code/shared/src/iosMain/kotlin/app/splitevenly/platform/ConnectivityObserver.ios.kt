@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * iOS [ConnectivityObserver] (06 §5.7) over `NWPathMonitor`. The monitor fires its update handler once
 * immediately with the current path and again on every change, so collectors get the present status
 * without waiting — matching the Android contract. A path counts as [NetworkStatus.Online] only when its
 * status is `satisfied` (the OS deems it usable for new connections).
 */
actual class ConnectivityObserver {

    actual val status: Flow<NetworkStatus> = callbackFlow {
        val monitor = nw_path_monitor_create()
        val queue = dispatch_queue_create("app.splitevenly.connectivity", null)

        nw_path_monitor_set_update_handler(monitor) { path ->
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            trySend(if (online) NetworkStatus.Online else NetworkStatus.Offline)
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)

        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()
}
