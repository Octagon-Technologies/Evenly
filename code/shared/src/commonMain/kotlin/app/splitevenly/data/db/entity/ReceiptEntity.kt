package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `receipts` (06 §5.1 — receipt capture). One row per image/PDF attached to an
 * expense. The bytes live in Supabase **Storage**; the row only carries the [storagePath] (the object
 * key inside the receipts bucket) plus a resolved [url] for rendering. [storagePath] is the source of
 * truth — [url] can always be re-derived from it if a signed/public URL expires.
 *
 * Synced wire-mirror with a [deletedAt] tombstone, same contract as [CommentEntity].
 */
@Entity(
    tableName = "receipts",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class ReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "uploaded_by")
    val uploadedBy: String,

    /** Object key inside the Storage bucket, e.g. `<groupId>/<expenseId>/<receiptId>.jpg`. */
    @ColumnInfo(name = "storage_path")
    val storagePath: String,

    /** Public (or last-resolved) URL for the object; rendered by the UI. Re-derivable from [storagePath]. */
    @ColumnInfo(name = "url")
    val url: String? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
