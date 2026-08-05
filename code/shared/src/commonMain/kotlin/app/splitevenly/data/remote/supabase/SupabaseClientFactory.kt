package app.splitevenly.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Builds the shared [SupabaseClient] from [SupabaseConfig] with the plugins Evenly uses: Auth
 * (sign-in / session), Postgrest (the REST surface the sync engine reads + writes), and Storage
 * (receipt bytes, F5). Realtime is added in the sync-hardening phase (F7).
 *
 * The default serializer uses a **snake_case** naming strategy so the Room `@Entity` rows (camelCase
 * properties whose `@ColumnInfo` names are the snake_case server columns 1:1) round-trip through
 * Postgrest without hand-written DTOs. Supabase's own models pin their fields with `@SerialName`, so
 * the strategy only touches our rows.
 */
@OptIn(ExperimentalSerializationApi::class)
fun createEvenlySupabaseClient(): SupabaseClient =
    createSupabaseClient(supabaseUrl = SupabaseConfig.URL, supabaseKey = SupabaseConfig.ANON_KEY) {
        defaultSerializer = KotlinXSerializer(
            Json {
                ignoreUnknownKeys = true
                namingStrategy = JsonNamingStrategy.SnakeCase
                encodeDefaults = true
            },
        )
        install(Auth) {
            // Redirect target for browser OAuth (Google/Apple) + magic-link taps. The host must
            // register `splitevenly://login-callback` (Android intent-filter / iOS URL scheme) and the
            // same URL must be allow-listed in Supabase → Auth → URL Configuration.
            scheme = AUTH_REDIRECT_SCHEME
            host = AUTH_REDIRECT_HOST
        }
        install(Postgrest)
        install(Storage)
        install(Realtime)
    }

const val AUTH_REDIRECT_SCHEME: String = "splitevenly"
const val AUTH_REDIRECT_HOST: String = "login-callback"
