package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import app.splitevenly.data.db.entity.SettlementEntity

/** A share and the bill it belongs to: what a payment-edit preview names each line by. */
data class ShareExpenseRow(
    @ColumnInfo(name = "share_id") val shareId: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "expense_date") val expenseDate: String,
)

/** A live payment that touched one bill, with what it put on that bill and how many bills it paid. */
data class ExpensePaymentRow(
    @Embedded val settlement: SettlementEntity,
    @ColumnInfo(name = "applied_here") val appliedHere: Long,
    @ColumnInfo(name = "bill_count") val billCount: Int,
)
