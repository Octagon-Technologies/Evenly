package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.SettlementRecord
import kotlinx.coroutines.flow.Flow

/**
 * Settlements (06 §3.1). [applySettlement] distributes the payment across the debtor's outstanding
 * shares to the creditor, oldest first (03 §4.3.1), decrements `shares.remaining_subunits`, and
 * recomputes the affected expenses' status — all in one Room transaction. Same-currency only for MVP
 * (cross-FX is v1.1, 03 §4.3.2). Server push is deferred to S-1.
 */
interface SettlementRepository {

    /** Non-voided settlements in a group, newest first (05 §8). */
    fun observeSettlements(groupId: GroupId): Flow<List<SettlementRecord>>

    /**
     * The expense titles each non-voided settlement paid toward, keyed by settlement id. Powers the
     * "what did this payment cover" line in the double-payment review (P1 #9).
     */
    fun observeCoveredExpenseTitles(groupId: GroupId): Flow<Map<SettlementId, List<String>>>

    /** Record a payment and apply it to the debtor→creditor outstanding shares. */
    suspend fun applySettlement(input: NewSettlement): AppResult<SettlementRecord>

    /** The payments recorded against a single expense (single-expense settlements), newest first. */
    fun observePaymentsForExpense(expenseId: ExpenseId): Flow<List<SettlementRecord>>

    /**
     * Correct a recorded payment's amount in place: void the old one and re-record [newAmountSubunits]
     * against the same expense / payer→payee, preserving app + notes. Guarded like recording —
     * [newAmountSubunits] must be > 0 and can't exceed what the payer owes on the expense, so a correction
     * can never manufacture an overpayment. The pre-check runs *before* voiding, so a rejected edit leaves
     * the existing payment untouched. Only single-expense payments are editable this way.
     */
    suspend fun editSettlement(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        actor: UserId? = null,
    ): AppResult<SettlementRecord>

    /** Void a settlement: restore the shares it paid and recompute status (04 §2.3 `void_settlement`). */
    suspend fun voidSettlement(settlementId: SettlementId): AppResult<Unit>
}
