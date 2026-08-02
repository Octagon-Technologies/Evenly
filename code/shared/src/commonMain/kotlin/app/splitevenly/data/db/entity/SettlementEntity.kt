package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `settlements` (02 §3.9): a payment event from one member to another. It pays down
 * one or more shares via [SettlementAllocationEntity] (a single payment can clear multiple
 * expenses, and a single share can be cleared by multiple partial payments).
 */
@Entity(
    tableName = "settlements",
    indices = [Index(value = ["group_id", "settled_at"])],
)
@Serializable
data class SettlementEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "from_user_id")
    val fromUserId: String,

    @ColumnInfo(name = "to_user_id")
    val toUserId: String,

    @ColumnInfo(name = "payment_currency")
    val paymentCurrency: String,

    @ColumnInfo(name = "payment_amount_subunits")
    val paymentAmountSubunits: Long,

    @ColumnInfo(name = "payment_app")
    val paymentApp: String? = null,

    @ColumnInfo(name = "deep_link_attempted")
    val deepLinkAttempted: Boolean = false,

    @ColumnInfo(name = "deep_link_succeeded")
    val deepLinkSucceeded: Boolean? = null,

    @ColumnInfo(name = "settled_at")
    val settledAt: Long,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
