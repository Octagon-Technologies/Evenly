package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * Read-only projection (NOT an @Entity) joining `shares` to its parent `expense` so the bilateral
 * balance engine has everything it needs in one row: who paid, who owes, the currency, and how much
 * is still outstanding. Room maps the SELECT columns onto this by `@ColumnInfo` name.
 *
 * `payer_user_id` is nullable (an expense can be paid by a non-member "outside" payer, 02 §3.7);
 * such shares form no member-to-member debt and are dropped when mapping to the balance domain.
 */
data class OutstandingShareRow(
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "payer_user_id") val payerUserId: String?,
    @ColumnInfo(name = "user_id") val participantUserId: String,
    @ColumnInfo(name = "remaining_subunits") val remainingSubunits: Long,
)
