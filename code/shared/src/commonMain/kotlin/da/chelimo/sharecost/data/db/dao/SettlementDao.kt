package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `settlements` + `settlement_allocations` (02 §3.9). Owns the apply/void transactions. */
@Dao
interface SettlementDao {

    @Upsert
    suspend fun upsert(settlement: SettlementEntity)

    @Upsert
    suspend fun upsertAll(settlements: List<SettlementEntity>)

    @Upsert
    suspend fun upsertAllocation(allocation: SettlementAllocationEntity)

    @Upsert
    suspend fun upsertAllocations(allocations: List<SettlementAllocationEntity>)

    @Query("SELECT * FROM settlements WHERE id = :id")
    suspend fun getById(id: String): SettlementEntity?

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM settlements")
    suspend fun allForSync(): List<SettlementEntity>

    @Query(
        """
        SELECT * FROM settlements
        WHERE group_id = :groupId AND deleted_at IS NULL
        ORDER BY settled_at DESC
        """
    )
    fun observeByGroup(groupId: String): Flow<List<SettlementEntity>>

    @Query("SELECT * FROM settlement_allocations WHERE settlement_id = :settlementId")
    suspend fun allocationsForSettlement(settlementId: String): List<SettlementAllocationEntity>

    @Query("SELECT * FROM settlement_allocations WHERE share_id = :shareId")
    suspend fun allocationsForShare(shareId: String): List<SettlementAllocationEntity>

    /**
     * Total applied to a share across all settlements. AC-INV-002: this equals
     * `share_owed_subunits - remaining_subunits` for that share.
     */
    @Query(
        """
        SELECT COALESCE(SUM(applied_amount_subunits), 0)
        FROM settlement_allocations WHERE share_id = :shareId
        """
    )
    suspend fun sumAppliedToShare(shareId: String): Long

    // --- Cross-table writes (declared here so apply/void stay atomic) ----------------------------

    /** Add [delta] to a share's remaining (negative to pay down, positive to restore on void). */
    @Query("UPDATE shares SET remaining_subunits = remaining_subunits + :delta, updated_at = :ts, row_version = row_version + 1 WHERE id = :shareId")
    suspend fun addToShareRemaining(shareId: String, delta: Long, ts: Long)

    /**
     * Recompute one non-deleted expense's denormalized status from its shares (AC-INV-003): SETTLED
     * iff every share is fully paid, else ACTIVE. Self-contained so it runs inside the transaction.
     */
    @Query(
        """
        UPDATE expenses
        SET status = CASE
                WHEN (SELECT COALESCE(SUM(remaining_subunits), 0) FROM shares WHERE expense_id = :expenseId) = 0
                THEN 'SETTLED' ELSE 'ACTIVE' END,
            updated_at = :ts
        WHERE id = :expenseId AND deleted_at IS NULL
        """
    )
    suspend fun recomputeExpenseStatus(expenseId: String, ts: Long)

    @Query("UPDATE settlements SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    // --- Transactions ---------------------------------------------------------------------------

    /**
     * Record a settlement, write its allocations, pay down each allocated share, and recompute the
     * affected expenses' status — atomically (03 §4.3.1). [allocations] carry the per-share applied
     * amounts; [affectedExpenseIds] are the distinct parent expenses to re-status.
     */
    @Transaction
    suspend fun applySettlement(
        settlement: SettlementEntity,
        allocations: List<SettlementAllocationEntity>,
        affectedExpenseIds: List<String>,
        ts: Long,
    ) {
        upsert(settlement)
        upsertAllocations(allocations)
        for (a in allocations) addToShareRemaining(a.shareId, -a.appliedAmountSubunits, ts)
        for (id in affectedExpenseIds) recomputeExpenseStatus(id, ts)
    }

    /**
     * Void a settlement: restore each share it paid, recompute the affected expenses' status, and
     * soft-delete the settlement — atomically (04 §2.3 `void_settlement`).
     */
    @Transaction
    suspend fun voidSettlement(
        settlementId: String,
        allocations: List<SettlementAllocationEntity>,
        affectedExpenseIds: List<String>,
        ts: Long,
    ) {
        for (a in allocations) addToShareRemaining(a.shareId, a.appliedAmountSubunits, ts)
        for (id in affectedExpenseIds) recomputeExpenseStatus(id, ts)
        softDelete(settlementId, ts)
    }
}
