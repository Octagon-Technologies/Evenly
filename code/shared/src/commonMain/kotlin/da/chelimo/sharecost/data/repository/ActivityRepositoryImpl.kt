package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.CommentId
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.ReceiptId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.CommentDao
import da.chelimo.sharecost.data.db.dao.HistoryEventDao
import da.chelimo.sharecost.data.db.dao.ReceiptDao
import da.chelimo.sharecost.data.db.entity.CommentEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import da.chelimo.sharecost.data.remote.supabase.ReceiptStorage
import da.chelimo.sharecost.domain.activity.Comment
import da.chelimo.sharecost.domain.activity.HistoryEvent
import da.chelimo.sharecost.domain.activity.HistoryEventType
import da.chelimo.sharecost.domain.activity.Receipt
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.repository.ActivityRepository
import da.chelimo.sharecost.newId
import da.chelimo.sharecost.platform.extensionForMime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [ActivityRepository] (06 §3, §5.1). Comments + receipt rows + history events are written
 * to Room first (the [da.chelimo.sharecost.data.remote.supabase.SyncEngine] carries them up); receipt
 * *bytes* are the one artifact that can't live in the cache, so they go straight to Supabase Storage on
 * write via [receiptStorage]. When storage is unavailable (the offline stub-auth build) [receiptStorage]
 * is null and [addReceipt] returns a clear error rather than silently dropping the file.
 */
@OptIn(ExperimentalTime::class)
class ActivityRepositoryImpl(
    private val commentDao: CommentDao,
    private val receiptDao: ReceiptDao,
    private val historyDao: HistoryEventDao,
    private val auth: AuthSession,
    private val receiptStorage: ReceiptStorage? = null,
    private val clock: Clock = Clock.System,
) : ActivityRepository {

    // ── Comments ────────────────────────────────────────────────────────────
    override fun observeComments(expenseId: ExpenseId): Flow<List<Comment>> =
        commentDao.observeByExpense(expenseId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun postComment(expenseId: ExpenseId, groupId: GroupId, body: String): AppResult<Comment> {
        val text = body.trim()
        if (text.isEmpty()) return AppError.Validation(mapOf("body" to AppError.Validation.Reason.Required)).asErr()
        val uid = auth.currentUserId.value ?: return notSignedIn()
        val now = clock.nowEpochMillis()
        val entity = CommentEntity(
            id = newId(),
            expenseId = expenseId.value,
            groupId = groupId.value,
            userId = uid.value,
            body = text,
            createdAt = now,
            updatedAt = now,
        )
        commentDao.upsert(entity)
        recordHistory(expenseId.value, groupId.value, HistoryEventType.COMMENTED, uid.value, null, now)
        return entity.toDomain().asOk()
    }

    override suspend fun deleteComment(commentId: CommentId): AppResult<Unit> {
        commentDao.softDelete(commentId.value, clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    // ── Receipts ────────────────────────────────────────────────────────────
    override fun observeReceipts(expenseId: ExpenseId): Flow<List<Receipt>> =
        receiptDao.observeByExpense(expenseId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun addReceipt(
        expenseId: ExpenseId,
        groupId: GroupId,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): AppResult<Receipt> {
        val storage = receiptStorage
            ?: return AppError.Network(AppError.Network.Kind.Offline).asErr()
        val uid = auth.currentUserId.value ?: return notSignedIn()
        if (bytes.isEmpty()) return AppError.Validation(mapOf("file" to AppError.Validation.Reason.Malformed)).asErr()

        val now = clock.nowEpochMillis()
        val receiptId = newId()
        // Object key groups objects by group/expense so a future per-group storage policy is trivial.
        val path = "${groupId.value}/${expenseId.value}/$receiptId.${extensionForMime(mimeType)}"
        val url = when (val uploaded = storage.upload(path, bytes, mimeType)) {
            is AppResult.Ok -> uploaded.value
            is AppResult.Err -> return uploaded
        }
        val entity = ReceiptEntity(
            id = receiptId,
            expenseId = expenseId.value,
            groupId = groupId.value,
            uploadedBy = uid.value,
            storagePath = path,
            url = url,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = now,
            updatedAt = now,
        )
        receiptDao.upsert(entity)
        recordHistory(expenseId.value, groupId.value, HistoryEventType.RECEIPT_ADDED, uid.value, null, now)
        return entity.toDomain().asOk()
    }

    override suspend fun deleteReceipt(receiptId: ReceiptId): AppResult<Unit> {
        val existing = receiptDao.getById(receiptId.value)
        receiptDao.softDelete(receiptId.value, clock.nowEpochMillis())
        // Best-effort: free the Storage object too, but a failure here doesn't fail the local delete.
        if (existing != null) receiptStorage?.delete(existing.storagePath)
        return AppResult.Ok(Unit)
    }

    // ── History ─────────────────────────────────────────────────────────────
    override fun observeHistory(expenseId: ExpenseId): Flow<List<HistoryEvent>> =
        historyDao.observeByExpense(expenseId.value).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    private suspend fun recordHistory(
        expenseId: String,
        groupId: String,
        type: HistoryEventType,
        actorUserId: String?,
        detail: String?,
        now: Long,
    ) {
        historyDao.upsert(
            HistoryEventEntity(
                id = newId(),
                expenseId = expenseId,
                groupId = groupId,
                actorUserId = actorUserId,
                type = type.name,
                detail = detail,
                createdAt = now,
            ),
        )
    }

    private fun notSignedIn(): AppResult<Nothing> =
        AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
}

private fun CommentEntity.toDomain(): Comment =
    Comment(CommentId(id), ExpenseId(expenseId), UserId(userId), body, createdAt)

private fun ReceiptEntity.toDomain(): Receipt =
    Receipt(ReceiptId(id), ExpenseId(expenseId), UserId(uploadedBy), storagePath, url, mimeType, sizeBytes, createdAt)

private fun HistoryEventEntity.toDomainOrNull(): HistoryEvent? {
    val parsed = HistoryEventType.entries.firstOrNull { it.name == type } ?: return null
    return HistoryEvent(id, ExpenseId(expenseId), actorUserId?.let { UserId(it) }, parsed, detail, createdAt)
}
