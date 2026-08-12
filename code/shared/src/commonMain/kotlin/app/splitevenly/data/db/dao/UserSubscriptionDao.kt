package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.UserSubscriptionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `user_subscriptions` — the personal route to Evenly Pro (`PRO_PASS_SPEC.md` §5.2).
 *
 * Read plus upsert-from-pull only, and **no `allForSync`** — that method is exactly what
 * `SyncEngine.push` consumes, so its absence makes pull-only a fact of the type system rather than a
 * comment. Same reasoning as [GroupPassDao]. Do not add one.
 */
@Dao
interface UserSubscriptionDao {

    /** Landing rows from a pull. The server is the only source of these. */
    @Upsert
    suspend fun upsertAll(subscriptions: List<UserSubscriptionEntity>)

    /**
     * Every subscription held by a **current ACTIVE member** of this group.
     *
     * The membership join is the whole point (`PRO_PASS_SPEC.md` §5.3): a subscription grants Pro through
     * the roster, so a subscriber leaving must drop the group back to free with nothing else to update.
     * Mirrors the server's `group_pro_status` join rather than a materialized per-group grant, which
     * could silently disagree with the roster.
     *
     * Deliberately NOT filtered on "still live": the caller filters by a clock it passes in, so a screen
     * open across an expiry re-evaluates instead of holding a `true` a query decided minutes ago.
     */
    @Query(
        """
        SELECT s.* FROM user_subscriptions s
        JOIN members m ON m.user_id = s.user_id
        WHERE m.group_id = :groupId AND m.status = 'ACTIVE'
        ORDER BY s.expires_at DESC
        """,
    )
    fun observeForGroup(groupId: String): Flow<List<UserSubscriptionEntity>>

    /** The signed-in user's own subscription, for the Profile row and the manage/restore controls. */
    @Query("SELECT * FROM user_subscriptions WHERE user_id = :userId")
    fun observeForUser(userId: String): Flow<UserSubscriptionEntity?>
}
