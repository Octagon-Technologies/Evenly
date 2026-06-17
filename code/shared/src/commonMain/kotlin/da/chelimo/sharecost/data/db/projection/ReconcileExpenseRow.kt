package da.chelimo.sharecost.data.db.projection

import androidx.room.ColumnInfo

/** Title + amount of one expense a placeholder is booked into — feeds the Reconcile claim cards (03 §8). */
data class ReconcileExpenseRow(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "amount_subunits") val amountSubunits: Long,
)
