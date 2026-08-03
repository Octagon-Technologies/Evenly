package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.PendingItemEditEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `pending_item_edits` — the log of every change a web guest made to a bill's menu
 * (WEB_CLAIM_SPEC.md §2.7).
 *
 * No delete of any kind: this is the record of who changed what on a shared bill, and an undo is
 * another entry in it rather than an erasure. [markUndone] stamps in place.
 */
@Dao
interface PendingItemEditDao {

    @Upsert
    suspend fun upsert(edit: PendingItemEditEntity)

    @Upsert
    suspend fun upsertAll(edits: List<PendingItemEditEntity>)

    /** Everything that happened to this bill's menu, oldest first, undone rows included: the screen has
     *  to keep showing what it just undid, or a mis-tap vanishes instead of being legible. */
    @Query("SELECT * FROM pending_item_edits WHERE expense_id = :expenseId ORDER BY proposed_at ASC")
    fun observeByExpense(expenseId: String): Flow<List<PendingItemEditEntity>>

    /** Live (undoable) changes across a group's live itemized bills. */
    @Query(
        """
        SELECT p.* FROM pending_item_edits p
        INNER JOIN expenses e ON e.id = p.expense_id
        WHERE p.group_id = :groupId AND p.decision = 'APPLIED' AND e.deleted_at IS NULL
        ORDER BY p.proposed_at ASC
        """
    )
    fun observeLiveByGroup(groupId: String): Flow<List<PendingItemEditEntity>>

    @Query("SELECT * FROM pending_item_edits WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingItemEditEntity?

    /**
     * Stamp a change as undone. **Conditional on it still being `APPLIED`** — that `WHERE` clause is
     * first-undo-wins, the same shape as `claim_placeholder`: anyone on the bill may undo (spec §2.7),
     * so two people tapping at once must produce one undo and one no-op, not two.
     *
     * Bumping `row_version` + `updated_at` is what carries it to the server.
     */
    @Query(
        """
        UPDATE pending_item_edits
           SET decision = 'UNDONE', decided_at = :ts, decided_by = :undoneBy,
               updated_at = :ts, row_version = row_version + 1
         WHERE id = :id AND decision = 'APPLIED'
        """
    )
    suspend fun markUndone(id: String, undoneBy: String, ts: Long): Int

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM pending_item_edits")
    suspend fun allForSync(): List<PendingItemEditEntity>
}
