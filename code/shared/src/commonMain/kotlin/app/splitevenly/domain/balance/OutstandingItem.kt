package app.splitevenly.domain.balance

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.UserId

/**
 * One line behind a balance: [debtorUserId] still owes [creditorUserId] [remainingSubunits] on the
 * expense [expenseId] ([title]). The Balances tab groups these per counterparty to show *what* a debt
 * is made of; the one-page settle screen lists them as the checkable, per-expense settle targets.
 * Currency is the expense's own (settlement is same-currency), so a mixed-currency debt lists per line.
 */
data class OutstandingItem(
    val expenseId: ExpenseId,
    val title: String,
    val expenseDate: String,
    val currency: String,
    val debtorUserId: UserId,
    val creditorUserId: UserId,
    val remainingSubunits: Long,
)
