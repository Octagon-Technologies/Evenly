package app.splitevenly.data.upload

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.HistoryEventDao
import app.splitevenly.data.db.dao.ReceiptDao
import app.splitevenly.data.db.dao.ReceiptUploadDao
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.ReceiptEntity
import app.splitevenly.data.db.entity.ReceiptUploadEntity
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.activity.ReceiptUpload
import app.splitevenly.domain.activity.ReceiptUploadStatus
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.newId
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.ImageProcessor
import app.splitevenly.platform.NetworkStatus
import app.splitevenly.platform.PickedFile
import app.splitevenly.platform.ReceiptFileStore
import app.splitevenly.platform.ReceiptUploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The durable, resumable receipt-upload pipeline (D-22). Picked files are compressed, copied to the
 * app sandbox, and recorded in a **local-only** outbox; a platform [ReceiptUploadScheduler] then carries
 * the bytes to Storage in the background. This class owns every Room mutation along the way — it's the
 * single [ReceiptUploadDriver] both platforms report into — so success always means "synced receipt row
 * created + local copy cleaned up", and a crash/network-drop never loses or double-publishes a file.
 *
 * Resilience contract: the outbox row + sandbox file outlive the process; [bind] recovers any work
 * stranded by a kill and re-drains whenever connectivity returns; failures stay FAILED and retry whole.
 */
@OptIn(ExperimentalTime::class)
class ReceiptUploadManager(
    private val uploadDao: ReceiptUploadDao,
    private val receiptDao: ReceiptDao,
    private val historyDao: HistoryEventDao,
    private val fileStore: ReceiptFileStore,
    private val imageProcessor: ImageProcessor,
    private val scheduler: ReceiptUploadScheduler,
    private val http: ReceiptUploadHttp,
    private val auth: AuthSession,
    private val connectivity: ConnectivityObserver,
    private val tokens: AccessTokenProvider,
    private val clock: Clock = Clock.System,
) : ReceiptUploadDriver {

    // App-lifetime scope (mirrors SupabaseAuthSession's own scope). Created once; never cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Self-bind on construction so leftover uploads from a prior session drain on launch — without
        // AuthSession having to depend on this manager (which would be a DI cycle).
        bind(scope)
    }

    /** Live pending/failed uploads for an expense, for the in-progress thumbnails + progress rings. */
    fun observe(expenseId: ExpenseId): Flow<List<ReceiptUpload>> =
        uploadDao.observeByExpense(expenseId.value).map { rows -> rows.map { it.toDomain() } }

    /** Hook the pipeline into the app lifecycle: recover stranded work, drain once, re-drain on reconnect. */
    private fun bind(scope: CoroutineScope) {
        scheduler.bind(this)
        scope.launch {
            // A row left UPLOADING by a killed process is stale — reset it so the next drain re-sends it
            // from the start (whole-file retry).
            uploadDao.resetStatus(ReceiptUploadStatus.UPLOADING.name, ReceiptUploadStatus.PENDING.name, clock.nowEpochMillis())
            scheduler.schedule()
        }
        scope.launch {
            connectivity.status.distinctUntilChanged().collect { status ->
                if (status == NetworkStatus.Online) scheduler.schedule()
            }
        }
    }

    /**
     * Queue [files] for upload. Each is compressed, persisted to disk, and recorded PENDING *before* any
     * network is touched — so the thumbnail shows instantly and the work survives even if the upload
     * never starts. Then kick the background pipeline.
     */
    suspend fun enqueue(expenseId: ExpenseId, groupId: GroupId, files: List<PickedFile>): AppResult<Unit> {
        val uid = auth.currentUserId.value ?: return notSignedIn()
        files.forEach { file ->
            if (file.bytes.isEmpty()) return@forEach
            val processed = imageProcessor.compress(file.bytes, file.mimeType)
            val id = newId()
            val storagePath = "${groupId.value}/${expenseId.value}/$id.${processed.extension}"
            val localPath = fileStore.save(id, processed.extension, processed.bytes)
            val now = clock.nowEpochMillis()
            uploadDao.upsert(
                ReceiptUploadEntity(
                    id = id,
                    expenseId = expenseId.value,
                    groupId = groupId.value,
                    uploadedBy = uid.value,
                    fileName = file.name,
                    mimeType = processed.mimeType,
                    storagePath = storagePath,
                    localPath = localPath,
                    sizeBytes = processed.bytes.size.toLong(),
                    status = ReceiptUploadStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        scheduler.schedule()
        return AppResult.Ok(Unit)
    }

    /** Re-queue a FAILED upload from the start. */
    suspend fun retry(id: String) {
        val now = clock.nowEpochMillis()
        uploadDao.updateStatus(id, ReceiptUploadStatus.PENDING.name, now)
        uploadDao.updateProgress(id, 0, now)
        scheduler.schedule()
    }

    /** Abandon an upload: stop any transfer, drop the outbox row, and delete the sandbox copy. */
    suspend fun cancel(id: String) {
        val row = uploadDao.getById(id)
        scheduler.cancel(id)
        uploadDao.delete(id)
        row?.let { fileStore.delete(it.localPath) }
    }

    /**
     * Android worker entrypoint: drain the outbox over Ktor, sequentially. Returns true when nothing is
     * left to do (so the worker can report success); false means at least one upload still needs a retry.
     */
    suspend fun uploadAll(): Boolean {
        val tasks = claimPending()
        for (task in tasks) {
            val bytes = fileStore.read(task.localPath)
            if (bytes == null) {
                // The sandbox file vanished (shouldn't happen) — we can't recover the bytes, so drop it.
                reportFailure(task.id, "Local file missing")
                cancel(task.id)
                continue
            }
            var lastReported = 0L
            val result = http.upload(task, bytes) { sent ->
                // Throttle DB writes: only persist progress on a meaningful jump or at completion.
                if (sent - lastReported >= PROGRESS_STEP_BYTES || sent >= task.sizeBytes) {
                    lastReported = sent
                    reportProgress(task.id, sent)
                }
            }
            when (result) {
                is AppResult.Ok -> reportSuccess(task.id)
                is AppResult.Err -> reportFailure(task.id, "Upload failed")
            }
        }
        return uploadDao.all().none { ReceiptUploadStatus.fromName(it.status) != ReceiptUploadStatus.UPLOADING }
    }

    // ── ReceiptUploadDriver ───────────────────────────────────────────────────
    override suspend fun claimPending(): List<PreparedUpload> {
        val token = tokens.token() ?: return emptyList() // not signed in / no session yet — try again later
        val now = clock.nowEpochMillis()
        return uploadDao.all()
            // Skip rows already in flight (e.g. an iOS native task) so we never double-send.
            .filter { ReceiptUploadStatus.fromName(it.status) != ReceiptUploadStatus.UPLOADING }
            .map { row ->
                uploadDao.updateStatus(row.id, ReceiptUploadStatus.UPLOADING.name, now)
                PreparedUpload(
                    id = row.id,
                    localPath = row.localPath,
                    mimeType = row.mimeType,
                    sizeBytes = row.sizeBytes,
                    uploadUrl = ReceiptUploadEndpoints.uploadUrl(row.storagePath),
                    token = token,
                )
            }
    }

    override suspend fun reportProgress(id: String, bytesUploaded: Long) {
        uploadDao.updateProgress(id, bytesUploaded, clock.nowEpochMillis())
    }

    override suspend fun reportSuccess(id: String) {
        val row = uploadDao.getById(id) ?: return
        val now = clock.nowEpochMillis()
        // Publish the real (synced) receipt row — this is what reaches peers via the sync engine.
        receiptDao.upsert(
            ReceiptEntity(
                id = row.id,
                expenseId = row.expenseId,
                groupId = row.groupId,
                uploadedBy = row.uploadedBy,
                storagePath = row.storagePath,
                url = ReceiptUploadEndpoints.publicUrl(row.storagePath),
                mimeType = row.mimeType,
                sizeBytes = row.sizeBytes,
                createdAt = now,
                updatedAt = now,
            ),
        )
        historyDao.upsert(
            HistoryEventEntity(
                id = newId(),
                expenseId = row.expenseId,
                groupId = row.groupId,
                actorUserId = row.uploadedBy,
                type = HistoryEventType.RECEIPT_ADDED.name,
                detail = null,
                createdAt = now,
            ),
        )
        uploadDao.delete(id)
        fileStore.delete(row.localPath)
    }

    override suspend fun reportFailure(id: String, error: String?) {
        uploadDao.markFailed(id, ReceiptUploadStatus.FAILED.name, error, clock.nowEpochMillis())
    }

    private fun ReceiptUploadEntity.toDomain(): ReceiptUpload =
        ReceiptUpload(
            id = id,
            expenseId = expenseId,
            localPath = localPath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            bytesUploaded = bytesUploaded,
            status = ReceiptUploadStatus.fromName(status),
        )

    private fun notSignedIn(): AppResult<Nothing> =
        AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()

    private companion object {
        const val PROGRESS_STEP_BYTES = 16 * 1024L
    }
}
