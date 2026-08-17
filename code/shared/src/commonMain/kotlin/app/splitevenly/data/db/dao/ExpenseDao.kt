package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ConflictEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ShareEntity
import kotlinx.coroutines.flow.Flow

/** One expense's re-split inside a retroactive-member sweep. See [ExpenseDao.applyRetroactiveMember]. */
data class RetroactiveResplit(
    val expense: ExpenseEntity,
    val shares: List<ShareEntity>,
    val removedShareIds: List<String>,
)

/** DAO for `expenses` (02 §3.7). Owns the expense+shares write transactions (02 §7.5). */
@Dao
interface ExpenseDao {
    @Upsert
    suspend fun upsert(expense: ExpenseEntity)

    @Upsert
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: String): ExpenseEntity?

    /** All non-deleted expenses in a group, one-shot — the input to a retroactive-member sweep (03 §8.1). */
    @Query("SELECT * FROM expenses WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY expense_date ASC, id ASC")
    suspend fun getActiveByGroup(groupId: String): List<ExpenseEntity>

    /** Every local row (incl. soft-deleted) — the push side of sync. */
    @Query("SELECT * FROM expenses")
    suspend fun allForSync(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<ExpenseEntity?>

    /**
     * Group home feed (U-3): non-deleted expenses newest-first. `id DESC` is the stable tiebreaker
     * within a day — and because IDs are UUIDv7 (time-sortable), it orders same-day rows by creation
     * time without a separate column.
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE group_id = :groupId AND deleted_at IS NULL
        ORDER BY expense_date DESC, id DESC
        """,
    )
    fun observeByGroup(groupId: String): Flow<List<ExpenseEntity>>

    // Payer reassignment and the post-merge split_version touch live in [PlaceholderMergeDao]: they are
    // steps of the placeholder merge and have to run inside its transaction, not next to it.

    // --- Shares (declared here so the expense + its shares write in one transaction) ------------

    @Upsert
    suspend fun upsertShares(shares: List<ShareEntity>)

    /** Tombstone the shares an edit removed (Rule 1): soft-delete so the removal syncs, never hard-delete. */
    @Query("UPDATE shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteSharesByIds(
        ids: List<String>,
        ts: Long,
    )

    /** Soft-delete an expense (04 §2.3 `delete_expense`); status becomes DELETED, version bumps. */
    @Query("UPDATE expenses SET deleted_at = :ts, status = 'DELETED', updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(
        id: String,
        ts: Long,
    )

    // --- Transactions ---------------------------------------------------------------------------

    /** Insert an expense and its shares atomically (AC-INV-001 enforced by the caller). */
    @Transaction
    suspend fun insertWithShares(
        expense: ExpenseEntity,
        shares: List<ShareEntity>,
    ) {
        upsert(expense)
        upsertShares(shares)
    }

    /**
     * Apply an expense edit atomically: update the expense, soft-delete the shares the edit removed,
     * and upsert the surviving/added shares. Removed shares are tombstoned (not hard-deleted) so the
     * removal syncs; surviving participants keep their share `id` (caller does the identity merge), so
     * settlement allocations stay linked and `remaining` re-derives instead of resetting (edit_expense).
     */
    @Transaction
    suspend fun replaceWithShares(
        expense: ExpenseEntity,
        shares: List<ShareEntity>,
        removedShareIds: List<String>,
        ts: Long,
    ) {
        upsert(expense)
        if (removedShareIds.isNotEmpty()) softDeleteSharesByIds(removedShareIds, ts)
        upsertShares(shares)
    }

    /** Tombstone the local shares the server's canonical set no longer contains (adoption cleanup). */
    @Query(
        "UPDATE shares SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE expense_id = :expenseId AND deleted_at IS NULL AND id NOT IN (:keepIds)",
    )
    suspend fun softDeleteLocalSharesNotIn(
        expenseId: String,
        keepIds: List<String>,
        now: Long,
    )

    /**
     * Conditional adoption (versioning #8): overwrite from the server's canonical ONLY if the local
     * expense still matches the [expectedRowVersion] we snapshotted before the merge RPC. A user edit
     * landing while the RPC was in flight bumps `row_version`, so we detect it and SKIP adoption —
     * otherwise the round-trip's canonical would silently clobber that just-made edit (which then never
     * re-dirties). Returns true iff it adopted; false leaves the local (newer) edit intact to re-push.
     */
    @Transaction
    suspend fun overwriteFromServerIfUnchanged(
        expectedRowVersion: Long,
        expense: ExpenseEntity,
        serverShares: List<ShareEntity>,
        now: Long,
    ): Boolean {
        val current = getById(expense.id)
        if (current == null || current.rowVersion != expectedRowVersion) return false
        upsert(expense)
        softDeleteLocalSharesNotIn(expense.id, serverShares.map { it.id }, now)
        upsertShares(serverShares)
        return true
    }

    /** Conflict upsert declared here so [applyRetroactiveMember] can write the whole sweep atomically. */
    @Upsert
    suspend fun upsertConflicts(conflicts: List<ConflictEntity>)

    /**
     * Apply a whole retroactive-member sweep (03 §8.1) in ONE transaction: every EVEN expense re-split to
     * include the new member, and a conflict card raised for every expense whose split is not ours to
     * re-derive (finding R12).
     *
     * It was a per-expense loop, each write individually atomic and the loop as a whole not, run from a
     * navigation-scoped coroutine. Leaving group settings after eight of twenty expenses left the group's
     * history split at an arbitrary point: both halves internally consistent, both pushing cleanly,
     * nothing looking broken and nothing ever re-running it, because the only trigger is adding a member
     * who is now already there. Bounded by the group's expense count, so one transaction is affordable.
     *
     * The caller computes every row first; this only writes them.
     */
    @Transaction
    suspend fun applyRetroactiveMember(
        resplits: List<RetroactiveResplit>,
        conflicts: List<ConflictEntity>,
        ts: Long,
    ) {
        for (r in resplits) {
            upsert(r.expense)
            if (r.removedShareIds.isNotEmpty()) softDeleteSharesByIds(r.removedShareIds, ts)
            upsertShares(r.shares)
        }
        if (conflicts.isNotEmpty()) upsertConflicts(conflicts)
    }

    /**
     * Conditional adoption of just the expense row (versioning #8), for an ITEMIZED bill whose shares are
     * a local derived materialization (adopted by re-running the materializer, not the server set). Skips
     * if a user edit bumped `row_version` during the merge round-trip. Returns true iff it adopted.
     */
    @Transaction
    suspend fun upsertFromServerIfUnchanged(
        expectedRowVersion: Long,
        expense: ExpenseEntity,
    ): Boolean {
        val current = getById(expense.id)
        if (current == null || current.rowVersion != expectedRowVersion) return false
        upsert(expense)
        return true
    }
}
