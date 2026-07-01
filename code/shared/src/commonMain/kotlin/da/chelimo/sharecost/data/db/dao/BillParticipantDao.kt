package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.BillParticipantEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `bill_participants` — who a "Split the bill" expense is for, plus each person's "I'm done"
 * stamp. Additive set; removing a participant soft-deletes (Rule 1).
 */
@Dao
interface BillParticipantDao {

    @Upsert
    suspend fun upsert(participant: BillParticipantEntity)

    @Upsert
    suspend fun upsertAll(participants: List<BillParticipantEntity>)

    /** The bill's **active** participants (excludes tombstones). */
    @Query("SELECT * FROM bill_participants WHERE expense_id = :expenseId AND deleted_at IS NULL")
    suspend fun getByExpense(expenseId: String): List<BillParticipantEntity>

    /** Reactive active participants (drives the claim screen's "shared by all" pool + the done tally). */
    @Query("SELECT * FROM bill_participants WHERE expense_id = :expenseId AND deleted_at IS NULL")
    fun observeByExpense(expenseId: String): Flow<List<BillParticipantEntity>>

    /** This user's active participant row on a bill, if any. */
    @Query("SELECT * FROM bill_participants WHERE expense_id = :expenseId AND user_id = :userId AND deleted_at IS NULL LIMIT 1")
    suspend fun getActive(expenseId: String, userId: String): BillParticipantEntity?

    /** Active participant rows across a whole group's non-deleted itemized expenses — the home surface input. */
    @Query(
        """
        SELECT bp.* FROM bill_participants bp
        INNER JOIN expenses e ON e.id = bp.expense_id
        WHERE bp.group_id = :groupId AND bp.deleted_at IS NULL AND e.deleted_at IS NULL AND e.split_mode = 'ITEMIZED'
        """
    )
    fun observeByGroup(groupId: String): Flow<List<BillParticipantEntity>>

    /** Every local row incl. tombstones — the push side of sync. */
    @Query("SELECT * FROM bill_participants")
    suspend fun allForSync(): List<BillParticipantEntity>

    /** Soft-delete participants (Rule 1). */
    @Query("UPDATE bill_participants SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, ts: Long)

    /** Stamp / clear this participant's "I'm done" marker. */
    @Query("UPDATE bill_participants SET done_at = :doneAt, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun setDone(id: String, doneAt: Long?, ts: Long)
}
