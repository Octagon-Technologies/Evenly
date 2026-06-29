package da.chelimo.sharecost

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import da.chelimo.sharecost.domain.auth.ThemeMode
import da.chelimo.sharecost.platform.imageCacheDir
import da.chelimo.sharecost.domain.repository.ProfileRepository
import da.chelimo.sharecost.ui.navigation.ShareCostNavHost
import da.chelimo.sharecost.ui.theme.ShareCostTheme
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
    // Appearance follows the signed-in user's saved preference (Profile → Appearance); System honours the OS.
    val profile by koinInject<ProfileRepository>().observeProfile().collectAsStateWithLifecycle(null)
    val darkTheme = when (profile?.themeMode ?: ThemeMode.System) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    ShareCostTheme(darkTheme = darkTheme) {
        ShareCostNavHost()
    }
}
