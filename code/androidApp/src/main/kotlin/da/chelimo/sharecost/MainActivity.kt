package da.chelimo.sharecost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.data.remote.supabase.handleAuthDeeplink

class MainActivity : ComponentActivity() {

    // Notification permission result is ignored — the FCM token still registers either way (F7).
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Complete an OAuth / magic-link sign-in if we were launched from the redirect.
        handleAuthDeeplink(intent)
        maybeRequestNotificationPermission()

        setContent {
            App()
        }
    }

    /** Android 13+ requires a runtime grant for POST_NOTIFICATIONS before notifications display (F7). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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