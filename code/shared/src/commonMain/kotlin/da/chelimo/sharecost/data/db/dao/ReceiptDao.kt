package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `receipts` (06 §5.1). */
@Dao
interface ReceiptDao {

    @Upsert
    suspend fun upsert(receipt: ReceiptEntity)

    @Upsert
    suspend fun upsertAll(receipts: List<ReceiptEntity>)

    /** Live receipts for an expense, newest first, tombstones hidden. */
    @Query("SELECT * FROM receipts WHERE expense_id = :expenseId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeByExpense(expenseId: String): Flow<List<ReceiptEntity>>

    /** Live total bytes of a group's live (non-tombstoned) receipts — drives the Storage section. */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM receipts WHERE group_id = :groupId AND deleted_at IS NULL")
    fun observeTotalSizeBytesByGroup(groupId: String): Flow<Long>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getById(id: String): ReceiptEntity?

    /** Soft-delete (the Storage object is removed separately by the repository). */
    @Query("UPDATE receipts SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM receipts")
    suspend fun allForSync(): List<ReceiptEntity>
}
