package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlin.coroutines.cancellation.CancellationException

/** The Supabase Storage bucket receipts live in. Created out-of-band (dashboard / `supabase/SETUP.md`). */
const val RECEIPTS_BUCKET: String = "receipts"

/**
 * Where receipt *bytes* go (06 §5.1). Abstracted behind an interface so the [ActivityRepository] stays
 * testable and so a no-storage build (the offline [StubAuthSession] path) can bind a null and degrade
 * gracefully rather than hard-depending on the Supabase client.
 */
interface ReceiptStorage {
    /** Upload [bytes] to [path] in the receipts bucket; returns the public URL to render. */
    suspend fun upload(path: String, bytes: ByteArray, contentType: String): AppResult<String>

    /** Best-effort remove of the object at [path]. */
    suspend fun delete(path: String): AppResult<Unit>
}

/**
 * Supabase-Storage-backed [ReceiptStorage]. The bucket is **public** so [publicUrl] yields a directly
 * renderable address (no signing round-trip per view); RLS on the bucket still scopes *writes*. Upsert
 * is on so a retried upload of the same object key is idempotent.
 */
class SupabaseReceiptStorage(private val client: SupabaseClient) : ReceiptStorage {

    override suspend fun upload(path: String, bytes: ByteArray, contentType: String): AppResult<String> =
        runCatchingStorage {
            val bucket = client.storage.from(RECEIPTS_BUCKET)
            bucket.upload(path, bytes) { upsert = true }
            bucket.publicUrl(path)
        }

    override suspend fun delete(path: String): AppResult<Unit> =
        runCatchingStorage { client.storage.from(RECEIPTS_BUCKET).delete(path) }.let {
            when (it) {
                is AppResult.Ok -> AppResult.Ok(Unit)
                is AppResult.Err -> it
            }
        }

    private inline fun <T> runCatchingStorage(block: () -> T): AppResult<T> =
        try {
            AppResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }
}
