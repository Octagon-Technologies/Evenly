package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `payment_proofs` (docs/features/payment-proof.md): a screenshot attached to a payment.
 * Same shape and bucket as [ReceiptEntity]; the bytes live in Storage at
 * `<groupId>/payments/<settlementId>/<id>.<ext>`.
 *
 * [settlementId] is the payment's CHAIN id (`original_settlement_id ?? id`), not the row id: editing a
 * payment voids it and writes a replacement, and the screenshot has to stay with the payment. One live row
 * per chain; attaching a new screenshot soft-deletes the old one.
 */
@Entity(
    tableName = "payment_proofs",
    indices = [
        Index(value = ["settlement_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class PaymentProofEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "settlement_id")
    val settlementId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "uploaded_by")
    val uploadedBy: String,

    @ColumnInfo(name = "storage_path")
    val storagePath: String,

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
