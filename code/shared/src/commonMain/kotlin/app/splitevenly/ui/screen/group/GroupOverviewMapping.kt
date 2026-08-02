package app.splitevenly.ui.screen.group

import app.splitevenly.core.id.UserId
import app.splitevenly.domain.balance.Debt
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.Member

/**
 * Aggregates for the Overview tab — pure, so it is unit-testable without Compose. All money is in
 * subunits; the screen formats with [app.splitevenly.ui.components.moneySubunits].
 */
data class GroupOverviewUi(
    val groupName: String,
    val groupEmoji: String,
    val byDay: List<Pair<String, Long>>,
    val byMember: List<Pair<String, Long>>,
    val totalSubunits: Long,
    val expenseCount: Int,
    val perPersonSubunits: Long,
    val currencyCode: String,
    val balances: List<Triple<String, String, Long>>,
)

/**
 * Folds the group + its expenses + members + balances into the Overview aggregates.
 *
 * `byDay` groups expenses by [Expense.expenseDate] (sum of amounts), labeled via [dayLabel] (reused
 * from the Expenses tab). `byMember` groups by payer, summing amounts, naming via [members] — a null
 * or outside payer rolls up under "Outside". `perPerson` divides the total across the member count
 * (guarded against an empty roster). Balances resolve debtor/creditor names from [members],
 * falling back to "Someone"; the current user is NOT specialized to "You" here.
 */
fun buildOverview(
    group: Group?,
    expenses: List<Expense>,
    members: List<Member>,
    balances: List<Debt>,
    today: String,
): GroupOverviewUi {
    val name = group?.name ?: ""
    val emoji = group?.emoji ?: "💸"
    val currency = group?.baseCurrency ?: "USD"
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }

    val byDay = expenses
        .groupBy { it.expenseDate }
        .map { (date, list) -> dayLabel(date, today) to list.sumOf { it.amountSubunits } }

    val byMember = expenses
        .groupBy { it.payerUserId }
        .map { (payer, list) ->
            val label = payerName(payer, nameByUser)
            label to list.sumOf { it.amountSubunits }
        }

    val total = expenses.sumOf { it.amountSubunits }
    val count = expenses.size
    val perPerson = total / maxOf(1, members.size)

    val balanceRows = balances.map { d ->
        Triple(memberName(d.debtorUserId, nameByUser), memberName(d.creditorUserId, nameByUser), d.amountSubunits)
    }

    return GroupOverviewUi(
        groupName = name,
        groupEmoji = emoji,
        byDay = byDay,
        byMember = byMember,
        totalSubunits = total,
        expenseCount = count,
        perPersonSubunits = perPerson,
        currencyCode = currency,
        balances = balanceRows,
    )
}

private fun payerName(payer: UserId?, nameByUser: Map<String, String>): String =
    if (payer == null) "Outside" else nameByUser[payer.value] ?: "Outside"

private fun memberName(userId: UserId, nameByUser: Map<String, String>): String =
    nameByUser[userId.value] ?: "Someone"
