package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.SettlementId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.projection.ConflictWithExpenseRow
import da.chelimo.sharecost.data.db.projection.MemberWithUserRow
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseShare
import da.chelimo.sharecost.domain.group.Conflict
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.domain.settlement.SettlementRecord

/**
 * Entity/projection → domain mappers (06 §3). They live in `data` (which may see both Room types and
 * domain types); the domain layer never imports Room. Mapping is one-way: writes build entities
 * directly inside each repository where the surrounding context (IDs, timestamps) is at hand.
 */

internal fun GroupEntity.toDomain(): Group = Group(
    id = GroupId(id),
    name = name,
    emoji = emoji,
    baseCurrency = baseCurrency,
    adminUserId = adminUserId?.let(::UserId),
    inviteToken = inviteToken,
    createdAt = createdAt,
)

internal fun MemberWithUserRow.toDomain(): Member = Member(
    userId = UserId(userId),
    displayName = displayName,
    isPlaceholder = isPlaceholder,
    isAdmin = isAdmin,
    joinedAt = joinedAt,
    paymentHandles = paymentHandles(venmoHandle, cashappHandle, paypalHandle, zelleHandle),
)

/** Folds the four nullable handle columns into a [PaymentApp]-keyed map (only the apps that are set). */
internal fun paymentHandles(venmo: String?, cashapp: String?, paypal: String?, zelle: String?): Map<PaymentApp, String> =
    buildMap {
        venmo?.takeIf { it.isNotBlank() }?.let { put(PaymentApp.VENMO, it) }
        cashapp?.takeIf { it.isNotBlank() }?.let { put(PaymentApp.CASH_APP, it) }
        paypal?.takeIf { it.isNotBlank() }?.let { put(PaymentApp.PAYPAL, it) }
        zelle?.takeIf { it.isNotBlank() }?.let { put(PaymentApp.ZELLE, it) }
    }

internal fun ExpenseEntity.toDomain(): Expense = Expense(
    id = ExpenseId(id),
    groupId = GroupId(groupId),
    title = title,
    amountSubunits = amountSubunits,
    currency = currency,
    expenseDate = expenseDate,
    payerUserId = payerUserId?.let(::UserId),
    payerOutsideName = payerOutsideName,
    splitMode = splitMode,
    status = status,
    notes = notes,
    categoryId = categoryId,
    createdBy = UserId(createdBy),
    createdAt = createdAt,
    rowVersion = rowVersion,
)

internal fun ShareEntity.toDomain(): ExpenseShare = ExpenseShare(
    id = id,
    userId = UserId(userId),
    owedSubunits = shareOwedSubunits,
    remainingSubunits = remainingSubunits,
    shareUnits = shareUnits,
    sharePercentage = sharePercentage,
    shareExactSubunits = shareExactSubunits,
)

internal fun ConflictWithExpenseRow.toDomain(): Conflict = Conflict(
    id = id,
    groupId = GroupId(groupId),
    expenseId = ExpenseId(expenseId),
    expenseTitle = expenseTitle,
    amountSubunits = amountSubunits,
    currency = currency,
    addedUserId = UserId(addedUserId),
    triggeredByUserId = UserId(triggeredByUserId),
    createdAt = createdAt,
)

internal fun SettlementEntity.toDomain(): SettlementRecord = SettlementRecord(
    id = SettlementId(id),
    groupId = GroupId(groupId),
    fromUserId = UserId(fromUserId),
    toUserId = UserId(toUserId),
    paymentCurrency = paymentCurrency,
    paymentAmountSubunits = paymentAmountSubunits,
    settledAt = settledAt,
    notes = notes,
)
