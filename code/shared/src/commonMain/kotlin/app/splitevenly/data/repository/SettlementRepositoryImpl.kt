package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.error.asOk
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.HistoryEventDao
import app.splitevenly.data.db.dao.SettlementDao
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.SettlementRecord
import app.splitevenly.domain.settlement.ShareBalance
import app.splitevenly.domain.settlement.allocateSameCurrency
import app.splitevenly.newId
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [SettlementRepository] (03 §4.3.1, 04 §6.2). A payment is distributed across the
 * debtor→creditor outstanding shares oldest-first via the pure [allocateSameCurrency] allocator,
 * then applied atomically (write settlement + allocations, pay down shares, re-status expenses).
 * Same-currency only for MVP; cross-FX (03 §4.3.2) is v1.1. Server push is deferred to S-1.
 */
@OptIn(ExperimentalTime::class)
class SettlementRepositoryImpl(
    private val settlementDao: SettlementDao,
    private val shareDao: ShareDao,
    private val clock: Clock = Clock.System,
    // Optional activity log (F5). Null in unit tests => no history rows; production DI wires it.
    private val historyEventDao: HistoryEventDao? = null,
    // Analytics: null in unit tests; production DI passes AndroidAnalytics.
    private val analytics: EvAnalytics? = null,
) : SettlementRepository {

    override fun observeSettlements(groupId: GroupId): Flow<List<SettlementRecord>> =
        settlementDao.observeByGroup(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeCoveredExpenseTitles(groupId: GroupId): Flow<Map<SettlementId, List<String>>> =
        settlementDao.observeCoveredTitlesByGroup(groupId.value).map { rows ->
            rows.groupBy({ SettlementId(it.settlementId) }, { it.title })
        }

    override suspend fun applySettlement(input: NewSettlement): AppResult<SettlementRecord> {
        val write = writeSettlement(input)
        return when (write) {
            is AppResult.Err -> write
            is AppResult.Ok -> {
                // One SETTLED activity-log row per expense this payment touched (no-op when the log isn't
                // wired). [detail] carries the amount as a raw token ("amt:<subunits>") so the UI formats it
                // in the expense's currency at render time — the repo stays free of currency-formatting.
                historyEventDao?.let { dao ->
                    write.value.appliedByExpense.forEach { (expenseId, applied) ->
                        dao.upsert(
                            HistoryEventEntity(
                                id = newId(),
                                expenseId = expenseId,
                                groupId = input.groupId.value,
                                actorUserId = input.createdBy.value,
                                type = HistoryEventType.SETTLED.name,
                                detail = "amt:$applied",
                                createdAt = write.value.settledAt,
                            ),
                        )
                    }
                }
                analytics?.capture(AnalyticsEvents.SETTLEMENT_APPLIED, mapOf("group_id" to input.groupId.value))
                write.value.record.asOk()
            }
        }
    }

    /**
     * The shared write core behind [applySettlement] and [editSettlement]: validate, allocate the payment
     * across the debtor→creditor outstanding shares (oldest-first, [NewSettlement.expenseId]-scoped when
     * set), then write the settlement + its allocations atomically. Shares are **not** mutated — remaining
     * derives on read. Returns the applied-per-expense breakdown so the caller logs the right activity row.
     */
    private suspend fun writeSettlement(input: NewSettlement): AppResult<WriteResult> {
        if (input.paymentAmountSubunits <= 0L) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        // Same-currency shares the debtor still owes the creditor, oldest first (03 §4.2). When scoped to a
        // single expense (the "Settle 'X'" sheet), restrict allocation to that expense's shares so a
        // *partial* payment pays down the expense the user is looking at — not the oldest outstanding one.
        val selectedExpenseIds = input.expenseIds?.map { it.value }?.toSet()
        val outstanding = shareDao
            .outstandingForPair(input.groupId.value, input.fromUserId.value, input.toUserId.value)
            .filter { it.currency == input.paymentCurrency }
            .filter {
                when {
                    input.expenseId != null -> it.expenseId == input.expenseId.value
                    selectedExpenseIds != null -> it.expenseId in selectedExpenseIds
                    else -> true
                }
            }
        val totalOutstanding = outstanding.sumOf { it.remainingSubunits }
        if (input.paymentAmountSubunits > totalOutstanding) {
            // PAYMENT_OVERALLOCATED (04 §2.2): can't pay more than is owed in this currency.
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        val allocations = allocateSameCurrency(
            paymentAmountSubunits = input.paymentAmountSubunits,
            shares = outstanding.map { ShareBalance(it.shareId, it.currency, it.remainingSubunits) },
        )
        val now = clock.nowEpochMillis()
        val settlementId = newId()
        val settlement = SettlementEntity(
            id = settlementId,
            groupId = input.groupId.value,
            fromUserId = input.fromUserId.value,
            toUserId = input.toUserId.value,
            paymentCurrency = input.paymentCurrency,
            paymentAmountSubunits = input.paymentAmountSubunits,
            paymentApp = input.paymentApp,
            deepLinkAttempted = input.deepLinkAttempted,
            deepLinkSucceeded = input.deepLinkSucceeded,
            settledAt = now,
            notes = input.notes,
            createdBy = input.createdBy.value,
            createdAt = now,
            updatedAt = now,
        )
        val expenseIdByShare = outstanding.associate { it.shareId to it.expenseId }
        val allocationEntities = allocations.map { a ->
            SettlementAllocationEntity(
                id = newId(),
                settlementId = settlementId,
                groupId = input.groupId.value,
                shareId = a.shareId,
                appliedAmountSubunits = a.appliedSubunits,
                appliedCurrency = input.paymentCurrency,
                createdAt = now,
            )
        }
        // How much of this payment landed on each expense — the audit-visible amount per activity row.
        val appliedByExpense = allocations
            .groupBy { expenseIdByShare[it.shareId] }
            .mapNotNull { (expenseId, allocs) -> expenseId?.let { it to allocs.sumOf { a -> a.appliedSubunits } } }
            .toMap()
        // Record the settlement + allocations — shares aren't touched; remaining derives from these. The
        // DAO re-checks against fresh in-txn state and refuses if a concurrent settlement already paid this
        // down (would over-apply, #9b); surface that as the same over-allocation validation error.
        if (!settlementDao.applySettlement(settlement, allocationEntities)) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        return WriteResult(settlement.toDomain(), appliedByExpense, now).asOk()
    }

    private data class WriteResult(
        val record: SettlementRecord,
        val appliedByExpense: Map<String, Long>,
        val settledAt: Long,
    )

    override fun observePaymentsForExpense(expenseId: ExpenseId): Flow<List<SettlementRecord>> =
        settlementDao.observeByExpense(expenseId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun editSettlement(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        actor: UserId?,
    ): AppResult<SettlementRecord> {
        if (newAmountSubunits <= 0L) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        val existing = settlementDao.getById(settlementId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("settlement", AppError.Validation.Reason.Required)
        }
        // Only single-expense payments are correctable from an expense screen: re-recording a
        // relationship-wide payment here would silently re-scope another expense's balance.
        val allocations = settlementDao.allocationsForSettlement(settlementId.value)
        val expenseIds = shareDao.expenseIdsForShares(allocations.map { it.shareId }).distinct()
        if (expenseIds.size != 1) {
            return validationErr("settlement", AppError.Validation.Reason.OutOfRange)
        }
        val expenseId = expenseIds.first()
        // Ceiling: what's still owed on this expense for the pair, PLUS what this payment currently covers
        // (it's about to be replaced). Guarding here — *before* voiding — means a rejected edit never
        // destroys the existing payment, and a correction can't manufacture an overpayment (Rule 5).
        val outstandingNow = shareDao
            .outstandingForPair(existing.groupId, existing.fromUserId, existing.toUserId)
            .filter { it.currency == existing.paymentCurrency && it.expenseId == expenseId }
            .sumOf { it.remainingSubunits }
        val thisApplied = allocations.sumOf { it.appliedAmountSubunits }
        if (newAmountSubunits > outstandingNow + thisApplied) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        // Correct in place: void the old payment (soft-delete, Rule 1) then re-record the new amount,
        // preserving the original payer, payee, currency, app, and notes.
        val now = clock.nowEpochMillis()
        settlementDao.voidSettlement(settlementId.value, now)
        val write = writeSettlement(
            NewSettlement(
                groupId = GroupId(existing.groupId),
                fromUserId = UserId(existing.fromUserId),
                toUserId = UserId(existing.toUserId),
                paymentCurrency = existing.paymentCurrency,
                paymentAmountSubunits = newAmountSubunits,
                createdBy = actor ?: UserId(existing.createdBy),
                expenseId = ExpenseId(expenseId),
                notes = existing.notes,
                paymentApp = existing.paymentApp,
            ),
        )
        return when (write) {
            is AppResult.Err -> write
            is AppResult.Ok -> {
                // Activity feed: "corrected a payment · $old → $new" (edit:<old>:<new> token).
                historyEventDao?.upsert(
                    HistoryEventEntity(
                        id = newId(),
                        expenseId = expenseId,
                        groupId = existing.groupId,
                        actorUserId = (actor ?: UserId(existing.createdBy)).value,
                        type = HistoryEventType.SETTLEMENT_EDITED.name,
                        detail = "edit:${existing.paymentAmountSubunits}:$newAmountSubunits",
                        createdAt = now,
                    ),
                )
                write.value.record.asOk()
            }
        }
    }

    override suspend fun voidSettlement(settlementId: SettlementId): AppResult<Unit> {
        val existing = settlementDao.getById(settlementId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("settlement", AppError.Validation.Reason.Required)
        }
        // Soft-delete only: the allocations stay as history but drop out of the derived remaining (the
        // join filters non-voided settlements), so the paid-down amount is restored automatically.
        settlementDao.voidSettlement(settlementId.value, clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
