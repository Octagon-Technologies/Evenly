package da.chelimo.sharecost.data.upload

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * One-shot PUT of receipt bytes to Supabase Storage over the shared Ktor client, with byte-level
 * progress via Ktor's `onUpload`. Used by the **Android** WorkManager worker (iOS uses a native
 * background `URLSession` instead). Whole-file: a failure surfaces as an `Err` and the manager retries
 * the file from the start on the next drain.
 */
class ReceiptUploadHttp(private val http: HttpClient) {

    suspend fun upload(
        task: PreparedUpload,
        bytes: ByteArray,
        onProgress: suspend (bytesSent: Long) -> Unit,
    ): AppResult<Unit> =
        try {
            val response = http.put(task.uploadUrl) {
                header(HttpHeaders.Authorization, "Bearer ${task.token}")
                header("x-upsert", "true")
                contentType(runCatching { ContentType.parse(task.mimeType) }.getOrDefault(ContentType.Application.OctetStream))
                setBody(bytes)
                onUpload { sent, _ -> onProgress(sent) }
            }
            if (response.status.isSuccess()) AppResult.Ok(Unit)
            else AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }
}
