package app.splitevenly.data.remote

import app.splitevenly.data.remote.supabase.ServerClockPlugin
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared Ktor client config applied by every platform engine (OkHttp on Android, Darwin on iOS).
 * `ignoreUnknownKeys` keeps deserialization resilient to fields a server adds later (04 §9).
 */
fun HttpClientConfig<*>.installEvenlyDefaults() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
    // Reads the `Date` header off responses we were already making, so the client can clamp its own
    // `updated_at` stamps to the server's clock the way `_clamp_client_ts` clamps them server-side.
    install(ServerClockPlugin)
}
