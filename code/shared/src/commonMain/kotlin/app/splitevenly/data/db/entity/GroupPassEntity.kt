package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `group_passes` — a one-time, non-renewing Evenly Pro pass someone bought for a group
 * (`PRO_PASS_SPEC.md`). While one is live the whole group scans receipts without limit.
 *
 * **Pull-only.** The server is the only writer: RevenueCat reports a purchase, `activate-pass` verifies
 * it and inserts the row, and RLS grants the client `select` and nothing else. It is deliberately absent
 * from `SyncEngine.push`, and it must stay absent — a client that could write this table could grant
 * itself unlimited paid Claude-vision calls. Nothing in the app constructs one of these outside a pull.
 *
 * Mirrored locally at all so the Pro badge and the paywall's already-Pro state render offline and
 * without a round trip; enforcement is always the server's copy, never this one.
 */
@Entity(
    tableName = "group_passes",
    indices = [Index(value = ["group_id"])],
)
@Serializable
data class GroupPassEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    /** `users.id` of whoever paid. Shown by name on the badge: the purchase is a favour to the group,
     *  and "who paid for this?" is the first thing the other five people ask. */
    @ColumnInfo(name = "purchased_by")
    val purchasedBy: String,

    /** `week_1` | `week_2` | `month_1`. */
    @ColumnInfo(name = "tier")
    val tier: String,

    /** `app_store` | `play_store` | `promo`. */
    @ColumnInfo(name = "store")
    val store: String,

    @ColumnInfo(name = "store_txn_id")
    val storeTxnId: String,

    @ColumnInfo(name = "rc_app_user_id")
    val rcAppUserId: String,

    /** Epoch millis, stamped on the SERVER clock. A pass bought while another is live starts at that
     *  one's expiry rather than now, which is what makes two friends' passes stack instead of overlap. */
    @ColumnInfo(name = "starts_at")
    val startsAt: Long,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /** Set on a refund or chargeback. The group drops back to free at the next pull; nothing already
     *  scanned is taken away. */
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "deleted_by")
    val deletedBy: String? = null,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
