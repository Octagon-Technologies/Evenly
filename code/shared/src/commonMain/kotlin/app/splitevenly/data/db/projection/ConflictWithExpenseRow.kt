package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * A conflict joined to its expense so the Conflicts tab can show the title + amount in one query
 * (member names are resolved from the roster in the route). INNER JOIN — a conflict whose expense is
 * gone is not actionable.
 */
data class ConflictWithExpenseRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "added_user_id") val addedUserId: String,
    @ColumnInfo(name = "triggered_by_user_id") val triggeredByUserId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expense_title") val expenseTitle: String,
    @ColumnInfo(name = "amount_subunits") val amountSubunits: Long,
    @ColumnInfo(name = "currency") val currency: String,
)
