package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.data.db.computeExpenseStatus
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.GroupDao
import da.chelimo.sharecost.data.db.dao.HistoryEventDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.domain.activity.HistoryEventType
import da.chelimo.sharecost.domain.balance.Debt
import da.chelimo.sharecost.domain.balance.Share as BalanceShare
import da.chelimo.sharecost.domain.balance.buildBilateralBalances
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.projection.OutstandingShareRow
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseWithShares
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.fx.rateOrNull
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [ExpenseRepository] (04 §6.2). An expense and its shares are written in one Room
 * transaction with the denormalized status computed up front (02 §7.5). Writes enforce AC-INV-001
 * (`sum(shares) == amount`) before touching the DB. Server push is deferred to S-1.
 */
@OptIn(ExperimentalTime::class)
class ExpenseRepositoryImpl(
    private val expenseDao: ExpenseDao,
    private val shareDao: ShareDao,
    private val clock: Clock = Clock.System,
    // Optional so unit tests can construct the repo without the FX/group stack; production DI wires both,
    // which switches on multi-currency balance conversion (F2). Null => same-currency behaviour as before.
    private val fxRepository: FxRepository? = null,
    private val groupDao: GroupDao? = null,
    // Optional activity log (F5). Null in unit tests => no history rows are written; production DI wires it.
    private val historyEventDao: HistoryEventDao? = null,
) : ExpenseRepository {

    override fun observeExpenses(groupId: GroupId): Flow<List<Expense>> =
        expenseDao.observeByGroup(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeExpense(expenseId: ExpenseId): Flow<ExpenseWithShares?> =
        combine(
            expenseDao.observeById(expenseId.value),
            shareDao.observeByExpense(expenseId.value),
        ) { expense, shares ->
            expense?.let { ExpenseWithShares(it.toDomain(), shares.map { s -> s.toDomain() }) }
        }

    override fun observeBalances(groupId: GroupId): Flow<List<Debt>> {
        val fx = fxRepository
        val groups = groupDao
        val shares = shareDao.observeOutstandingShares(groupId.value)
        // Without the FX stack (unit tests) net per stored currency, exactly as before.
        if (fx == null || groups == null) {
            return shares.map { rows ->
                buildBilateralBalances(rows.mapNotNull { r -> r.toBalanceShare(r.currency, r.remainingSubunits) })
            }
        }
        // With it, convert every non-base share into the group's base currency so the "who owes whom"
        // nets to a single base figure. A share whose rate is unavailable stays in its own currency
        // (a separate balance row) rather than being dropped or mis-netted.
        return combine(shares, groups.observeById(groupId.value)) { rows, group ->
            val base = group?.baseCurrency ?: "USD"
            val today = clock.todayUtc()
            val converted = rows.mapNotNull { r ->
                val (currency, amount) = convertToBase(r.currency, r.remainingSubunits, base, today, fx)
                r.toBalanceShare(currency, amount)
            }
            buildBilateralBalances(converted)
        }
    }

    private fun OutstandingShareRow.toBalanceShare(currency: String, remaining: Long): BalanceShare? {
        val payer = payerUserId ?: return null
        return BalanceShare(
            expenseId = expenseId,
            currency = currency,
            payerUserId = UserId(payer),
            participantUserId = UserId(participantUserId),
            remainingSubunits = remaining,
        )
    }

    /** (base, convertedSubunits) when a rate exists; otherwise the original (currency, subunits). */
    private suspend fun convertToBase(currency: String, subunits: Long, base: String, asOf: String, fx: FxRepository): Pair<String, Long> {
        if (currency.equals(base, ignoreCase = true)) return base to subunits
        val rate = fx.rate(currency, base, asOf).rateOrNull() ?: return currency to subunits
        return base to (subunits * rate).roundToLong()
    }

    override suspend fun addExpense(input: NewExpense): AppResult<Expense> {
        validate(input.title, input.amountSubunits, input.shares)?.let { return it }

        val now = clock.nowEpochMillis()
        val expenseId = newId()
        val status = computeExpenseStatus(deletedAt = null, sumRemainingSubunits = input.shares.sumOf { it.owedSubunits })
        val expense = ExpenseEntity(
            id = expenseId,
            groupId = input.groupId.value,
            title = input.title.trim(),
            notes = input.notes,
            amountSubunits = input.amountSubunits,
            currency = input.currency,
            expenseDate = input.expenseDate,
            payerUserId = input.payerUserId?.value,
            payerOutsideName = input.payerOutsideName,
            splitMode = input.splitMode,
            categoryId = input.categoryId,
            status = status,
            createdBy = input.createdBy.value,
            createdAt = now,
            updatedAt = now,
        )
        expenseDao.insertWithShares(expense, input.shares.toEntities(expenseId, now))
        recordHistory(expenseId, input.groupId.value, HistoryEventType.CREATED, input.createdBy.value, now)
        return expense.toDomain().asOk()
    }

    override suspend fun editExpense(expenseId: ExpenseId, input: EditExpense): AppResult<Expense> {
        validate(input.title, input.amountSubunits, input.shares)?.let { return it }

        val existing = expenseDao.getById(expenseId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        val status = computeExpenseStatus(deletedAt = null, sumRemainingSubunits = input.shares.sumOf { it.owedSubunits })
        val updated = existing.copy(
            title = input.title.trim(),
            notes = input.notes,
            amountSubunits = input.amountSubunits,
            currency = input.currency,
            expenseDate = input.expenseDate,
            payerUserId = input.payerUserId?.value,
            payerOutsideName = input.payerOutsideName,
            splitMode = input.splitMode,
            categoryId = input.categoryId,
            status = status,
            updatedAt = now,
            rowVersion = existing.rowVersion + 1,
        )
        expenseDao.replaceWithShares(updated, input.shares.toEntities(expenseId.value, now))
        recordHistory(expenseId.value, existing.groupId, HistoryEventType.EDITED, input.editedBy?.value, now)
        return updated.toDomain().asOk()
    }

    override suspend fun deleteExpense(expenseId: ExpenseId): AppResult<Unit> {
        val existing = expenseDao.getById(expenseId.value)
            ?: return validationErr("expense", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        expenseDao.softDelete(expenseId.value, now)
        recordHistory(expenseId.value, existing.groupId, HistoryEventType.DELETED, actorUserId = null, now)
        return AppResult.Ok(Unit)
    }

    /** Append an activity-log row when the log is wired (no-op in unit tests where the dao is null). */
    private suspend fun recordHistory(
        expenseId: String,
        groupId: String,
        type: HistoryEventType,
        actorUserId: String?,
        now: Long,
    ) {
        val dao = historyEventDao ?: return
        dao.upsert(
            HistoryEventEntity(
                id = newId(),
                expenseId = expenseId,
                groupId = groupId,
                actorUserId = actorUserId,
                type = type.name,
                detail = null,
                createdAt = now,
            ),
        )
    }

    /** A new share starts fully unpaid (`remaining == owed`); the raw split inputs are preserved. */
    private fun List<NewShare>.toEntities(expenseId: String, now: Long): List<ShareEntity> = map { s ->
        ShareEntity(
            id = newId(),
            expenseId = expenseId,
            userId = s.userId.value,
            shareOwedSubunits = s.owedSubunits,
            remainingSubunits = s.owedSubunits,
            shareUnits = s.shareUnits,
            sharePercentage = s.sharePercentage,
            shareExactSubunits = s.shareExactSubunits,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** AC-INV-001 + basic field checks; null when the input is valid. */
    private fun validate(title: String, amountSubunits: Long, shares: List<NewShare>): AppResult<Nothing>? {
        val fieldErrors = buildMap {
            if (title.trim().isEmpty()) put("title", AppError.Validation.Reason.Required)
            if (amountSubunits <= 0L) put("amount", AppError.Validation.Reason.OutOfRange)
            when {
                shares.isEmpty() -> put("shares", AppError.Validation.Reason.Required)
                shares.sumOf { it.owedSubunits } != amountSubunits -> put("shares", AppError.Validation.Reason.Malformed)
            }
        }
        return if (fieldErrors.isEmpty()) null else AppError.Validation(fieldErrors).asErr()
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
