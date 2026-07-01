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
import da.chelimo.sharecost.data.db.ExpenseStatus
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.ExpenseEditConflictDao
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
import da.chelimo.sharecost.domain.expense.ConflictSide
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseEditConflict
import da.chelimo.sharecost.domain.expense.ExpenseWithShares
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.fx.rateOrNull
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
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
    // Optional parked-edit store (versioning). Null in unit tests that don't exercise conflict resolution.
    private val editConflictDao: ExpenseEditConflictDao? = null,
) : ExpenseRepository {

    // Parks store the rejected payload as the snake_case JSON the client sent to commit_expense.
    @OptIn(ExperimentalSerializationApi::class)
    private val payloadJson = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    override fun observeExpenses(groupId: GroupId): Flow<List<Expense>> =
        expenseDao.observeByGroup(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeExpense(expenseId: ExpenseId): Flow<ExpenseWithShares?> =
        combine(
            expenseDao.observeById(expenseId.value),
            shareDao.observeByExpense(expenseId.value),
        ) { expense, shares ->
            expense?.let { ExpenseWithShares(it.toDomain(), shares.map { s -> s.toDomain() }) }
        }

    override fun observeExpensesWithShares(groupId: GroupId): Flow<List<ExpenseWithShares>> =
        combine(
            expenseDao.observeByGroup(groupId.value),
            shareDao.observeByGroup(groupId.value),
        ) { expenses, shares ->
            val sharesByExpense = shares.groupBy { it.expenseId }
            expenses.map { e ->
                ExpenseWithShares(e.toDomain(), sharesByExpense[e.id].orEmpty().map { it.toDomain() })
            }
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
        // A new expense is always ACTIVE; "settled" is derived on read (no payments exist yet, and the
        // payer's own share derives to remaining 0 anyway — a person can't owe themselves).
        val shares = input.shares.toEntities(expenseId, now)
        val status = ExpenseStatus.ACTIVE
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
        expenseDao.insertWithShares(expense, shares)
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
        // Identity-preserving merge: a participant who stays keeps their share id, so settlement
        // allocations stay linked and `remaining` re-derives (editing a split no longer wipes payments).
        // Participants the edit dropped are tombstoned. status stays ACTIVE — "settled" is derived.
        val existingShares = shareDao.getByExpense(expenseId.value)
        val (shares, removedShareIds) = mergeShares(existingShares, input.shares.toDesired(), expenseId.value, now)
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
            status = ExpenseStatus.ACTIVE,
            updatedAt = now,
            rowVersion = existing.rowVersion + 1,
        )
        expenseDao.replaceWithShares(updated, shares, removedShareIds, now)
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

    override fun observeEditConflicts(groupId: GroupId): Flow<List<ExpenseEditConflict>> {
        val dao = editConflictDao ?: return flowOf(emptyList())
        // Join each parked edit (its rejected payload) to the live expense AND its live shares so the UI
        // can diff both sides field-by-field (total, split mode, payer, each participant's owed amount) —
        // not just show a bare total. Current shares come from the group's active share set, keyed by user.
        return combine(
            dao.observeUnresolved(groupId.value),
            expenseDao.observeByGroup(groupId.value),
            shareDao.observeByGroup(groupId.value),
        ) { conflicts, expenses, shares ->
            val byId = expenses.associateBy { it.id }
            val currentSharesByExpense = shares.groupBy { it.expenseId }
            conflicts.mapNotNull { c ->
                val rejectedExpense = runCatching { payloadJson.decodeFromString<ExpenseEntity>(c.rejectedExpense) }.getOrNull()
                    ?: return@mapNotNull null
                val rejectedShares = runCatching { payloadJson.decodeFromString<List<ShareEntity>>(c.rejectedShares) }.getOrNull()
                    ?: emptyList()
                val current = byId[c.expenseId]
                // Current side derives from the live expense + its active shares (fall back to the rejected
                // payload if the expense isn't hydrated yet, so the card still renders).
                val currentShareMap = currentSharesByExpense[c.expenseId]
                    ?.associate { UserId(it.userId) to it.shareOwedSubunits }
                    ?: rejectedShares.filter { it.deletedAt == null }.associate { UserId(it.userId) to it.shareOwedSubunits }
                ExpenseEditConflict(
                    id = c.id,
                    expenseId = ExpenseId(c.expenseId),
                    rejectedBy = UserId(c.rejectedBy),
                    winnerBy = c.serverActor?.let { UserId(it) },
                    currency = current?.currency ?: rejectedExpense.currency,
                    current = ConflictSide(
                        title = current?.title ?: rejectedExpense.title,
                        amountSubunits = current?.amountSubunits ?: rejectedExpense.amountSubunits,
                        splitMode = current?.splitMode ?: rejectedExpense.splitMode,
                        payerUserId = (current?.payerUserId ?: rejectedExpense.payerUserId)?.let { UserId(it) },
                        payerOutsideName = current?.payerOutsideName ?: rejectedExpense.payerOutsideName,
                        shares = currentShareMap,
                    ),
                    rejected = ConflictSide(
                        title = rejectedExpense.title,
                        amountSubunits = rejectedExpense.amountSubunits,
                        splitMode = rejectedExpense.splitMode,
                        payerUserId = rejectedExpense.payerUserId?.let { UserId(it) },
                        payerOutsideName = rejectedExpense.payerOutsideName,
                        shares = rejectedShares.filter { it.deletedAt == null }
                            .associate { UserId(it.userId) to it.shareOwedSubunits },
                    ),
                    createdAt = c.createdAt,
                )
            }
        }
    }

    override suspend fun resolveEditConflict(conflictId: String, useRejected: Boolean, resolvedBy: UserId?): AppResult<Unit> {
        val dao = editConflictDao ?: return AppResult.Ok(Unit)
        val conflict = dao.getById(conflictId) ?: return validationErr("conflict", AppError.Validation.Reason.Required)
        if (conflict.resolvedAt != null) return AppResult.Ok(Unit) // already resolved — idempotent
        val now = clock.nowEpochMillis()

        if (!useRejected) {
            dao.resolve(conflictId, "KEEP_CURRENT", resolvedBy?.value, now)
            return AppResult.Ok(Unit)
        }

        // Re-apply the rejected edit on top of the CURRENT canonical version. Because it's based on the
        // live row, the next commit_expense push CASes from a clean base and the edit lands as a normal
        // new version — no second collision. If the expense was deleted meanwhile, there's nothing to
        // apply onto, so just close the conflict.
        val current = expenseDao.getById(conflict.expenseId)
        if (current == null || current.deletedAt != null) {
            dao.resolve(conflictId, "KEEP_CURRENT", resolvedBy?.value, now)
            return AppResult.Ok(Unit)
        }
        val rejectedExpense = runCatching { payloadJson.decodeFromString<ExpenseEntity>(conflict.rejectedExpense) }.getOrNull()
            ?: return validationErr("conflict", AppError.Validation.Reason.Malformed)
        val rejectedShares = runCatching { payloadJson.decodeFromString<List<ShareEntity>>(conflict.rejectedShares) }.getOrNull()
            ?: return validationErr("conflict", AppError.Validation.Reason.Malformed)

        val existingActive = shareDao.getByExpense(conflict.expenseId)
        val desired = rejectedShares.map {
            DesiredShare(it.userId, it.shareOwedSubunits, it.shareUnits, it.sharePercentage, it.shareExactSubunits)
        }
        val (merged, removed) = mergeShares(existingActive, desired, conflict.expenseId, now)
        val updated = current.copy(
            title = rejectedExpense.title,
            notes = rejectedExpense.notes,
            amountSubunits = rejectedExpense.amountSubunits,
            currency = rejectedExpense.currency,
            expenseDate = rejectedExpense.expenseDate,
            payerUserId = rejectedExpense.payerUserId,
            payerOutsideName = rejectedExpense.payerOutsideName,
            splitMode = rejectedExpense.splitMode,
            categoryId = rejectedExpense.categoryId,
            status = ExpenseStatus.ACTIVE,
            updatedAt = now,
            rowVersion = current.rowVersion + 1,
        )
        expenseDao.replaceWithShares(updated, merged, removed, now)
        recordHistory(conflict.expenseId, current.groupId, HistoryEventType.EDITED, resolvedBy?.value, now)
        dao.resolve(conflictId, "USE_REJECTED", resolvedBy?.value, now)
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

    /**
     * Maps split inputs to fresh share rows (used when adding an expense). Remaining is not stored: a
     * share's outstanding amount derives as `owed − Σ applied`, and the payer's own share derives to 0
     * (a person can't owe themselves; balances likewise net self-shares to zero). The raw split inputs
     * are preserved so the editor can be re-rendered without recomputing.
     */
    private fun List<NewShare>.toEntities(expenseId: String, now: Long): List<ShareEntity> = map { s ->
        ShareEntity(
            id = newId(),
            expenseId = expenseId,
            userId = s.userId.value,
            shareOwedSubunits = s.owedSubunits,
            shareUnits = s.shareUnits,
            sharePercentage = s.sharePercentage,
            shareExactSubunits = s.shareExactSubunits,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Split inputs as the merge's desired set (preserves share identity by user on edit). */
    private fun List<NewShare>.toDesired(): List<DesiredShare> = map { s ->
        DesiredShare(
            userId = s.userId.value,
            owedSubunits = s.owedSubunits,
            shareUnits = s.shareUnits,
            sharePercentage = s.sharePercentage,
            shareExactSubunits = s.shareExactSubunits,
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
