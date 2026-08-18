package app.splitevenly.data.remote.supabase

import app.splitevenly.core.time.ServerClock
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Teaches [ServerClock] what time the server thinks it is, from the `Date` header every HTTP response
 * already carries. Free: no extra request, no body read, one header lookup per response.
 *
 * A malformed or absent header teaches us nothing and changes nothing — [ServerClock] stays on the
 * identity clamp, which is also its behaviour on a device that has not reached the network yet.
 */
@OptIn(ExperimentalTime::class)
fun observeServerClock(headers: Headers) {
    val header = headers[HttpHeaders.Date] ?: return
    val serverMillis = runCatching { header.fromHttpToGmtDate().timestamp }.getOrNull() ?: return
    ServerClock.observe(serverEpochMillis = serverMillis, deviceEpochMillis = Clock.System.now().toEpochMilliseconds())
}

/**
 * [observeServerClock] as a Ktor plugin, installed by `installEvenlyDefaults` on the app's own client
 * (edge functions: OCR, export, pass activation).
 *
 * Postgrest traffic does **not** come through this client — supabase-kt builds its own, and configuring
 * it means an internal API — so `SyncEngine.pull` calls [observeServerClock] directly on the response
 * it is already holding. Sync runs far more often than any edge function, so that is the path that
 * actually keeps the offset fresh; this one is what makes a device that has only ever scanned a receipt
 * still learn it.
 */
val ServerClockPlugin =
    createClientPlugin("EvenlyServerClock") {
        onResponse { response -> observeServerClock(response.headers) }
    }
