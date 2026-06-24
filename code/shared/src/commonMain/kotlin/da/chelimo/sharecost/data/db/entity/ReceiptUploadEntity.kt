package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * **Device-local outbox** for receipt uploads (not a synced wire-mirror — deliberately absent from
 * `SyncEngine`'s table list and *not* `@Serializable`). One row per picked file whose bytes have not
 * yet reached Supabase Storage. The picked bytes are copied to app-private storage at [localPath] the
 * instant they're picked, so this row + that file are a durable, restart/reboot-surviving record of
 * "this still needs uploading".
 *
 * On success the uploader creates the real [ReceiptEntity] (which *is* synced) and then **deletes this
 * row** + the local file — so the table only ever holds work that's pending or failed. [id] doubles as
 * the published receipt's id and the Storage object stem, making retries idempotent (same object key).
 */
@Entity(
    tableName = "receipt_uploads",
    indices = [Index(value = ["expense_id"])],
)
data class ReceiptUploadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "uploaded_by")
    val uploadedBy: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** Object key inside the receipts bucket, e.g. `<groupId>/<expenseId>/<id>.jpg`. Fixed at enqueue. */
    @ColumnInfo(name = "storage_path")
    val storagePath: String,

    /** Absolute path to the app-private copy of the (already compressed) bytes. */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "bytes_uploaded")
    val bytesUploaded: Long = 0,

    /** One of [da.chelimo.sharecost.domain.activity.ReceiptUploadStatus]'s names. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
