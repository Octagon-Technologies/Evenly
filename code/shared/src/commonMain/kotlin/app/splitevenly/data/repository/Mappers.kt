package app.splitevenly.data.repository

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.projection.ConflictWithExpenseRow
import app.splitevenly.data.db.projection.MemberWithUserRow
import app.splitevenly.data.db.projection.ShareRow
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseShare
import app.splitevenly.domain.group.Conflict
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.Member
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.domain.settlement.SettlementRecord

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
    preferredPaymentApp = parsePaymentApp(preferredPaymentApp),
)

/** Parses a stored [PaymentApp] name; unknown/null → null (tolerant of older/newer rows). */
internal fun parsePaymentApp(name: String?): PaymentApp? =
    name?.let { n -> PaymentApp.entries.firstOrNull { it.name == n } }

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

internal fun ShareRow.toDomain(): ExpenseShare = ExpenseShare(
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
    paymentApp = paymentApp,
    createdBy = UserId(createdBy),
)
