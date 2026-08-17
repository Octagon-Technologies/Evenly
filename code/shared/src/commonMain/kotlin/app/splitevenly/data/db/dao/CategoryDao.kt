package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `categories` — per-group editable categories with copy-on-write defaults. */
@Dao
interface CategoryDao {
    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    /** Live, ordered categories for a group, tombstones hidden. Empty ⇒ the group uses code defaults. */
    @Query("SELECT * FROM categories WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY sort_order ASC, created_at ASC")
    fun observeByGroup(groupId: String): Flow<List<CategoryEntity>>

    /** Whether this group has any materialized rows yet (drives copy-on-write materialization). */
    @Query("SELECT COUNT(*) FROM categories WHERE group_id = :groupId AND deleted_at IS NULL")
    suspend fun countActiveInGroup(groupId: String): Int

    /** One live category by its per-group [key] (the value stored on `expenses.category_id`). */
    @Query("SELECT * FROM categories WHERE group_id = :groupId AND key = :key AND deleted_at IS NULL LIMIT 1")
    suspend fun getByKey(
        groupId: String,
        key: String,
    ): CategoryEntity?

    /** Largest sort_order currently used in a group (so a new category appends to the end). */
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM categories WHERE group_id = :groupId AND deleted_at IS NULL")
    suspend fun maxSortOrder(groupId: String): Long

    /** Soft-delete one category (Rule 1 — never hard-delete user data). Guarded to the LIVE row:
     *  uniqueness on `(group_id, key)` is partial over active rows, so a reused key legitimately has an
     *  old tombstone beside it, and re-stamping that tombstone would re-push a row nothing changed. */
    @Query(
        "UPDATE categories SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE group_id = :groupId AND key = :key AND deleted_at IS NULL",
    )
    suspend fun softDelete(
        groupId: String,
        key: String,
        now: Long,
    )

    /** Every local row — the push side of sync (includes tombstones so deletions propagate). */
    @Query("SELECT * FROM categories")
    suspend fun allForSync(): List<CategoryEntity>
}
