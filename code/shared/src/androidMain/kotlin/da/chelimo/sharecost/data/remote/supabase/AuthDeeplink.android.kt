package da.chelimo.sharecost.data.remote.supabase

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import org.koin.core.context.GlobalContext

/**
 * Forward an OAuth / magic-link redirect (`sharecost://login-callback`) to Supabase Auth, which parses
 * the tokens out of the URL and completes the session (surfacing through `AuthSession.currentUserId`).
 *
 * No-op when Supabase isn't configured — the [SupabaseClient] is simply absent from Koin. Call from
 * `MainActivity.onCreate` and `onNewIntent`.
 */
fun handleAuthDeeplink(intent: Intent) {
    val client = GlobalContext.getOrNull()?.getOrNull<SupabaseClient>() ?: return
    client.handleDeeplinks(intent)
}
