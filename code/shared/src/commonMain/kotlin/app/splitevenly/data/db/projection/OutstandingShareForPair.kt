package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * An outstanding share owed by one specific debtor to one specific creditor, joined to its expense
 * for currency + ordering. Feeds the settlement allocator (03 §4.3.1): the payment walks these
 * oldest-expense-first. [expenseDate] + [shareId] give a deterministic order; [shareId]/[expenseId]
 * let the repository write allocations and recompute the right expenses' status.
 */
data class OutstandingShareForPair(
    @ColumnInfo(name = "id") val shareId: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "remaining_subunits") val remainingSubunits: Long,
    @ColumnInfo(name = "expense_date") val expenseDate: String,
)
