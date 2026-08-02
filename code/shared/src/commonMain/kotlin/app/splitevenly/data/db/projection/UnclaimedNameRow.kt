package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One name in this group that has expenses but no account, and that the asking member has not yet
 * answered — the single row shape behind both the "Is this you?" card and the full-screen list.
 *
 * Carries the **evidence**, because "Are you Chelimo?" is unanswerable from a name alone while "Chelimo
 * bought the airport taxi" is answerable in a second. [currencyCount] exists so a name with expenses in
 * more than one currency shows the count without a meaningless summed amount.
 */
data class UnclaimedNameRow(
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "expense_count") val expenseCount: Int,
    @ColumnInfo(name = "owed_subunits") val owedSubunits: Long,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "currency_count") val currencyCount: Int,
)
