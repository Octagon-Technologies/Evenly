package app.splitevenly.domain.settlement

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId

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
    /** The first payment of this one's edit chain; null when it was never edited. */
    val originalSettlementId: SettlementId? = null,
) {
    /** The id a payment proof hangs off: stable across edits, which void and replace the row. */
    val chainId: String get() = (originalSettlementId ?: id).value
}

/**
 * Input to [app.splitevenly.domain.repository.SettlementRepository.applySettlement]
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
    /**
     * When the money changed hands, if not now: a payment recorded the day after it happened. Epoch
     * millis; clamped to now, since a payment can't be dated in the future.
     */
    val settledAt: Long? = null,
)

/**
 * A payment as one bill's screen sees it: the whole payment plus what landed on this bill.
 * [billCount] > 1 means it came from "Settle up" and also paid other bills.
 */
data class ExpensePayment(
    val record: SettlementRecord,
    val appliedHereSubunits: Long,
    val billCount: Int,
)

/** One bill in a payment-edit preview: what this payment put on it before and after the edit. */
data class PaymentEditPreviewLine(
    val expenseId: ExpenseId,
    val title: String,
    val expenseDate: String,
    val beforeSubunits: Long,
    val afterSubunits: Long,
)

/**
 * What an edit to [newTotalSubunits] would do, before it is saved. [refusal] is set when it can't be
 * saved; [ceilingSubunits] is then the most the edited amount may be.
 */
data class PaymentEditPreview(
    val oldTotalSubunits: Long,
    val newTotalSubunits: Long,
    val lines: List<PaymentEditPreviewLine>,
    val refusal: PaymentEditRefusal? = null,
    val ceilingSubunits: Long = 0L,
)
