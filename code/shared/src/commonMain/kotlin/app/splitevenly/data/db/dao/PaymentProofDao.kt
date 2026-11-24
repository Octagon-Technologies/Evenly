package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.PaymentProofEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `payment_proofs` (docs/features/payment-proof.md). */
@Dao
interface PaymentProofDao {
    @Upsert
    suspend fun upsert(proof: PaymentProofEntity)

    @Upsert
    suspend fun upsertAll(proofs: List<PaymentProofEntity>)

    /** A group's live proofs, newest first, so the first per chain is the current one. */
    @Query("SELECT * FROM payment_proofs WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeByGroup(groupId: String): Flow<List<PaymentProofEntity>>

    @Query("SELECT * FROM payment_proofs WHERE settlement_id = :chainId AND deleted_at IS NULL")
    suspend fun liveForChain(chainId: String): List<PaymentProofEntity>

    @Query("SELECT * FROM payment_proofs WHERE id = :id")
    suspend fun getById(id: String): PaymentProofEntity?

    /** Soft-delete (the Storage object is removed separately by the repository). */
    @Query("UPDATE payment_proofs SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM payment_proofs")
    suspend fun allForSync(): List<PaymentProofEntity>
}
