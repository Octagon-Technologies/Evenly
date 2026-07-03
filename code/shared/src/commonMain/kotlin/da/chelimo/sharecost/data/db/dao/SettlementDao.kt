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

    /**
     * Non-voided settlements whose payment lands **entirely** on [expenseId]'s shares — the payments
     * recorded against this one expense (via "Settle this"). A relationship-wide settlement that also paid
     * other expenses is deliberately excluded: editing or removing it from an expense screen would
     * silently re-scope another expense's balance. Newest first.
     */
    @Query(
        """
        SELECT st.* FROM settlements st
        WHERE st.deleted_at IS NULL
          AND EXISTS (
            SELECT 1 FROM settlement_allocations sa INNER JOIN shares sh ON sh.id = sa.share_id
            WHERE sa.settlement_id = st.id AND sh.expense_id = :expenseId)
          AND NOT EXISTS (
            SELECT 1 FROM settlement_allocations sa2 INNER JOIN shares sh2 ON sh2.id = sa2.share_id
            WHERE sa2.settlement_id = st.id AND sh2.expense_id <> :expenseId)
        ORDER BY st.settled_at DESC
        """
    )
    fun observeByExpense(expenseId: String): Flow<List<SettlementEntity>>

    @Query("SELECT * FROM settlement_allocations WHERE settlement_id = :settlementId")
    suspend fun allocationsForSettlement(settlementId: String): List<SettlementAllocationEntity>

    @Query("SELECT * FROM settlement_allocations WHERE share_id = :shareId")
    suspend fun allocationsForShare(shareId: String): List<SettlementAllocationEntity>

    /** Every local allocation — the push side of sync (allocations are synced ground truth). */
    @Query("SELECT * FROM settlement_allocations")
    suspend fun allAllocationsForSync(): List<SettlementAllocationEntity>

    /** Total applied to a share across all non-voided settlements (AC-INV-002: equals owed − remaining). */
    @Query(
        """
        SELECT COALESCE(SUM(sa.applied_amount_subunits), 0)
        FROM settlement_allocations sa INNER JOIN settlements st ON st.id = sa.settlement_id
        WHERE sa.share_id = :shareId AND st.deleted_at IS NULL
        """
    )
    suspend fun sumAppliedToShare(shareId: String): Long

    @Query("UPDATE settlements SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    // --- Transactions ---------------------------------------------------------------------------

    /**
     * Record a settlement and its allocations atomically (03 §4.3.1). That is the whole write: shares
     * are **not** mutated — `remaining` is derived from these allocations on read, so there is no
     * denormalized tally to update and no expense status to recompute.
     */
    @Transaction
    suspend fun applySettlement(
        settlement: SettlementEntity,
        allocations: List<SettlementAllocationEntity>,
    ) {
        upsert(settlement)
        upsertAllocations(allocations)
    }

    /**
     * Void a settlement by soft-deleting it (04 §2.3 `void_settlement`). Its allocations stay as
     * tombstoned history but are excluded from the derived remaining (the join filters
     * `settlements.deleted_at IS NULL`), so the paid-down amount is restored automatically — no
     * per-share restore, no status recompute.
     */
    @Transaction
    suspend fun voidSettlement(settlementId: String, ts: Long) {
        softDelete(settlementId, ts)
    }
}
