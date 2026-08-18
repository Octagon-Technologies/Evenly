package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.projection.SettlementCoveredTitleRow
import kotlinx.coroutines.flow.Flow

/** DAO for `settlements` + `settlement_allocations` (02 §3.9). Owns the apply/void transactions. */
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

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM settlements")
    suspend fun allForSync(): List<SettlementEntity>

    @Query(
        """
        SELECT * FROM settlements
        WHERE group_id = :groupId AND deleted_at IS NULL
        ORDER BY settled_at DESC
        """,
    )
    fun observeByGroup(groupId: String): Flow<List<SettlementEntity>>

    /**
     * Non-voided settlements whose payment lands **entirely** on [expenseId]'s shares — the payments
     * recorded against this one expense (via "Settle this"). A relationship-wide settlement that also paid
     * other expenses is deliberately excluded: editing or removing it from an expense screen would
     * silently re-scope another expense's balance. Newest first.
     */
    @Query(
        """
        SELECT st.* FROM settlements st
        WHERE st.deleted_at IS NULL
          AND EXISTS (
            SELECT 1 FROM settlement_allocations sa INNER JOIN shares sh ON sh.id = sa.share_id
            WHERE sa.settlement_id = st.id AND sh.expense_id = :expenseId)
          AND NOT EXISTS (
            SELECT 1 FROM settlement_allocations sa2 INNER JOIN shares sh2 ON sh2.id = sa2.share_id
            WHERE sa2.settlement_id = st.id AND sh2.expense_id <> :expenseId)
        ORDER BY st.settled_at DESC
        """,
    )
    fun observeByExpense(expenseId: String): Flow<List<SettlementEntity>>

    /**
     * Which expenses each non-voided settlement in a group paid toward, one row per (settlement, expense),
     * via `allocations → shares → expenses`. Powers the "what did this payment cover" line in the
     * double-payment review. Voided settlements (soft-deleted) and deleted expenses are excluded; oldest
     * expense first so the caption reads in the order money was applied.
     */
    @Query(
        """
        SELECT DISTINCT sa.settlement_id AS settlement_id, e.title AS title
        FROM settlement_allocations sa
        INNER JOIN settlements st ON st.id = sa.settlement_id
        INNER JOIN shares sh ON sh.id = sa.share_id
        INNER JOIN expenses e ON e.id = sh.expense_id
        WHERE sa.group_id = :groupId AND st.deleted_at IS NULL AND e.deleted_at IS NULL
        ORDER BY e.expense_date ASC
        """,
    )
    fun observeCoveredTitlesByGroup(groupId: String): Flow<List<SettlementCoveredTitleRow>>

    /**
     * The two most recent non-voided payment amounts from [fromUserId] to [toUserId] in [currency],
     * newest first — the input to the double-payment amount-equality gate (P1 #9). A duplicate is
     * almost always the exact same amount logged twice (both sides enter it, or one enters it twice
     * offline); two *different* amounts summing past what was owed is just two real payments, not a
     * double payment, so the caller must not flag on sum alone.
     */
    @Query(
        """
        SELECT payment_amount_subunits FROM settlements
        WHERE group_id = :groupId AND deleted_at IS NULL
          AND from_user_id = :fromUserId AND to_user_id = :toUserId AND payment_currency = :currency
        ORDER BY settled_at DESC
        LIMIT 2
        """,
    )
    suspend fun lastTwoPaymentAmounts(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        currency: String,
    ): List<Long>

    @Query("SELECT * FROM settlement_allocations WHERE settlement_id = :settlementId")
    suspend fun allocationsForSettlement(settlementId: String): List<SettlementAllocationEntity>

    /** Every local allocation — the push side of sync (allocations are synced ground truth). */
    @Query("SELECT * FROM settlement_allocations")
    suspend fun allAllocationsForSync(): List<SettlementAllocationEntity>

    /** Total applied to a share across all non-voided settlements (AC-INV-002: equals owed − remaining). */
    @Query(
        """
        SELECT COALESCE(SUM(sa.applied_amount_subunits), 0)
        FROM settlement_allocations sa INNER JOIN settlements st ON st.id = sa.settlement_id
        WHERE sa.share_id = :shareId AND st.deleted_at IS NULL
        """,
    )
    suspend fun sumAppliedToShare(shareId: String): Long

    @Query("UPDATE settlements SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(
        id: String,
        ts: Long,
    )

    // --- Transactions ---------------------------------------------------------------------------

    /** A share's derived remaining (owed − Σ applied of non-voided settlements) — the fresh, in-txn read. */
    @Query(
        """
        SELECT s.share_owed_subunits - COALESCE((
            SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
            INNER JOIN settlements st ON st.id = sa.settlement_id
            WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0)
        FROM shares s WHERE s.id = :shareId
        """,
    )
    suspend fun derivedRemainingForShare(shareId: String): Long?

    /**
     * Record a settlement and its allocations atomically (03 §4.3.1), guarding against over-apply (P1 #9b):
     * re-read each target share's derived remaining INSIDE the transaction and REFUSE if any allocation
     * exceeds it. The caller validates payment ≤ outstanding beforehand, but a concurrent settlement can
     * commit between that read and this write (a same-device double-fire) and push a share negative; the
     * fresh in-txn read closes that race. (Cross-device doubles surface via the Balances overpayment
     * banner instead.) Shares are **not** mutated — remaining derives from these allocations on read.
     * Returns false (nothing written) when the guard trips, true when applied.
     */
    @Transaction
    suspend fun applySettlement(
        settlement: SettlementEntity,
        allocations: List<SettlementAllocationEntity>,
    ): Boolean {
        for (a in allocations) {
            if (a.appliedAmountSubunits > (derivedRemainingForShare(a.shareId) ?: 0L)) return false
        }
        upsert(settlement)
        upsertAllocations(allocations)
        return true
    }

    /**
     * Void a settlement by soft-deleting it (04 §2.3 `void_settlement`). Its allocations stay as
     * tombstoned history but are excluded from the derived remaining (the join filters
     * `settlements.deleted_at IS NULL`), so the paid-down amount is restored automatically — no
     * per-share restore, no status recompute.
     */
    @Transaction
    suspend fun voidSettlement(
        settlementId: String,
        ts: Long,
    ) {
        softDelete(settlementId, ts)
    }

    /**
     * Correct a payment: void [oldSettlementId] and record [settlement] + [allocations] in ONE
     * transaction.
     *
     * `editSettlement` used to do this as two separate writes. A crash between them left the payment
     * voided with no replacement — money the debtor had actually paid silently became owed again, and
     * nothing on either device said so.
     *
     * **The order matters and must not be flipped.** Voiding first is what frees the old payment's
     * allocations, so the over-apply guard below sees the same ceiling the caller validated against.
     * Writing the new payment *before* voiding the old would look safer on a crash but is worse: the
     * two would both be live and the expense would read as double-paid, which is the harder error to
     * notice and the one that stops someone chasing a debt they are still owed.
     *
     * Returns false with nothing written (the transaction rolls back, so the old payment survives)
     * when the guard trips.
     */
    @Transaction
    suspend fun replaceSettlement(
        oldSettlementId: String,
        voidedAt: Long,
        settlement: SettlementEntity,
        allocations: List<SettlementAllocationEntity>,
    ): Boolean {
        softDelete(oldSettlementId, voidedAt)
        for (a in allocations) {
            if (a.appliedAmountSubunits > (derivedRemainingForShare(a.shareId) ?: 0L)) {
                // Roll the void back with it. Room only rolls a @Transaction back on a throw, so the
                // refusal has to be one — caught by the caller's own guard, never surfaced as a crash.
                throw SettlementReplaceRefused()
            }
        }
        upsert(settlement)
        upsertAllocations(allocations)
        return true
    }
}

/** Internal control flow for [SettlementDao.replaceSettlement]'s rollback. Never escapes the DAO layer. */
internal class SettlementReplaceRefused : Exception("settlement replacement would over-apply")
