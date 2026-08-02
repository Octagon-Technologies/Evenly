package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.SupersededNoticeEntity
import kotlinx.coroutines.flow.Flow

/** Device-local one-sided "your split edit was superseded" notices (see [SupersededNoticeEntity]). */
@Dao
interface SupersededNoticeDao {
    @Upsert
    suspend fun upsert(notice: SupersededNoticeEntity)

    /** Live, undismissed notices for a group — drives the dismissible "review?" banner. */
    @Query("SELECT * FROM superseded_notices WHERE group_id = :groupId AND dismissed = 0 ORDER BY created_at DESC")
    fun observeForGroup(groupId: String): Flow<List<SupersededNoticeEntity>>

    /** The live notice for one expense, if any (drives the banner on the expense detail). */
    @Query("SELECT * FROM superseded_notices WHERE expense_id = :expenseId AND dismissed = 0 LIMIT 1")
    fun observeForExpense(expenseId: String): Flow<SupersededNoticeEntity?>

    @Query("UPDATE superseded_notices SET dismissed = 1 WHERE expense_id = :expenseId")
    suspend fun dismiss(expenseId: String)
}
