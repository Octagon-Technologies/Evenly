package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `settlements` + `settlement_allocations` (02 §3.9). */
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
}
