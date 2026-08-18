package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ReceiptUploadEntity
import kotlinx.coroutines.flow.Flow

/** DAO for the local-only receipt-upload outbox (see [ReceiptUploadEntity]). */
@Dao
interface ReceiptUploadDao {
    @Upsert
    suspend fun upsert(upload: ReceiptUploadEntity)

    /** Live pending/failed uploads for an expense, oldest first (so they render in pick order). */
    @Query("SELECT * FROM receipt_uploads WHERE expense_id = :expenseId ORDER BY created_at ASC")
    fun observeByExpense(expenseId: String): Flow<List<ReceiptUploadEntity>>

    /** Every outstanding upload — the work-list the background uploader drains. */
    @Query("SELECT * FROM receipt_uploads ORDER BY created_at ASC")
    suspend fun all(): List<ReceiptUploadEntity>

    @Query("SELECT * FROM receipt_uploads WHERE id = :id")
    suspend fun getById(id: String): ReceiptUploadEntity?

    /** Advance the transferred-bytes counter (progress) without touching status. */
    @Query("UPDATE receipt_uploads SET bytes_uploaded = :bytes, updated_at = :now WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        bytes: Long,
        now: Long,
    )

    @Query("UPDATE receipt_uploads SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        now: Long,
    )

    /** Mark a failed attempt: bump the retry counter and record the error for the UI. */
    @Query(
        "UPDATE receipt_uploads SET status = :status, last_error = :error, retry_count = retry_count + 1, updated_at = :now WHERE id = :id",
    )
    suspend fun markFailed(
        id: String,
        status: String,
        error: String?,
        now: Long,
    )

    /**
     * Reset any rows stranded mid-flight (e.g. the process died while UPLOADING) back to a clean
     * pending state so the next drain re-sends them from the start (whole-file retry).
     */
    @Query("UPDATE receipt_uploads SET status = :toStatus, bytes_uploaded = 0, updated_at = :now WHERE status = :fromStatus")
    suspend fun resetStatus(
        fromStatus: String,
        toStatus: String,
        now: Long,
    )

    /**
     * User-initiated retry: back to PENDING with the auto-retry budget restored. Zeroing `retry_count`
     * is what re-arms a terminally FAILED row past `MAX_AUTO_RETRIES` — a tap is new evidence the user
     * wants this file up, so it buys a fresh budget rather than one attempt.
     */
    @Query(
        "UPDATE receipt_uploads SET status = 'PENDING', bytes_uploaded = 0, retry_count = 0, last_error = NULL, updated_at = :now WHERE id = :id",
    )
    suspend fun resetForRetry(
        id: String,
        now: Long,
    )

    @Query("DELETE FROM receipt_uploads WHERE id = :id")
    suspend fun delete(id: String)
}
