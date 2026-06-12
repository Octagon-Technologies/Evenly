package da.chelimo.sharecost.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android [ConnectivityObserver] (06 §5.7) over `ConnectivityManager.NetworkCallback`. We track a
 * default network as "online" only once it reports `NET_CAPABILITY_VALIDATED` — i.e. the network can
 * actually reach the internet (captive portals and dead Wi-Fi report a network but no validation), which
 * is what the sync engine cares about. The current status is seeded on collection so a fresh collector
 * isn't left waiting for the first transition.
 */
actual class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    actual val status: Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(if (online) NetworkStatus.Online else NetworkStatus.Offline)
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(NetworkStatus.Offline)
            }
        }

        trySend(currentStatus())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .conflate()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun currentStatus(): NetworkStatus {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return if (online) NetworkStatus.Online else NetworkStatus.Offline
    }
}
