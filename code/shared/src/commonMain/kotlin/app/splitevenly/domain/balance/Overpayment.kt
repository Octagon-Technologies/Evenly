package app.splitevenly.domain.balance

import app.splitevenly.core.id.UserId

/**
 * A **double-payment signal** (P1 #9): [debtorUserId] has paid [creditorUserId] MORE than they owed —
 * the sum of that pair's shares derives to a *negative* remaining, AND the last two payments between
 * them were for the exact same amount. That amount match is what tells a real double payment (the same
 * payment logged twice) apart from two different payments that simply add up past what was owed.
 * Because remaining is derived on read (never stored), an overpayment would otherwise just read as
 * "settled" and the extra money would vanish with no signal — so the Balances tab surfaces it as a
 * banner linking to the pair's payments, where one can be voided to correct it. [overpaidSubunits] is
 * the positive magnitude of the overpayment.
 */
data class Overpayment(
    val debtorUserId: UserId,
    val creditorUserId: UserId,
    val currency: String,
    val overpaidSubunits: Long,
)
