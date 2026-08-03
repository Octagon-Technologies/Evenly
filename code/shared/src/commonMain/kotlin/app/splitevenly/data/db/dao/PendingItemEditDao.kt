package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.PendingItemEditEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `pending_item_edits` — web guests' proposed changes to a bill's menu, awaiting the payer's
 * individual approval (WEB_CLAIM_SPEC.md §2.7).
 *
 * No delete of any kind: a decided edit stays as the record of who changed what on a shared bill, and
 * an undecided one is the payer's inbox. `decide` stamps in place.
 */
@Dao
interface PendingItemEditDao {

    @Upsert
    suspend fun upsert(edit: PendingItemEditEntity)

    @Upsert
    suspend fun upsertAll(edits: List<PendingItemEditEntity>)

    /** Everything proposed on this bill, oldest first — decided rows included (the review screen shows
     *  what it just decided, and E20's "approving the second applies on top of the first" needs order). */
    @Query("SELECT * FROM pending_item_edits WHERE expense_id = :expenseId ORDER BY proposed_at ASC")
    fun observeByExpense(expenseId: String): Flow<List<PendingItemEditEntity>>

    /** Undecided edits across a group's live itemized bills — the "N changes to review" surface. */
    @Query(
        """
        SELECT p.* FROM pending_item_edits p
        INNER JOIN expenses e ON e.id = p.expense_id
        WHERE p.group_id = :groupId AND p.decided_at IS NULL AND e.deleted_at IS NULL
        ORDER BY p.proposed_at ASC
        """
    )
    fun observeUndecidedByGroup(groupId: String): Flow<List<PendingItemEditEntity>>

    @Query("SELECT * FROM pending_item_edits WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingItemEditEntity?

    /** Stamp the payer's verdict. Bumping `row_version` + `updated_at` is what carries it to the server. */
    @Query(
        """
        UPDATE pending_item_edits
           SET decision = :decision, decided_at = :ts, decided_by = :decidedBy,
               updated_at = :ts, row_version = row_version + 1
         WHERE id = :id AND decided_at IS NULL
        """
    )
    suspend fun decide(id: String, decision: String, decidedBy: String, ts: Long)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM pending_item_edits")
    suspend fun allForSync(): List<PendingItemEditEntity>
}
