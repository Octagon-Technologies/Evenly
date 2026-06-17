package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.SettlementId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.HistoryEventDao
import da.chelimo.sharecost.data.db.dao.SettlementDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.domain.activity.HistoryEventType
import da.chelimo.sharecost.domain.repository.SettlementRepository
import da.chelimo.sharecost.domain.settlement.NewSettlement
import da.chelimo.sharecost.domain.settlement.SettlementRecord
import da.chelimo.sharecost.domain.settlement.ShareBalance
import da.chelimo.sharecost.domain.settlement.allocateSameCurrency
import da.chelimo.sharecost.newId
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
) : SettlementRepository {

    override fun observeSettlements(groupId: GroupId): Flow<List<SettlementRecord>> =
        settlementDao.observeByGroup(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun applySettlement(input: NewSettlement): AppResult<SettlementRecord> {
        if (input.paymentAmountSubunits <= 0L) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }

        // Same-currency shares the debtor still owes the creditor, oldest first (03 §4.2).
        val outstanding = shareDao
            .outstandingForPair(input.groupId.value, input.fromUserId.value, input.toUserId.value)
            .filter { it.currency == input.paymentCurrency }
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
                shareId = a.shareId,
                appliedAmountSubunits = a.appliedSubunits,
                appliedCurrency = input.paymentCurrency,
                createdAt = now,
            )
        }
        val affectedExpenseIds = allocations.mapNotNull { expenseIdByShare[it.shareId] }.distinct()
        settlementDao.applySettlement(settlement, allocationEntities, affectedExpenseIds, now)
        // One SETTLED activity-log row per expense this payment touched (no-op when the log isn't wired).
        historyEventDao?.let { dao ->
            affectedExpenseIds.forEach { expenseId ->
                dao.upsert(
                    HistoryEventEntity(
                        id = newId(),
                        expenseId = expenseId,
                        groupId = input.groupId.value,
                        actorUserId = input.createdBy.value,
                        type = HistoryEventType.SETTLED.name,
                        detail = null,
                        createdAt = now,
                    ),
                )
            }
        }
        return settlement.toDomain().asOk()
    }

    override suspend fun voidSettlement(settlementId: SettlementId): AppResult<Unit> {
        val existing = settlementDao.getById(settlementId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("settlement", AppError.Validation.Reason.Required)
        }
        val allocations = settlementDao.allocationsForSettlement(settlementId.value)
        val affectedExpenseIds =
            if (allocations.isEmpty()) emptyList()
            else shareDao.expenseIdsForShares(allocations.map { it.shareId })
        settlementDao.voidSettlement(settlementId.value, allocations, affectedExpenseIds, clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
