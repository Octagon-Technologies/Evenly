package app.splitevenly.platform

import kotlinx.coroutines.flow.Flow

/** Coarse reachability state (06 §5.7). The sync engine (S-1) flushes the mutation queue on [Online]. */
enum class NetworkStatus { Online, Offline }

/**
 * E-2 — observe connectivity as a cold [Flow] (06 §5.7). **Android**: `ConnectivityManager`'s
 * `NetworkCallback` bridged through `callbackFlow`. **iOS**: `NWPathMonitor`. Both seed the current
 * status on collection and emit only on change (`distinctUntilChanged`), so a fresh collector always
 * learns the present state without waiting for a transition.
 *
 * Constructor is platform-specific (Android needs a `Context`) — instances come from `platformModule()`.
 */
expect class ConnectivityObserver {
    /** Emits the current [NetworkStatus] on collection, then on every subsequent change. */
    val status: Flow<NetworkStatus>
}
