package app.splitevenly.data.upload

import app.splitevenly.data.remote.supabase.RECEIPTS_BUCKET
import app.splitevenly.data.remote.supabase.SupabaseConfig

/**
 * The contract a platform uploader drives. The shared [ReceiptUploadManager] implements it; the
 * platform [app.splitevenly.platform.ReceiptUploadScheduler] calls it. This is the seam that lets
 * Android (WorkManager + Ktor) and iOS (background `URLSession`) push bytes their own way while all the
 * Room bookkeeping — claiming work, recording progress, publishing the receipt on success — stays in
 * one shared place.
 */
interface ReceiptUploadDriver {
    /** Mark every uploadable row UPLOADING and return them as ready-to-send tasks (URL + auth resolved). */
    suspend fun claimPending(): List<PreparedUpload>

    /** Transferred-bytes update for the progress ring (callers should throttle; this hits the DB). */
    suspend fun reportProgress(id: String, bytesUploaded: Long)

    /** Bytes landed in Storage: publishes the synced receipt row and clears the outbox entry + local file. */
    suspend fun reportSuccess(id: String)

    /** A transfer attempt failed: the row goes FAILED and is retried on the next drain. */
    suspend fun reportFailure(id: String, error: String?)
}

/** A receipt upload resolved to everything a transfer needs: the local file, target URL, and auth token. */
class PreparedUpload(
    val id: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadUrl: String,
    val token: String,
)

/** Supabase Storage REST endpoints for the receipts bucket (used directly so we control upload progress). */
object ReceiptUploadEndpoints {
    /** PUT target (with `x-upsert: true`) for an object key — idempotent, so a retry overwrites cleanly. */
    fun uploadUrl(path: String): String = "${SupabaseConfig.URL}/storage/v1/object/$RECEIPTS_BUCKET/$path"

    /** Directly renderable public URL for a successfully uploaded object. */
    fun publicUrl(path: String): String = "${SupabaseConfig.URL}/storage/v1/object/public/$RECEIPTS_BUCKET/$path"
}

/** Supplies the current user's Supabase access token for authorizing a Storage upload. */
fun interface AccessTokenProvider {
    suspend fun token(): String?
}
