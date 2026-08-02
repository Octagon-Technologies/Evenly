package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ItemShareEntity
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

    /** All active memberships across a group's bills — input to the group-wide unresolved-bills computation. */
    @Query("SELECT * FROM item_shares WHERE group_id = :groupId AND deleted_at IS NULL")
    fun observeByGroup(groupId: String): Flow<List<ItemShareEntity>>

    /** Every local row incl. tombstones — the push side of sync. */
    @Query("SELECT * FROM item_shares")
    suspend fun allForSync(): List<ItemShareEntity>

    /** Soft-delete memberships (Rule 1): leave a share, or tombstone the shares of a removed item. */
    @Query("UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, ts: Long)

    /** Tombstone every active membership on an item — used when the item is removed from the bill. */
    @Query("UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE item_id IN (:itemIds) AND deleted_at IS NULL")
    suspend fun softDeleteByItems(itemIds: List<String>, ts: Long)

    /** Zero every active CLAIM on an item — the claims half of the atomic servings rebuild (#15). */
    @Query("UPDATE item_claims SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE item_id = :itemId AND deleted_at IS NULL")
    suspend fun clearClaimsForItem(itemId: String, now: Long)

    /**
     * Atomically re-slice an item into servings (#15): zero its claims, tombstone its existing portions,
     * and write [newPortions] — all in ONE transaction. The "Who had what?" per-serving assignment used to
     * do this as a sequence of separate repo calls in a navigation-scoped coroutine, so navigating away
     * mid-flight could cancel AFTER the teardown but BEFORE the rebuild, wiping the item's whole assignment
     * (and other devices could pull that transient empty state). Doing it in one Room transaction makes it
     * all-or-nothing across a cancel or process death. Claims live on ItemClaimDao, but the transaction
     * must span both tables, so the single claim-clearing query lives here. The caller re-derives shares
     * afterwards — they're a self-healing materialization, not part of this atomic unit.
     */
    @Transaction
    suspend fun setServings(itemId: String, newPortions: List<ItemShareEntity>, now: Long) {
        clearClaimsForItem(itemId, now)
        softDeleteByItems(listOf(itemId), now)
        if (newPortions.isNotEmpty()) upsertAll(newPortions)
    }
}
