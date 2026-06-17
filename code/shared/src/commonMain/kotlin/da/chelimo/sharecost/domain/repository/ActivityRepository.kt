package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.CommentId
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.ReceiptId
import da.chelimo.sharecost.domain.activity.Comment
import da.chelimo.sharecost.domain.activity.HistoryEvent
import da.chelimo.sharecost.domain.activity.Receipt
import kotlinx.coroutines.flow.Flow

/**
 * The activity that hangs off a single expense (06 §3, §5.1): its comment thread, attached receipts,
 * and the append-only history feed. Local-first like every repository — writes land in Room first and
 * the [da.chelimo.sharecost.data.remote.supabase.SyncEngine] carries them up; receipt *bytes* go to
 * Supabase Storage on write (the only artifact that can't live purely in the local cache).
 */
interface ActivityRepository {

    // ── Comments ────────────────────────────────────────────────────────────
    fun observeComments(expenseId: ExpenseId): Flow<List<Comment>>

    /** Post [body] as the current user; trims + rejects blank. [groupId] scopes the row for sync/RLS. */
    suspend fun postComment(expenseId: ExpenseId, groupId: GroupId, body: String): AppResult<Comment>

    suspend fun deleteComment(commentId: CommentId): AppResult<Unit>

    // ── Receipts ────────────────────────────────────────────────────────────
    fun observeReceipts(expenseId: ExpenseId): Flow<List<Receipt>>

    /**
     * Upload a picked image/PDF to Storage and record it. The bytes are compressed (images) before
     * upload by the caller-supplied pipeline; here they are taken as-is. Returns the stored [Receipt].
     */
    suspend fun addReceipt(
        expenseId: ExpenseId,
        groupId: GroupId,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): AppResult<Receipt>

    suspend fun deleteReceipt(receiptId: ReceiptId): AppResult<Unit>

    // ── History ─────────────────────────────────────────────────────────────
    fun observeHistory(expenseId: ExpenseId): Flow<List<HistoryEvent>>
}
