package app.splitevenly.domain.balance

import app.splitevenly.core.id.UserId

/**
 * A single participant's portion of an expense.
 *
 * [payerUserId] fronted the money; [participantUserId] owes their share.
 * [remainingSubunits] is the unsettled amount still owed, in the currency's
 * minor units (e.g. cents). A value of 0 means the share is fully settled.
 */
data class Share(
    val expenseId: String,
    val currency: String,
    val payerUserId: UserId,
    val participantUserId: UserId,
    val remainingSubunits: Long,
)
