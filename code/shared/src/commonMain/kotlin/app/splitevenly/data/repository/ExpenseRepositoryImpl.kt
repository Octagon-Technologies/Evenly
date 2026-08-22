package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.error.asOk
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.db.ExpenseStatus
import app.splitevenly.data.db.dao.ExpenseDao
import app.splitevenly.data.db.dao.ExpenseEditConflictDao
import app.splitevenly.data.db.dao.GroupDao
import app.splitevenly.data.db.dao.HistoryEventDao
import app.splitevenly.data.db.dao.SettlementDao
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.dao.SupersededNoticeDao
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.projection.OutstandingShareRow
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.balance.Debt
import app.splitevenly.domain.balance.OutstandingItem
import app.splitevenly.domain.balance.Overpayment
import app.splitevenly.domain.balance.buildBilateralBalances
import app.splitevenly.domain.expense.ConflictSide
import app.splitevenly.domain.expense.EditExpense
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseEditConflict
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import app.splitevenly.domain.fx.convertSubunits
import app.splitevenly.domain.fx.rateOrNull
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.FxRepository
import app.splitevenly.newId
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import app.splitevenly.domain.balance.Share as BalanceShare

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
    // Track F: device-local one-sided "your split edit was superseded" notices. Null in unit tests.
    private val supersededNoticeDao: SupersededNoticeDao? = null,
    // Analytics: null in unit tests; production DI passes AndroidAnalytics.
    private val analytics: EvAnalytics? = null,
    // Gates the double-payment banner on amount equality (see observeOverpayments). Null in unit tests
    // that don't exercise it => legacy sum-only behaviour, matching the optional-ctor-dep pattern.
    private val settlementDao: SettlementDao? = null,
) : ExpenseRepository {
    override fun observeSupersededNotice(expenseId: ExpenseId): Flow<Boolean> =
        supersededNoticeDao?.observeForExpense(expenseId.value)?.map { it != null } ?: flowOf(false)

    override suspend fun dismissSupersededNotice(expenseId: ExpenseId) {
        supersededNoticeDao?.dismiss(expenseId.value)
    }

    // Parks store the rejected payload as the snake_case JSON the client sent to commit_expense.
    @OptIn(ExperimentalSerializationApi::class)
    private val payloadJson =
        Json {
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
        // With it, the "who owes whom" nets to a single figure in the group's base currency. **Net first,
        // convert second**: converting each share on the way in rounds once per share and then sums the
        // roundings, which drifts from the same figure computed any other way by a few subunits in a
        // direction that depends on the rate (R14). Netting per pair in each expense's own currency and
        // converting that leaves exactly one rounding per number anyone reads.
        return combine(shares, groups.observeById(groupId.value)) { rows, group ->
            val base = group?.baseCurrency ?: "USD"
            val today = clock.todayUtc()
            val perCurrency =
                buildBilateralBalances(rows.mapNotNull { r -> r.toBalanceShare(r.currency, r.remainingSubunits) })
            // A pair whose rate is unavailable stays in its own currency (a separate balance row) rather
            // than being dropped or mis-netted. Re-netting afterwards is what collapses two currencies
            // that both converted to base, including when they oppose each other.
            val inBase = perCurrency.map { debt -> convertDebt(debt, base, today, fx) }
            buildBilateralBalances(
                inBase.map { d ->
                    BalanceShare(
                        expenseId = "",
                        currency = d.currency,
                        payerUserId = d.creditorUserId,
                        participantUserId = d.debtorUserId,
                        remainingSubunits = d.amountSubunits,
                    )
                },
            )
        }
    }

    /** One netted debt in the group's base currency, or unchanged when no rate covers its currency. */
    private suspend fun convertDebt(
        debt: Debt,
        base: String,
        asOf: String,
        fx: FxRepository,
    ): Debt =
        when {
            debt.currency.equals(base, ignoreCase = true) -> {
                debt.copy(currency = base)
            }

            else -> {
                fx
                    .rate(debt.currency, base, asOf)
                    .rateOrNull()
                    ?.let { rate ->
                        debt.copy(currency = base, amountSubunits = convertSubunits(debt.amountSubunits, rate))
                    }
                    ?: debt
            }
        }

    override fun observeOutstandingItems(groupId: GroupId): Flow<List<OutstandingItem>> =
        shareDao.observeOutstandingItems(groupId.value).map { rows ->
            rows.map { r ->
                OutstandingItem(
                    expenseId = ExpenseId(r.expenseId),
                    title = r.title,
                    expenseDate = r.expenseDate,
                    currency = r.currency,
                    debtorUserId = UserId(r.debtorUserId),
                    creditorUserId = UserId(r.creditorUserId),
                    remainingSubunits = r.remainingSubunits,
                )
            }
        }

    override fun observeOverpayments(
        groupId: GroupId,
        viewer: UserId?,
    ): Flow<List<Overpayment>> =
        shareDao.observeOverpayments(groupId.value).map { rows ->
            rows
                // The banner is about payments the viewer can act on — pairs they're a party to (they
                // overpaid someone, or someone overpaid them). A null viewer surfaces every over-paid pair.
                .filter { viewer == null || it.debtorUserId == viewer.value || it.creditorUserId == viewer.value }
                // The negative-remaining aggregate only says the pair paid past what was owed overall — two
                // genuinely different payments (a $20 expense and a separate $25 one) can add up to that same
                // signal and are NOT a double payment. The tell is the last two payments being the exact same
                // amount (both sides logging the one payment, or one side logging it twice); require that
                // before surfacing the banner.
                .filter { lastTwoPaymentsMatch(groupId.value, it.debtorUserId, it.creditorUserId, it.currency) }
                .map {
                    Overpayment(
                        debtorUserId = UserId(it.debtorUserId),
                        creditorUserId = UserId(it.creditorUserId),
                        currency = it.currency,
                        overpaidSubunits = it.overpaidSubunits,
                    )
                }
        }

    /** No [settlementDao] (unit tests that don't wire it) keeps legacy sum-only behaviour. */
    private suspend fun lastTwoPaymentsMatch(
        groupId: String,
        debtorUserId: String,
        creditorUserId: String,
        currency: String,
    ): Boolean {
        val dao = settlementDao ?: return true
        val lastTwo = dao.lastTwoPaymentAmounts(groupId, debtorUserId, creditorUserId, currency)
        return lastTwo.size == 2 && lastTwo[0] == lastTwo[1]
    }

    private fun OutstandingShareRow.toBalanceShare(
        currency: String,
        remaining: Long,
    ): BalanceShare? {
        val payer = payerUserId ?: return null
        return BalanceShare(
            expenseId = expenseId,
            currency = currency,
            payerUserId = UserId(payer),
            participantUserId = UserId(participantUserId),
            remainingSubunits = remaining,
        )
    }

    override suspend fun addExpense(input: NewExpense): AppResult<Expense> {
        validate(input.title, input.amountSubunits, input.shares)?.let { return it }

        val now = clock.nowEpochMillis()
        val expenseId = newId()
        // A new expense is always ACTIVE; "settled" is derived on read (no payments exist yet, and the
        // payer's own share derives to remaining 0 anyway — a person can't owe themselves).
        val shares = input.shares.toEntities(expenseId, now)
        val status = ExpenseStatus.ACTIVE
        val expense =
            ExpenseEntity(
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
                // Track F: a fresh expense stamps every Zone-1 field at `now` and starts the split at gen 1.
                titleUpdatedAt = now,
                notesUpdatedAt = now,
                categoryUpdatedAt = now,
                dateUpdatedAt = now,
                splitVersion = 1,
                splitUpdatedBy = input.createdBy.value,
            )
        expenseDao.insertWithShares(expense, shares)
        recordHistory(expenseId, input.groupId.value, HistoryEventType.CREATED, input.createdBy.value, now)
        analytics?.capture(
            AnalyticsEvents.EXPENSE_ADDED,
            mapOf(
                "split_mode" to input.splitMode,
                "group_id" to input.groupId.value,
                "entry_method" to if (input.fromScan) "scan" else "manual",
            ),
        )
        return expense.toDomain().asOk()
    }

    override suspend fun editExpense(
        expenseId: ExpenseId,
        input: EditExpense,
    ): AppResult<Expense> {
        validate(input.title, input.amountSubunits, input.shares)?.let { return it }

        val existing = expenseDao.getById(expenseId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        // The half of validation [validate] cannot see, because it needs the database (R1).
        //
        // An edit preserves each surviving participant's share **id** on purpose, so recorded payments
        // stay linked to the split (`data/AGENTS.md`). That makes `currency` the one field whose change
        // silently re-interprets money that is already in the ledger: `ShareDao`'s derived remaining
        // subtracts an allocation with no currency predicate, so a $25.00 payment would read as settling
        // a EUR 25,00 debt, the line would drop out of every `remaining > 0` filter, and the debtor could
        // not even pay the difference because no outstanding row in the new currency exists.
        //
        // Refusing is the conservative half of the choice: making the change *possible* means either
        // re-denominating the allocations at some rate or voiding them, and both are product decisions
        // about somebody else's recorded payment rather than repository ones.
        if (!input.currency.equals(existing.currency, ignoreCase = true) &&
            shareDao.appliedAllocationCount(expenseId.value) > 0
        ) {
            return validationErr("currency", AppError.Validation.Reason.OutOfRange)
        }
        val now = clock.nowEpochMillis()
        // Identity-preserving merge: a participant who stays keeps their share id, so settlement
        // allocations stay linked and `remaining` re-derives (editing a split no longer wipes payments).
        // Participants the edit dropped are tombstoned. status stays ACTIVE — "settled" is derived.
        val existingShares = shareDao.getByExpense(expenseId.value)
        val (shares, removedShareIds) = mergeShares(existingShares, input.shares.toDesired(), expenseId.value, now)
        // Track F zone-aware stamping. Zone 1: stamp a field's `*_updated_at` only when it actually
        // changed, so a field this edit leaves alone keeps its prior stamp and correctly LOSES the
        // per-field merge to a concurrent newer edit of that same field on another device. Zone 2:
        // advance the causal split_version (base+1) only when the money value changed — amount, mode,
        // payer, or the active share split — so a metadata-only edit never collides with a split edit.
        val newTitle = input.title.trim()
        val oldSplit = existingShares.filter { it.deletedAt == null }.associate { it.userId to it.shareOwedSubunits }
        val newSplit = shares.filter { it.deletedAt == null }.associate { it.userId to it.shareOwedSubunits }
        val splitChanged =
            input.amountSubunits != existing.amountSubunits ||
                input.currency != existing.currency ||
                input.splitMode != existing.splitMode ||
                input.payerUserId?.value != existing.payerUserId ||
                input.payerOutsideName != existing.payerOutsideName ||
                oldSplit != newSplit
        val updated =
            existing.copy(
                title = newTitle,
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
                titleUpdatedAt = if (newTitle != existing.title) now else existing.titleUpdatedAt,
                notesUpdatedAt = if (input.notes != existing.notes) now else existing.notesUpdatedAt,
                categoryUpdatedAt = if (input.categoryId != existing.categoryId) now else existing.categoryUpdatedAt,
                dateUpdatedAt = if (input.expenseDate != existing.expenseDate) now else existing.dateUpdatedAt,
                splitVersion = if (splitChanged) existing.splitVersion + 1 else existing.splitVersion,
                splitUpdatedBy = if (splitChanged) input.editedBy?.value else existing.splitUpdatedBy,
            )
        expenseDao.replaceWithShares(updated, shares, removedShareIds, now)
        recordHistory(expenseId.value, existing.groupId, HistoryEventType.EDITED, input.editedBy?.value, now)
        analytics?.capture(AnalyticsEvents.EXPENSE_EDITED, mapOf("group_id" to existing.groupId))
        return updated.toDomain().asOk()
    }

    override suspend fun deleteExpense(expenseId: ExpenseId): AppResult<Unit> {
        val existing =
            expenseDao.getById(expenseId.value)
                ?: return validationErr("expense", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        expenseDao.softDelete(expenseId.value, now)
        recordHistory(expenseId.value, existing.groupId, HistoryEventType.DELETED, actorUserId = null, now)
        analytics?.capture(AnalyticsEvents.EXPENSE_DELETED, mapOf("group_id" to existing.groupId))
        return AppResult.Ok(Unit)
    }

    // Track F retired bilateral edit-collision cards. Expenses now sync through the zone-aware
    // `merge_expense` (per-field metadata merge + causal split guard), which NEVER parks a two-sided
    // conflict — a superseded split edit is logged server-side and surfaces to its author ALONE as a
    // one-sided "your change was superseded — review?" notice (see SupersededNoticeDao). So this stream
    // is always empty; the Conflicts tab's edit half is dead. (The parking table + this method's old
    // body are left in place only until the branch is settled, to avoid colliding with concurrent sync
    // work; nothing writes to `expense_edit_conflicts` anymore.)
    override fun observeEditConflicts(groupId: GroupId): Flow<List<ExpenseEditConflict>> = flowOf(emptyList())

    @Suppress("unused")
    private fun observeEditConflictsLegacy(groupId: GroupId): Flow<List<ExpenseEditConflict>> {
        val dao = editConflictDao ?: return flowOf(emptyList())
        return combine(
            dao.observeUnresolved(groupId.value),
            expenseDao.observeByGroup(groupId.value),
            shareDao.observeByGroup(groupId.value),
        ) { conflicts, expenses, shares ->
            val byId = expenses.associateBy { it.id }
            val currentSharesByExpense = shares.groupBy { it.expenseId }
            conflicts.mapNotNull { c ->
                val rejectedExpense =
                    runCatching { payloadJson.decodeFromString<ExpenseEntity>(c.rejectedExpense) }.getOrNull()
                        ?: return@mapNotNull null
                val rejectedShares =
                    runCatching { payloadJson.decodeFromString<List<ShareEntity>>(c.rejectedShares) }.getOrNull()
                        ?: emptyList()
                val current = byId[c.expenseId]
                // Current side derives from the live expense + its active shares (fall back to the rejected
                // payload if the expense isn't hydrated yet, so the card still renders).
                val currentShareMap =
                    currentSharesByExpense[c.expenseId]
                        ?.associate { UserId(it.userId) to it.shareOwedSubunits }
                        ?: rejectedShares.filter { it.deletedAt == null }.associate { UserId(it.userId) to it.shareOwedSubunits }
                ExpenseEditConflict(
                    id = c.id,
                    expenseId = ExpenseId(c.expenseId),
                    rejectedBy = UserId(c.rejectedBy),
                    winnerBy = c.serverActor?.let { UserId(it) },
                    currency = current?.currency ?: rejectedExpense.currency,
                    current =
                        ConflictSide(
                            title = current?.title ?: rejectedExpense.title,
                            amountSubunits = current?.amountSubunits ?: rejectedExpense.amountSubunits,
                            splitMode = current?.splitMode ?: rejectedExpense.splitMode,
                            payerUserId = (current?.payerUserId ?: rejectedExpense.payerUserId)?.let { UserId(it) },
                            payerOutsideName = current?.payerOutsideName ?: rejectedExpense.payerOutsideName,
                            shares = currentShareMap,
                        ),
                    rejected =
                        ConflictSide(
                            title = rejectedExpense.title,
                            amountSubunits = rejectedExpense.amountSubunits,
                            splitMode = rejectedExpense.splitMode,
                            payerUserId = rejectedExpense.payerUserId?.let { UserId(it) },
                            payerOutsideName = rejectedExpense.payerOutsideName,
                            shares =
                                rejectedShares
                                    .filter { it.deletedAt == null }
                                    .associate { UserId(it.userId) to it.shareOwedSubunits },
                        ),
                    createdAt = c.createdAt,
                )
            }
        }
    }

    override suspend fun resolveEditConflict(
        conflictId: String,
        useRejected: Boolean,
        resolvedBy: UserId?,
    ): AppResult<Unit> {
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
        val rejectedExpense =
            runCatching { payloadJson.decodeFromString<ExpenseEntity>(conflict.rejectedExpense) }.getOrNull()
                ?: return validationErr("conflict", AppError.Validation.Reason.Malformed)
        val rejectedShares =
            runCatching { payloadJson.decodeFromString<List<ShareEntity>>(conflict.rejectedShares) }.getOrNull()
                ?: return validationErr("conflict", AppError.Validation.Reason.Malformed)

        val existingActive = shareDao.getByExpense(conflict.expenseId)
        val desired =
            rejectedShares.map {
                DesiredShare(it.userId, it.shareOwedSubunits, it.shareUnits, it.sharePercentage, it.shareExactSubunits)
            }
        val (merged, removed) = mergeShares(existingActive, desired, conflict.expenseId, now)
        val updated =
            current.copy(
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
    private fun List<NewShare>.toEntities(
        expenseId: String,
        now: Long,
    ): List<ShareEntity> =
        map { s ->
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
    private fun List<NewShare>.toDesired(): List<DesiredShare> =
        map { s ->
            DesiredShare(
                userId = s.userId.value,
                owedSubunits = s.owedSubunits,
                shareUnits = s.shareUnits,
                sharePercentage = s.sharePercentage,
                shareExactSubunits = s.shareExactSubunits,
            )
        }

    /** AC-INV-001 + basic field checks; null when the input is valid. */
    private fun validate(
        title: String,
        amountSubunits: Long,
        shares: List<NewShare>,
    ): AppResult<Nothing>? {
        val fieldErrors =
            buildMap {
                if (title.trim().isEmpty()) put("title", AppError.Validation.Reason.Required)
                if (amountSubunits <= 0L) put("amount", AppError.Validation.Reason.OutOfRange)
                when {
                    shares.isEmpty() -> put("shares", AppError.Validation.Reason.Required)
                    shares.sumOf { it.owedSubunits } != amountSubunits -> put("shares", AppError.Validation.Reason.Malformed)
                }
            }
        return if (fieldErrors.isEmpty()) null else AppError.Validation(fieldErrors).asErr()
    }

    private fun validationErr(
        field: String,
        reason: AppError.Validation.Reason,
    ): AppResult<Nothing> = AppError.Validation(mapOf(field to reason)).asErr()
}
