package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ItemShareEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `item_shares` — the auto-union shared-membership set of a "Split the bill" line. Additive:
 * adding a member upserts a row; leaving soft-deletes it (Rule 1). The active rows per item are the set.
 */
@Dao
interface ItemShareDao {

    @Upsert
    suspend fun upsert(share: ItemShareEntity)

    @Upsert
    suspend fun upsertAll(shares: List<ItemShareEntity>)

    /** All **active** share memberships on a bill (excludes tombstones) — input to the share derivation. */
    @Query("SELECT * FROM item_shares WHERE expense_id = :expenseId AND deleted_at IS NULL")
    suspend fun getByExpense(expenseId: String): List<ItemShareEntity>

    /** Reactive active memberships for the live claim screen. */
    @Query("SELECT * FROM item_shares WHERE expense_id = :expenseId AND deleted_at IS NULL")
    fun observeByExpense(expenseId: String): Flow<List<ItemShareEntity>>

    /** This user's active membership in one item's shared split, if any. */
    @Query("SELECT * FROM item_shares WHERE item_id = :itemId AND user_id = :userId AND deleted_at IS NULL LIMIT 1")
    suspend fun getActiveShare(itemId: String, userId: String): ItemShareEntity?

    /** Every local row incl. tombstones — the push side of sync. */
    @Query("SELECT * FROM item_shares")
    suspend fun allForSync(): List<ItemShareEntity>

    /** Soft-delete memberships (Rule 1): leave a share, or tombstone the shares of a removed item. */
    @Query("UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, ts: Long)

    /** Tombstone every active membership on an item — used when the item is removed from the bill. */
    @Query("UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE item_id IN (:itemIds) AND deleted_at IS NULL")
    suspend fun softDeleteByItems(itemIds: List<String>, ts: Long)
}
