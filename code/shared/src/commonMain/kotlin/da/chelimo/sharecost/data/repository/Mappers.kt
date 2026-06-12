package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.SettlementId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.projection.MemberWithUserRow
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseShare
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
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
)

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
    createdBy = UserId(createdBy),
    createdAt = createdAt,
    rowVersion = rowVersion,
)

internal fun ShareEntity.toDomain(): ExpenseShare = ExpenseShare(
    id = id,
    userId = UserId(userId),
    owedSubunits = shareOwedSubunits,
    remainingSubunits = remainingSubunits,
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
