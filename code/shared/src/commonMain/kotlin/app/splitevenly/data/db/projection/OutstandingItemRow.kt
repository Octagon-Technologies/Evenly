package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One outstanding line — a single debtor's still-owed share of one expense, joined to that expense for
 * title/date/currency and payer. Unlike [OutstandingShareRow] (which feeds the netting engine) this
 * carries the human title/date, so the Balances breakdown and the one-page settle checklist can list
 * *which* expenses make up a balance. Remaining is derived (owed − applied), payer's own share excluded.
 */
data class OutstandingItemRow(
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "expense_date") val expenseDate: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "debtor_user_id") val debtorUserId: String,
    @ColumnInfo(name = "creditor_user_id") val creditorUserId: String,
    @ColumnInfo(name = "remaining_subunits") val remainingSubunits: Long,
)
