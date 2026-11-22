package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.settlement.ExpensePayment
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.PaymentEditPreview
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
     * Every live payment that touched [expenseId], "Settle up" payments included, with what each put on
     * this bill. Newest first.
     */
    fun observeExpensePayments(expenseId: ExpenseId): Flow<List<ExpensePayment>>

    /**
     * What [editSettlement] would do with the same arguments, without writing anything. Drives the
     * read-only "Now covers" preview and the ceiling hint on the edit sheet.
     */
    suspend fun previewSettlementEdit(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        scopeExpenseId: ExpenseId? = null,
    ): AppResult<PaymentEditPreview>

    /**
     * Change a recorded payment's amount (`docs/features/one-time-splits.md` Part B). Only its two
     * parties may: [actor] must be the payer or the one owed, or the result is [AppError.NotAuthorized]
     * (the server refuses it too). Voids the payment and records a replacement in one transaction, so a
     * rejected edit leaves the original untouched.
     *
     * With [scopeExpenseId] null, [newAmountSubunits] is the new total and re-spreads by
     * [app.splitevenly.domain.settlement.respreadPayment]: a cut comes off the newest covered bill first,
     * an increase fills the oldest unpaid one. With it set, it is that bill's portion and no other bill
     * changes. Never above what is owed. Writes one SETTLEMENT_EDITED history row per affected bill.
     */
    suspend fun editSettlement(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        actor: UserId? = null,
        scopeExpenseId: ExpenseId? = null,
    ): AppResult<SettlementRecord>

    /**
     * Remove a payment ("I didn't pay this" / "I didn't get this"): soft-delete it so its allocations stop
     * counting (04 §2.3 `void_settlement`). Party-only, like [editSettlement]; [actor] null skips the check
     * for internal callers. Writes a SETTLEMENT_REMOVED history row per bill it covered.
     */
    suspend fun voidSettlement(
        settlementId: SettlementId,
        actor: UserId? = null,
    ): AppResult<Unit>
}
