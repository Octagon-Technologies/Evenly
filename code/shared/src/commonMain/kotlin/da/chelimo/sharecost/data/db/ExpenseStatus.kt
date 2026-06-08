package da.chelimo.sharecost.data.db

/**
 * The denormalized `expenses.status` values (02 §6). Locally this column is **recomputed in app
 * code** on insert/update (02 §7.5), not by a DB trigger — a generated column can't read other
 * tables (`shares`), so the value is owned by [computeExpenseStatus].
 */
object ExpenseStatus {
    const val ACTIVE = "ACTIVE"
    const val SETTLED = "SETTLED"
    const val DELETED = "DELETED"
}

/**
 * Derives `expenses.status` from soft-delete state and the sum of its shares' remaining balances
 * (AC-INV-003: `status == 'SETTLED'` iff `SUM(remaining) == 0`). Deletion wins over settlement.
 *
 * @param sumRemainingSubunits sum of `shares.remaining_subunits` for the expense (0 if no shares).
 */
fun computeExpenseStatus(deletedAt: Long?, sumRemainingSubunits: Long): String = when {
    deletedAt != null -> ExpenseStatus.DELETED
    sumRemainingSubunits == 0L -> ExpenseStatus.SETTLED
    else -> ExpenseStatus.ACTIVE
}
