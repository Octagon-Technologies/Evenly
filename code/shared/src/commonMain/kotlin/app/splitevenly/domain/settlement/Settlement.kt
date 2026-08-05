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
 * Invariant: `sum(appliedSubunits) == min(paymentAmountSubunits, sum(remainingSubunits))`.
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
        if (applied > 0L) {
            allocations += Allocation(shareId = share.shareId, appliedSubunits = applied)
            remaining -= applied
        }
    }
    return allocations
}
