package app.splitevenly.domain.group

import app.splitevenly.core.id.UserId

/**
 * A name in a group that has expenses but no account, and that the asking member has not yet answered.
 *
 * Carries the evidence rather than just the name: "Are you Chelimo?" is unanswerable from a name alone,
 * while "Chelimo bought the airport taxi" is answerable in a second.
 *
 * [currency] is null when there is nothing to show a currency for — either the name has no expenses yet,
 * or its expenses span several currencies, in which case [multiCurrency] is true and only [expenseCount]
 * is meaningful. Never sum across currencies to produce one number.
 */
data class UnclaimedName(
    val userId: UserId,
    val displayName: String,
    val expenseCount: Int,
    val owedSubunits: Long,
    val currency: String?,
    val multiCurrency: Boolean,
)
