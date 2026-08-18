package app.splitevenly.domain.settlement

/**
 * The unsettled balance of a single share, in the currency's minor units (e.g. cents).
 *
 * [remainingSubunits] is what is still owed on this share; 0 means fully settled.
 */
data class ShareBalance(
    val shareId: String,
    val currency: String,
    val remainingSubunits: Long,
)

/**
 * How much of a payment was applied to one share — spec 03-business-rules.md §4.3.1.
 */
data class Allocation(
    val shareId: String,
    val appliedSubunits: Long, // how much of this share the payment covers
)

/**
 * Distributes a same-currency [paymentAmountSubunits] across [shares] — spec
 * 03-business-rules.md §4.1 and §4.3.1 (the normative pseudocode). Cross-currency
 * settlement (§4.3.2) is deferred to v1.1.
 *
 * [shares] are already ordered by the caller (oldest expense first, §4.2). The payment
 * walks them in order, applying `min(remaining, paymentLeft)` to each and stopping once
 * the payment is exhausted. Shares with no remaining balance produce no allocation.
 *
 * Invariant: `sum(appliedSubunits) == min(paymentAmountSubunits, sum(max(remainingSubunits, 0)))`.
 *
 * The `max(…, 0)` is the contract, not a hedge. A negative remaining is a **credit** — the
 * state `Overpayment` exists to describe — and a payment does not pay a credit down: that
 * share is skipped, never netted against a sibling. Netting would spend one expense's
 * overpayment on another expense's debt, which is a decision this allocator does not get to
 * make. Every production caller filters `remaining > 0` before calling (all three outstanding
 * queries in `ShareDao` end that way), so the case is latent; the invariant is written to hold
 * for any input rather than only for a set someone remembered to filter.
 *
 * @throws IllegalArgumentException if the shares span more than one currency — the
 *   same-currency path requires a single uniform currency (the cross-FX path handles
 *   mixed currencies in v1.1).
 */
fun allocateSameCurrency(
    paymentAmountSubunits: Long,
    shares: List<ShareBalance>,
): List<Allocation> {
    require(shares.map { it.currency }.distinct().size <= 1) {
        "allocateSameCurrency requires a single currency; got ${shares.map { it.currency }.distinct()}"
    }

    var remaining = paymentAmountSubunits
    val allocations = mutableListOf<Allocation>()
    for (share in shares) {
        if (remaining <= 0L) break
        val applied = minOf(remaining, share.remainingSubunits)
        if (applied > 0L) { // a share with nothing owed, or holding a credit, is skipped (never netted)
            allocations += Allocation(shareId = share.shareId, appliedSubunits = applied)
            remaining -= applied
        }
    }
    return allocations
}
