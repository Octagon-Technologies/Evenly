package da.chelimo.sharecost.domain.balance

import da.chelimo.sharecost.core.id.UserId

/**
 * A net amount [debtorUserId] owes [creditorUserId] in a single [currency].
 *
 * Always strictly positive: a zero or negative net never produces a [Debt].
 */
data class Debt(
    val debtorUserId: UserId,
    val creditorUserId: UserId,
    val currency: String,
    val amountSubunits: Long,
)

/**
 * Collapses [shares] into bilateral debts per (debtor, creditor, currency) — spec
 * 03-business-rules.md §2.2, principle D-02.
 *
 * Each unordered pair of users is netted independently within each currency. The
 * engine NEVER simplifies across triangles and NEVER nets across currencies: a
 * triangle of mutual debts stays a triangle, and the same direction in two
 * currencies stays two rows.
 */
fun buildBilateralBalances(shares: List<Share>): List<Debt> {
    // Sum remaining owed within each (currency, payer, participant) direction.
    val owedByDirection = mutableMapOf<Triple<String, UserId, UserId>, Long>()
    for (share in shares) {
        if (share.remainingSubunits <= 0L) continue
        val key = Triple(share.currency, share.payerUserId, share.participantUserId)
        owedByDirection[key] = (owedByDirection[key] ?: 0L) + share.remainingSubunits
    }

    val debts = mutableListOf<Debt>()
    val handled = mutableSetOf<Triple<String, UserId, UserId>>()

    for ((key, forward) in owedByDirection) {
        if (key in handled) continue
        val (currency, payer, participant) = key

        // The reversed direction in the same currency cancels against this one.
        val reverseKey = Triple(currency, participant, payer)
        val reverse = owedByDirection[reverseKey] ?: 0L

        handled += key
        handled += reverseKey

        // forward: participant owes payer. net > 0 means participant is the debtor.
        val net = forward - reverse
        when {
            net > 0L -> debts += Debt(
                debtorUserId = participant,
                creditorUserId = payer,
                currency = currency,
                amountSubunits = net,
            )
            net < 0L -> debts += Debt(
                debtorUserId = payer,
                creditorUserId = participant,
                currency = currency,
                amountSubunits = -net,
            )
            // net == 0: exact cancellation, emit nothing.
        }
    }

    return debts
}
