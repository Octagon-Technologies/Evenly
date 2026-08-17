package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ExpenseBlockedUserEntity

/** DAO for `expense_blocked_users` (CHAT_MODERATION_SPEC.md). */
@Dao
interface ExpenseBlockedUserDao {
    @Upsert
    suspend fun upsert(row: ExpenseBlockedUserEntity)

    @Upsert
    suspend fun upsertAll(rows: List<ExpenseBlockedUserEntity>)

    @Query("SELECT * FROM expense_blocked_users WHERE id = :id")
    suspend fun getById(id: String): ExpenseBlockedUserEntity?

    /** Soft-delete so the unblock syncs to other devices instead of resurrecting on the next pull. */
    @Query("UPDATE expense_blocked_users SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun unblock(
        id: String,
        now: Long,
    )

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM expense_blocked_users")
    suspend fun allForSync(): List<ExpenseBlockedUserEntity>
}
