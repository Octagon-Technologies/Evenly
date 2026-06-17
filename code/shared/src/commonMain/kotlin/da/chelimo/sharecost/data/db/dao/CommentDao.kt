package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.CommentEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `comments` (06 §3). */
@Dao
interface CommentDao {

    @Upsert
    suspend fun upsert(comment: CommentEntity)

    @Upsert
    suspend fun upsertAll(comments: List<CommentEntity>)

    /** Live thread for an expense, oldest first, tombstones hidden. */
    @Query("SELECT * FROM comments WHERE expense_id = :expenseId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun observeByExpense(expenseId: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE id = :id")
    suspend fun getById(id: String): CommentEntity?

    /** Soft-delete so the removal syncs to other devices instead of resurrecting on the next pull. */
    @Query("UPDATE comments SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM comments")
    suspend fun allForSync(): List<CommentEntity>
}
