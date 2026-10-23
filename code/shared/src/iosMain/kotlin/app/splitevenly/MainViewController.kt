package app.splitevenly

import androidx.compose.ui.window.ComposeUIViewController
import app.splitevenly.di.initKoin
import app.splitevenly.platform.setupAnalytics
import app.splitevenly.ui.navigation.PendingInvite
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.samuolis.posthog.PostHogContext
import org.koin.mp.KoinPlatform
import platform.Foundation.NSURL
import platform.UIKit.UIViewController
import kotlin.native.Platform

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun MainViewController(): UIViewController {
    // PostHog must be set up before Koin so PostHogAnalytics (bound in platformModule) can call
    // PostHog.* immediately after Koin finishes starting.
    setupAnalytics(PostHogContext(), debug = Platform.isDebugBinary)
    initKoin()
    return ComposeUIViewController { App() }
}

/**
 * Forward an OAuth / magic-link redirect (`splitevenly://login-callback`) to Supabase Auth so the
 * session completes. Call from Swift `iOSApp`'s `.onOpenURL { MainViewControllerKt.handleAuthDeeplink(url: $0) }`.
 * No-op when Supabase isn't configured (the client is absent from Koin). Uses [KoinPlatform] rather
 * than `GlobalContext`, which isn't available on Kotlin/Native.
 */
fun handleAuthDeeplink(url: NSURL) {
    val client = runCatching { KoinPlatform.getKoin().getOrNull<SupabaseClient>() }.getOrNull() ?: return
    client.handleDeeplinks(url)
}

/**
 * Queue an invite link for the nav host. Call from Swift `.onOpenURL`, which receives both universal
 * links and `splitevenly://` URLs; anything that is not an invite is ignored.
 */
fun handleInviteLink(url: NSURL) {
    PendingInvite.offer(url.absoluteString)
}
