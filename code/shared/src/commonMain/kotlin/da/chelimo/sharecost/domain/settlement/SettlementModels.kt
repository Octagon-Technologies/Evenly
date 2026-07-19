package da.chelimo.sharecost.domain.settlement

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.SettlementId
import da.chelimo.sharecost.core.id.UserId

/**
 * Domain view of a settlement (02 §3.9): a payment event from [fromUserId] to [toUserId] that pays
 * down one or more of their shares. The allocation detail lives in the data layer; this is the
 * summary the UI lists (05 §8).
 */
data class SettlementRecord(
    val id: SettlementId,
    val groupId: GroupId,
    val fromUserId: UserId,
    val toUserId: UserId,
    val paymentCurrency: String,
    val paymentAmountSubunits: Long,
    val settledAt: Long,
    val notes: String?,
    /** Which payment app was used ("VENMO"…), if any — surfaced on the expense's payments list. */
    val paymentApp: String? = null,
    /** Who recorded this payment (the actor) — shown when reviewing a possible double payment. */
    val createdBy: UserId? = null,
)

/**
 * Input to [da.chelimo.sharecost.domain.repository.SettlementRepository.applySettlement]
 * (04 §2.3 `apply_settlement`). The repository computes the per-share allocations itself
 * (03 §4.3.1, oldest-share-first) — same currency only for MVP; cross-FX is v1.1 (03 §4.3.2).
 */
data class NewSettlement(
    val groupId: GroupId,
    val fromUserId: UserId,
    val toUserId: UserId,
    val paymentCurrency: String,
    val paymentAmountSubunits: Long,
    val createdBy: UserId,
    /**
     * When set, the payment is confined to **this one expense's** outstanding shares (the single-expense
     * "Settle 'X'" sheet). Null = relationship-wide: allocate oldest-expense-first across everything the
     * debtor owes the creditor (the "settle a person" flow). Scoping matters for *partial* payments — a
     * partial relationship-wide payment lands on the oldest expense, which is not the expense the
     * single-expense sheet is showing.
     */
    val expenseId: ExpenseId? = null,
    /**
     * When set (and [expenseId] is null), the payment is confined to **these** expenses' outstanding
     * shares — the one-page "settle with X" flow, where the user ticks which specific expenses they're
     * paying off (the Uber but not yet the Airbnb). Allocation still runs oldest-first *within* the
     * selected set. Null = all outstanding to the creditor (the pay-everything default). [expenseId]
     * (single) takes precedence when both are set.
     */
    val expenseIds: List<ExpenseId>? = null,
    val notes: String? = null,
    val paymentApp: String? = null,
    /** Whether a payment-app deep link was opened for this settlement, and whether the user then
     *  confirmed it went through (03 §5.2). [deepLinkSucceeded] is null when no link was attempted. */
    val deepLinkAttempted: Boolean = false,
    val deepLinkSucceeded: Boolean? = null,
)
