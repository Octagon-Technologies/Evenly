package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One line of money a claim would move: an expense title, the amount the retired name is on the hook
 * for (or paid), and its currency.
 *
 * The confirm sheet must show the money before it moves, so this is the *share* amount rather than the
 * expense total: "you'll take on $24.00 of the dinner" is the number that lands on a balance, and the
 * dinner's total is not.
 */
data class ClaimLineRow(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "amount_subunits") val amountSubunits: Long,
    @ColumnInfo(name = "currency") val currency: String,
)
