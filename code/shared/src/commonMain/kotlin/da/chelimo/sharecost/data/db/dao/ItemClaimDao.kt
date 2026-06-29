package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ItemClaimEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `item_claims` — who claimed what on a "Split the bill" expense. Claims are partitioned by
 * user (each device only writes its own), so live multi-device claiming needs no CAS. Soft-delete only
 * (Rule 1): un-claiming tombstones the row so the removal syncs.
 */
@Dao
interface ItemClaimDao {

    @Upsert
    suspend fun upsert(claim: ItemClaimEntity)

    @Upsert
    suspend fun upsertAll(claims: List<ItemClaimEntity>)

    /** All **active** claims on a bill (excludes tombstones) — the input to the share derivation. */
    @Query("SELECT * FROM item_claims WHERE expense_id = :expenseId AND deleted_at IS NULL")
    suspend fun getByExpense(expenseId: String): List<ItemClaimEntity>

    /** Reactive active claims for the live claim screen (everyone's stakes, updating in real time). */
    @Query("SELECT * FROM item_claims WHERE expense_id = :expenseId AND deleted_at IS NULL")
    fun observeByExpense(expenseId: String): Flow<List<ItemClaimEntity>>

    /** This user's active claim on one item, if any — the toggle/stepper reads it to know the count. */
    @Query("SELECT * FROM item_claims WHERE item_id = :itemId AND user_id = :userId AND deleted_at IS NULL LIMIT 1")
    suspend fun getActiveClaim(itemId: String, userId: String): ItemClaimEntity?

    /** Every local row incl. tombstones — the push side of sync. */
    @Query("SELECT * FROM item_claims")
    suspend fun allForSync(): List<ItemClaimEntity>

    /** Soft-delete claims (Rule 1): un-claim, or tombstone the claims of a removed item. */
    @Query("UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, ts: Long)

    /** Tombstone every active claim on an item — used when the item itself is removed from the bill. */
    @Query("UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE item_id IN (:itemIds) AND deleted_at IS NULL")
    suspend fun softDeleteByItems(itemIds: List<String>, ts: Long)
}
