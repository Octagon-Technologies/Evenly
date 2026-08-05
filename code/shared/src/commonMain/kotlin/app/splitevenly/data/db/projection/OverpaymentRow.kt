package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One over-paid debtor→creditor pair (P1 #9): the shares [debtorUserId] owes [creditorUserId] in a
 * currency sum to a *negative* derived remaining, i.e. more has been paid than owed. [overpaidSubunits]
 * is the positive magnitude (the query negates the summed negative remainder). One row per pair+currency.
 */
data class OverpaymentRow(
    @ColumnInfo(name = "debtor_user_id") val debtorUserId: String,
    @ColumnInfo(name = "creditor_user_id") val creditorUserId: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "overpaid_subunits") val overpaidSubunits: Long,
)
