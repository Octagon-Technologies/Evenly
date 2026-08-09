package app.splitevenly.data.remote.supabase

import app.splitevenly.data.upload.AccessTokenProvider
import app.splitevenly.domain.export.ExportOutcome
import app.splitevenly.domain.export.GroupExporter
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.NetworkStatus
import kotlinx.coroutines.flow.first

/**
 * Calls the `export_group` edge function and returns the group's ledger as CSV text.
 *
 * Every failure is a distinct [ExportOutcome] so the screen can say the true thing: offline, needs a
 * pass, or something broke. Same shape as [ReceiptOcrHttp], including authenticating **as the user** so
 * the server can check membership. The Pro gate is enforced server-side; this client can only report it.
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
            val response = http.post("${SupabaseConfig.URL}/functions/v1/export_group") {
                header("Authorization", "Bearer $userToken")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{\"groupId\":\"$groupId\"}")
            }
            val body = response.bodyAsText()
            when {
                response.status.isSuccess() -> ExportOutcome.Success(body)
                // 402 is the Pro gate, and it is a refusal rather than a failure: nothing went wrong and
                // retrying changes nothing, so the screen must not offer a retry.
                response.status == HttpStatusCode.PaymentRequired -> ExportOutcome.NeedsPro
                else -> ExportOutcome.Failed(body.takeIf { it.isNotBlank() })
            }
        } catch (t: Throwable) {
            ExportOutcome.Failed(t.message)
        }
    }
}
