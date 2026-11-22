package app.splitevenly.domain.settlement

/**
 * One share a recorded payment currently pays toward. [expenseOrder] sorts bills oldest to newest
 * (the expense date, then a stable tiebreak) so a cut can come off the newest bill first.
 */
data class CoveredPortion(
    val shareId: String,
    val expenseId: String,
    val expenseOrder: String,
    val appliedSubunits: Long,
)

/** Why [respreadPayment] refused an edit. */
enum class PaymentEditRefusal {
    /** The new amount is zero or negative. Removing a payment is a void, not an edit to 0. */
    NotPositive,

    /** The new amount is more than the payer still owes, so it would create an overpayment. */
    AboveCeiling,

    /** A single-bill edit named a bill this payment does not cover. */
    NotCovered,
}

/** The outcome of [respreadPayment]: the new per-share split, or why it was refused. */
sealed interface PaymentRespread {
    data class Ok(
        val newTotalSubunits: Long,
        /** Share id to the amount this payment will put on it. Shares at zero are omitted. */
        val appliedByShare: Map<String, Long>,
    ) : PaymentRespread

    data class Refused(
        val reason: PaymentEditRefusal,
        /** The most the edited amount may be (the total, or the one bill's portion when scoped). */
        val ceilingSubunits: Long,
    ) : PaymentRespread
}

/**
 * Re-spread a recorded payment after one of its two parties changes the amount
 * (`docs/features/one-time-splits.md` Part B).
 *
 * A reduction comes off the **newest** covered bill first, and each bill can only go down from what
 * this payment had put on it, so money never moves onto a bill the payment did not cover. An
 * increase keeps what is already there and fills the **oldest** unpaid share first, the same order
 * [allocateSameCurrency] uses for a new payment, capped at what is still owed.
 *
 * [outstandingOldestFirst] is the pair's live remaining per share *with this payment still applied*,
 * which is exactly the room an increase has on top of the current allocations.
 *
 * With [scopeExpenseId] set (an edit from one bill's screen), [newAmountSubunits] is that bill's
 * portion and every other bill keeps exactly what it had.
 */
fun respreadPayment(
    current: List<CoveredPortion>,
    outstandingOldestFirst: List<ShareBalance>,
    newAmountSubunits: Long,
    scopeExpenseId: String? = null,
): PaymentRespread {
    val inScope = if (scopeExpenseId == null) current else current.filter { it.expenseId == scopeExpenseId }
    val fixed = if (scopeExpenseId == null) emptyList() else current.filter { it.expenseId != scopeExpenseId }
    val room =
        if (scopeExpenseId == null) {
            outstandingOldestFirst
        } else {
            val scopeShares = inScope.mapTo(HashSet()) { it.shareId }
            outstandingOldestFirst.filter { it.shareId in scopeShares }
        }
    val oldInScope = inScope.sumOf { it.appliedSubunits }
    val ceiling = oldInScope + room.sumOf { it.remainingSubunits.coerceAtLeast(0L) }

    if (scopeExpenseId != null && inScope.isEmpty()) {
        return PaymentRespread.Refused(PaymentEditRefusal.NotCovered, 0L)
    }
    if (newAmountSubunits <= 0L) return PaymentRespread.Refused(PaymentEditRefusal.NotPositive, ceiling)
    if (newAmountSubunits > ceiling) return PaymentRespread.Refused(PaymentEditRefusal.AboveCeiling, ceiling)

    val applied = LinkedHashMap<String, Long>()
    current.forEach { applied[it.shareId] = (applied[it.shareId] ?: 0L) + it.appliedSubunits }

    if (newAmountSubunits < oldInScope) {
        var cut = oldInScope - newAmountSubunits
        for (p in inScope.sortedWith(compareByDescending<CoveredPortion> { it.expenseOrder }.thenByDescending { it.shareId })) {
            if (cut <= 0L) break
            val take = minOf(cut, applied[p.shareId] ?: 0L)
            applied[p.shareId] = (applied[p.shareId] ?: 0L) - take
            cut -= take
        }
    } else if (newAmountSubunits > oldInScope) {
        allocateSameCurrency(newAmountSubunits - oldInScope, room).forEach { a ->
            applied[a.shareId] = (applied[a.shareId] ?: 0L) + a.appliedSubunits
        }
    }

    val newTotal = fixed.sumOf { it.appliedSubunits } + newAmountSubunits
    return PaymentRespread.Ok(newTotal, applied.filterValues { it > 0L })
}

/** True when [userId] is one of the two people in a payment: the one paying back or the one owed. */
fun isPaymentParty(
    fromUserId: String,
    toUserId: String,
    userId: String?,
): Boolean = userId != null && (userId == fromUserId || userId == toUserId)
