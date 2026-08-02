package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.PlaceholderClaimAnswerEntity
import app.splitevenly.data.db.projection.UnclaimedNameRow
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `placeholder_claim_answers` — the synced "that name is not me" answers — and the one query
 * that decides which names a member still has to answer.
 */
@Dao
interface PlaceholderClaimAnswerDao {

    @Upsert
    suspend fun upsert(answer: PlaceholderClaimAnswerEntity)

    @Upsert
    suspend fun upsertAll(answers: List<PlaceholderClaimAnswerEntity>)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM placeholder_claim_answers")
    suspend fun allForSync(): List<PlaceholderClaimAnswerEntity>

    @Query("SELECT * FROM placeholder_claim_answers WHERE group_id = :groupId AND answered_by_user_id = :userId")
    suspend fun answersOf(groupId: String, userId: String): List<PlaceholderClaimAnswerEntity>

    /**
     * The names [userId] still has to answer in [groupId]: names with no account, minus the ones already
     * claimed by somebody, minus the ones this member has ruled out, minus the ones this member created.
     * The **single source** for both the card and the full-screen list, so the two can never disagree and
     * the narrowing needs no separate "dismissed" state — the list simply shrinks as it is answered.
     *
     * The `created_by` filter is why we never ask someone whether they are a name they typed in
     * themselves. Placeholders created before that column existed are null, which reads as "creator
     * unknown" and offers them to everyone — exactly the pre-feature behaviour.
     *
     * Evidence is computed per name: how many live expenses it is booked into, what it owes across them,
     * and how many distinct currencies that spans (summing across currencies is meaningless, so a
     * multi-currency name shows the count alone). Most expenses first, then name, so the card's cap of 3
     * keeps the names most worth asking about.
     */
    @Query(
        """
        SELECT u.id AS user_id, u.display_name AS display_name,
               (SELECT COUNT(*) FROM shares s
                 INNER JOIN expenses e ON e.id = s.expense_id
                 WHERE s.user_id = u.id AND s.deleted_at IS NULL
                   AND e.group_id = :groupId AND e.deleted_at IS NULL) AS expense_count,
               (SELECT COALESCE(SUM(s.share_owed_subunits), 0) FROM shares s
                 INNER JOIN expenses e ON e.id = s.expense_id
                 WHERE s.user_id = u.id AND s.deleted_at IS NULL
                   AND e.group_id = :groupId AND e.deleted_at IS NULL) AS owed_subunits,
               (SELECT MIN(e.currency) FROM shares s
                 INNER JOIN expenses e ON e.id = s.expense_id
                 WHERE s.user_id = u.id AND s.deleted_at IS NULL
                   AND e.group_id = :groupId AND e.deleted_at IS NULL) AS currency,
               (SELECT COUNT(DISTINCT e.currency) FROM shares s
                 INNER JOIN expenses e ON e.id = s.expense_id
                 WHERE s.user_id = u.id AND s.deleted_at IS NULL
                   AND e.group_id = :groupId AND e.deleted_at IS NULL) AS currency_count
        FROM users u
        INNER JOIN members m ON m.user_id = u.id AND m.group_id = u.placeholder_group_id
        WHERE u.is_placeholder = 1
          AND u.placeholder_group_id = :groupId
          AND m.status = 'ACTIVE'
          AND m.placeholder_claim_completed_at IS NULL
          AND (u.created_by IS NULL OR u.created_by <> :userId)
          AND NOT EXISTS (
            SELECT 1 FROM placeholder_claim_answers a
            WHERE a.group_id = :groupId AND a.placeholder_user_id = u.id
              AND a.answered_by_user_id = :userId)
        ORDER BY expense_count DESC, u.display_name COLLATE NOCASE ASC
        """
    )
    fun observeUnansweredNames(groupId: String, userId: String): Flow<List<UnclaimedNameRow>>
}
