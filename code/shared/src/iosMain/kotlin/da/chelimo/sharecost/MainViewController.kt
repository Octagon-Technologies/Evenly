package da.chelimo.sharecost

import androidx.compose.ui.window.ComposeUIViewController
import da.chelimo.sharecost.di.initKoin
import da.chelimo.sharecost.platform.setupAnalytics
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.samuolis.posthog.PostHogContext
import kotlin.native.Platform
import org.koin.mp.KoinPlatform
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun MainViewController(): UIViewController {
    // PostHog must be set up before Koin so PostHogAnalytics (bound in platformModule) can call
    // PostHog.* immediately after Koin finishes starting.
    setupAnalytics(PostHogContext(), debug = Platform.isDebugBinary)
    initKoin()
    return ComposeUIViewController { App() }
}

/**
 * Forward an OAuth / magic-link redirect (`sharecost://login-callback`) to Supabase Auth so the
 * session completes. Call from Swift `iOSApp`'s `.onOpenURL { MainViewControllerKt.handleAuthDeeplink(url: $0) }`.
 * No-op when Supabase isn't configured (the client is absent from Koin). Uses [KoinPlatform] rather
 * than `GlobalContext`, which isn't available on Kotlin/Native.
 */
fun handleAuthDeeplink(url: NSURL) {
    val client = runCatching { KoinPlatform.getKoin().getOrNull<SupabaseClient>() }.getOrNull() ?: return
    client.handleDeeplinks(url)
}
