package da.chelimo.sharecost

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import da.chelimo.sharecost.ui.navigation.ShareCostNavHost
import da.chelimo.sharecost.ui.theme.ShareCostTheme

@Composable
@Preview
fun App() {
    // Coil's singleton loader needs the Ktor network fetcher registered explicitly on Kotlin/Native
    // (no ServiceLoader on iOS), so receipt URLs (F5) render the same on Android and iOS.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }
    ShareCostTheme {
        ShareCostNavHost()
    }
}
