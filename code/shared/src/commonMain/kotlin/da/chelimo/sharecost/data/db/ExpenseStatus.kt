package da.chelimo.sharecost.data.db

/**
 * The `expenses.status` values (02 §6). Only [ACTIVE] and [DELETED] are ever stored now: deletion is
 * ground truth (`deleted_at`). **Settled is no longer stored** — it is derived on read from the owed
 * split + settlement allocations (a share's remaining is `owed − Σ applied`, settled iff every share's
 * remaining is 0), so it can never go stale when a split is edited. [SETTLED] is kept only as the
 * conventional label the UI shows for a fully-paid expense.
 */
object ExpenseStatus {
    const val ACTIVE = "ACTIVE"
    const val SETTLED = "SETTLED"
    const val DELETED = "DELETED"
}
