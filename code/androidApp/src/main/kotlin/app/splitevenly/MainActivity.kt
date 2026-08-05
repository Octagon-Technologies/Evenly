package app.splitevenly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.splitevenly.data.remote.supabase.handleAuthDeeplink

/**
 * The POST_NOTIFICATIONS grant is deliberately NOT requested here. Android gives one prompt per install
 * and never re-asks, so firing it from onCreate spent it on a user who hadn't signed in, had no group,
 * and had been told nothing — and everyone who declined was unreachable for good. The ask now lives on
 * the onboarding "Stay in the loop" step, which explains what the notifications are for first
 * (`platform/NotificationPermission`).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: it swaps the splash theme out for Theme.Evenly. The system
        // splash is left to dismiss on the first frame rather than being held here, because Compose
        // immediately draws the same blue-and-feather SplashScreen while the start destination
        // resolves, so the user sees one continuous brand screen, not two.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Complete an OAuth / magic-link sign-in if we were launched from the redirect.
        handleAuthDeeplink(intent)

        setContent {
            App()
        }
    }

    // singleTop launch: redirects arrive here while the app is already running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeeplink(intent)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}