package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `user_subscriptions` — one person's Evenly Pro subscription (`PRO_PASS_SPEC.md` §5.2).
 * While it is live, every group that person is an ACTIVE member of is Pro, which is why this table is
 * keyed by user and carries no `group_id`.
 *
 * **Pull-only**, for the same reason as [GroupPassEntity] and enforced the same three redundant ways:
 * server RLS grants `select` alone, there is no entry in `SyncEngine.push`, and the DAO has no
 * `allForSync` for `push()` to consume.
 *
 * One row per person rather than per purchase: a store account holds at most one live subscription at a
 * time, so a renewal updates this row. Billing history lives in RevenueCat.
 */
@Entity(tableName = "user_subscriptions")
@Serializable
data class UserSubscriptionEntity(
    /** `users.id` of the subscriber. The primary key by construction, not just by convenience. */
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "product_id")
    val productId: String,

    /** `app_store` | `play_store` | `promo`. */
    @ColumnInfo(name = "store")
    val store: String,

    @ColumnInfo(name = "rc_app_user_id")
    val rcAppUserId: String,

    /** `monthly` | `annual`. Doubles as the `tier` label on the Pro badge. */
    @ColumnInfo(name = "period")
    val period: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** The paid-through date on the SERVER clock. Someone who cancels stays Pro to the end of the
     *  period they already paid for. */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /** Renders "renews 16 Aug" vs "ends 16 Aug". It never decides entitlement; [expiresAt] alone does. */
    @ColumnInfo(name = "will_renew")
    val willRenew: Boolean = true,

    /** Set on a refund or chargeback. Nothing already scanned is taken away. */
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
