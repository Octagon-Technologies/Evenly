package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.splitevenly.data.db.projection.PeopleLineRow
import app.splitevenly.data.db.projection.PeopleRosterRow
import kotlinx.coroutines.flow.Flow

/**
 * Read-only queries behind the People tab: every group the viewer is still in, at once. Kept apart from
 * the per-group DAOs so the cross-group shape (and its "viewer is ACTIVE here" join) lives in one place.
 */
@Dao
interface PeopleDao {
    /**
     * Outstanding lines involving [me] in either direction, across every live group [me] is ACTIVE in.
     * Both directions are needed because a group's balance with someone nets inside that group, and the
     * People tab shows the same number the group does. Paid-back lines are included (remaining 0 or below)
     * so someone who owed you and has paid it all shows under "All settled".
     */
    @Query(
        """
        SELECT * FROM (
            SELECT e.group_id AS group_id, g.name AS group_name, g.emoji AS group_emoji, g.kind AS group_kind,
                   e.id AS expense_id, e.title AS title, e.expense_date AS expense_date,
                   e.currency AS currency, s.user_id AS debtor_user_id, e.payer_user_id AS creditor_user_id,
                   s.share_owed_subunits - COALESCE((
                       SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                       INNER JOIN settlements st ON st.id = sa.settlement_id
                       WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) AS remaining_subunits
            FROM shares s
            INNER JOIN expenses e ON e.id = s.expense_id
            INNER JOIN groups g ON g.id = e.group_id
            INNER JOIN members me ON me.group_id = e.group_id AND me.user_id = :me AND me.status = 'ACTIVE'
            WHERE g.deleted_at IS NULL AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND e.payer_user_id IS NOT NULL AND s.user_id <> e.payer_user_id
              AND (e.payer_user_id = :me OR s.user_id = :me)
        )
        ORDER BY expense_date ASC, expense_id ASC
        """,
    )
    fun observeLines(me: String): Flow<List<PeopleLineRow>>

    /** Who is in each of [me]'s live groups, for names and the "one person can't be in a group twice" rule. */
    @Query(
        """
        SELECT m.group_id AS group_id, g.name AS group_name, g.emoji AS group_emoji, m.user_id AS user_id,
               u.display_name AS display_name, COALESCE(u.is_placeholder, 0) AS is_placeholder
        FROM members m
        INNER JOIN groups g ON g.id = m.group_id
        INNER JOIN members me ON me.group_id = m.group_id AND me.user_id = :me AND me.status = 'ACTIVE'
        LEFT JOIN users u ON u.id = m.user_id
        WHERE g.deleted_at IS NULL AND m.status = 'ACTIVE'
        """,
    )
    fun observeRoster(me: String): Flow<List<PeopleRosterRow>>
}
