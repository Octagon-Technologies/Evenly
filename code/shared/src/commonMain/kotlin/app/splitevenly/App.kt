package app.splitevenly

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import app.splitevenly.data.remote.revenuecat.RevenueCatBilling
import app.splitevenly.domain.auth.ThemeMode
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.platform.AppForeground
import app.splitevenly.platform.SecureStorage
import app.splitevenly.platform.imageCacheDir
import app.splitevenly.domain.repository.ProfileRepository
import app.splitevenly.ui.navigation.EvenlyNavHost
import app.splitevenly.ui.navigation.Route
import app.splitevenly.ui.navigation.WELCOME_SEEN_KEY
import app.splitevenly.ui.screen.SplashScreen
import app.splitevenly.ui.theme.EvenlyTheme
import org.koin.compose.koinInject

@Composable
@Preview
fun App() {
    // Coil's singleton loader needs the Ktor network fetcher registered explicitly on Kotlin/Native
    // (no ServiceLoader on iOS), so receipt URLs (F5) render the same on Android and iOS. A bounded
    // memory + disk cache means a receipt downloads once and re-renders from disk thereafter (no repeat
    // network) — the efficiency backbone of the in-app receipt viewer.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(imageCacheDir(context))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
    // Tell the sync driver whether we're on screen. Backgrounding drops the Realtime socket and stops
    // the sync loops (after a short grace); returning restarts them with a catch-up sync. STARTED is
    // the right threshold: a system alert or the app switcher leaves us STARTED, so those don't churn
    // the socket, while an actually-backgrounded app falls below it on both platforms.
    val appForeground = koinInject<AppForeground>()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, appForeground) {
        lifecycle.currentStateFlow.collect { appForeground.set(it.isAtLeast(Lifecycle.State.STARTED)) }
    }
    // Evenly Pro (PRO_PASS_SPEC.md §9). Idempotent, and a no-op with no RevenueCat keys, which is the
    // whole "unconfigured is inert" contract: no paywall, no pass sheet, scans exactly as today. Done
    // here rather than in a platform entry point so both hosts get it from one place.
    val proBilling = koinInject<ProBilling>()
    LaunchedEffect(proBilling) { (proBilling as? RevenueCatBilling)?.configure() }
    // Appearance follows the signed-in user's saved preference (Profile → Appearance); System honours the OS.
    val profile by koinInject<ProfileRepository>().observeProfile().collectAsStateWithLifecycle(null)
    val darkTheme = when (profile?.themeMode ?: ThemeMode.System) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // First launch (no "welcome_seen" flag) opens on the intro carousel; every launch after goes
    // straight to sign-in. Resolved off the device-local store before the NavHost composes, since
    // startDestination is locked in on first composition — null means "still reading" (the brand
    // splash holds, a blink at most), so we never flash sign-in and then jump back to Welcome.
    val storage = koinInject<SecureStorage>()
    val startDestination by produceState<Route?>(initialValue = null) {
        value = if (storage.contains(WELCOME_SEEN_KEY)) Route.SignIn else Route.Welcome
    }
    EvenlyTheme(darkTheme = darkTheme) {
        // Until the start destination resolves, hold the brand splash rather than a blank page. It
        // continues the blue the OS launch screen already painted, so the hand-off is seamless in
        // light and dark mode alike.
        startDestination?.let { EvenlyNavHost(startDestination = it) } ?: SplashScreen()
    }
}
