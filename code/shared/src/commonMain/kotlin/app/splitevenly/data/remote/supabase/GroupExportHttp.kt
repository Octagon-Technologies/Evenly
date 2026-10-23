package app.splitevenly.data.remote.supabase

import app.splitevenly.data.upload.AccessTokenProvider
import app.splitevenly.domain.export.ExportOutcome
import app.splitevenly.domain.export.GroupExporter
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.NetworkStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/**
 * Calls the `export_group` edge function and returns the group's ledger as CSV text.
 *
 * Every failure is a distinct [ExportOutcome] so the screen can say the true thing: offline,
 * unavailable, or something broke. It authenticates as the user so the server can check membership.
 */
class GroupExportHttp(
    private val http: HttpClient,
    private val connectivity: ConnectivityObserver,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : GroupExporter {
    override suspend fun exportCsv(groupId: String): ExportOutcome {
        if (!SupabaseConfig.isConfigured) return ExportOutcome.Unavailable
        if (connectivity.status.first() == NetworkStatus.Offline) return ExportOutcome.Offline
        val userToken = accessTokenProvider?.token() ?: return ExportOutcome.Unavailable
        return try {
            val response =
                http.post("${SupabaseConfig.URL}/functions/v1/export_group") {
                    header("Authorization", "Bearer $userToken")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody("{\"groupId\":\"$groupId\"}")
                }
            val body = response.bodyAsText()
            when {
                response.status.isSuccess() -> ExportOutcome.Success(body)

                else -> ExportOutcome.Failed(body.takeIf { it.isNotBlank() })
            }
        } catch (e: CancellationException) {
            // Never swallow structured-concurrency cancellation (same rule as [ReceiptOcrHttp] and
            // `FrankfurterFxFetcher`): leaving the export screen cancels this call, and reporting that
            // as ExportOutcome.Failed writes an error onto a screen the user already walked away from.
            throw e
        } catch (t: Throwable) {
            ExportOutcome.Failed(t.message)
        }
    }
}
