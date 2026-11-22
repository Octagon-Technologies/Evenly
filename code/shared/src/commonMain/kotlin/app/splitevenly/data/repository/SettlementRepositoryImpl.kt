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
import app.splitevenly.data.db.dao.SettlementReplaceRefused
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.projection.ShareExpenseRow
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.CoveredPortion
import app.splitevenly.domain.settlement.ExpensePayment
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.PaymentEditPreview
import app.splitevenly.domain.settlement.PaymentEditPreviewLine
import app.splitevenly.domain.settlement.PaymentRespread
import app.splitevenly.domain.settlement.SettlementRecord
import app.splitevenly.domain.settlement.ShareBalance
import app.splitevenly.domain.settlement.allocateSameCurrency
import app.splitevenly.domain.settlement.isPaymentParty
import app.splitevenly.domain.settlement.respreadPayment
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
            is AppResult.Err -> {
                write
            }

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
        val outstanding =
            shareDao
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
        val allocations =
            allocateSameCurrency(
                paymentAmountSubunits = input.paymentAmountSubunits,
                shares = outstanding.map { ShareBalance(it.shareId, it.currency, it.remainingSubunits) },
            )
        val now = clock.nowEpochMillis()
        val settlementId = newId()
        val settlement =
            SettlementEntity(
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
        val allocationEntities =
            allocations.map { a ->
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
        val appliedByExpense =
            allocations
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

    override fun observeExpensePayments(expenseId: ExpenseId): Flow<List<ExpensePayment>> =
        settlementDao.observePaymentsOnExpense(expenseId.value).map { rows ->
            rows.map { ExpensePayment(it.settlement.toDomain(), it.appliedHere, it.billCount) }
        }

    /** Everything an edit is computed from, read once so the preview and the save agree. */
    private class EditInputs(
        val existing: SettlementEntity,
        val portions: List<CoveredPortion>,
        val outstanding: List<ShareBalance>,
        val billByShare: Map<String, ShareExpenseRow>,
    )

    private suspend fun editInputs(settlementId: SettlementId): AppResult<EditInputs> {
        val existing = settlementDao.getById(settlementId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("settlement", AppError.Validation.Reason.Required)
        }
        val allocations = settlementDao.allocationsForSettlement(settlementId.value)
        // Live remaining per share with this payment still applied: the room an increase has on top.
        val outstandingRows =
            shareDao
                .outstandingForPair(existing.groupId, existing.fromUserId, existing.toUserId)
                .filter { it.currency == existing.paymentCurrency }
        val shareIds = (allocations.map { it.shareId } + outstandingRows.map { it.shareId }).distinct()
        val billByShare = if (shareIds.isEmpty()) emptyMap() else settlementDao.shareExpenses(shareIds).associateBy { it.shareId }
        val portions =
            allocations.map { a ->
                val bill = billByShare[a.shareId]
                CoveredPortion(
                    shareId = a.shareId,
                    expenseId = bill?.expenseId.orEmpty(),
                    expenseOrder = "${bill?.expenseDate.orEmpty()}|${bill?.expenseId.orEmpty()}",
                    appliedSubunits = a.appliedAmountSubunits,
                )
            }
        val outstanding = outstandingRows.map { ShareBalance(it.shareId, it.currency, it.remainingSubunits) }
        return EditInputs(existing, portions, outstanding, billByShare).asOk()
    }

    override suspend fun previewSettlementEdit(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        scopeExpenseId: ExpenseId?,
    ): AppResult<PaymentEditPreview> {
        val inputs =
            when (val r = editInputs(settlementId)) {
                is AppResult.Err -> return r
                is AppResult.Ok -> r.value
            }
        val respread = respreadPayment(inputs.portions, inputs.outstanding, newAmountSubunits, scopeExpenseId?.value)
        val before = inputs.portions.groupBy { it.shareId }.mapValues { (_, p) -> p.sumOf { it.appliedSubunits } }
        val after = (respread as? PaymentRespread.Ok)?.appliedByShare ?: before
        val lines =
            (before.keys + after.keys)
                .distinct()
                .mapNotNull { shareId ->
                    val bill = inputs.billByShare[shareId] ?: return@mapNotNull null
                    PaymentEditPreviewLine(
                        expenseId = ExpenseId(bill.expenseId),
                        title = bill.title,
                        expenseDate = bill.expenseDate,
                        beforeSubunits = before[shareId] ?: 0L,
                        afterSubunits = after[shareId] ?: 0L,
                    )
                }.sortedBy { it.expenseDate }
        return PaymentEditPreview(
            oldTotalSubunits = inputs.existing.paymentAmountSubunits,
            newTotalSubunits = (respread as? PaymentRespread.Ok)?.newTotalSubunits ?: inputs.existing.paymentAmountSubunits,
            lines = lines,
            refusal = (respread as? PaymentRespread.Refused)?.reason,
            ceilingSubunits = (respread as? PaymentRespread.Refused)?.ceilingSubunits ?: 0L,
        ).asOk()
    }

    override suspend fun editSettlement(
        settlementId: SettlementId,
        newAmountSubunits: Long,
        actor: UserId?,
        scopeExpenseId: ExpenseId?,
    ): AppResult<SettlementRecord> {
        if (newAmountSubunits <= 0L) {
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        val inputs =
            when (val r = editInputs(settlementId)) {
                is AppResult.Err -> return r
                is AppResult.Ok -> r.value
            }
        val existing = inputs.existing
        // Only the two people in the payment. The server's `settlement_party_guard` refuses it as well;
        // checking here is what lets the sheet say so instead of queueing a push that can never land.
        if (actor != null && !isPaymentParty(existing.fromUserId, existing.toUserId, actor.value)) {
            return AppError.NotAuthorized.asErr()
        }
        val respread = respreadPayment(inputs.portions, inputs.outstanding, newAmountSubunits, scopeExpenseId?.value)
        if (respread !is PaymentRespread.Ok) {
            // Over the ceiling (or a bill this payment never covered): refused before anything is voided,
            // so a correction can't manufacture an overpayment (Rule 5) or lose the original.
            return validationErr("amount", AppError.Validation.Reason.OutOfRange)
        }
        // Void + re-record in ONE `replaceSettlement` transaction. Two separate writes left the payment
        // voided with no replacement after a crash between them: money actually paid became owed again.
        val now = clock.nowEpochMillis()
        val newSettlementId = newId()
        val newAllocations =
            respread.appliedByShare.map { (shareId, applied) ->
                SettlementAllocationEntity(
                    id = newId(),
                    settlementId = newSettlementId,
                    groupId = existing.groupId,
                    shareId = shareId,
                    appliedAmountSubunits = applied,
                    appliedCurrency = existing.paymentCurrency,
                    createdAt = now,
                )
            }
        val actorId = (actor ?: UserId(existing.createdBy)).value
        val replacement =
            existing.copy(
                id = newSettlementId,
                paymentAmountSubunits = respread.newTotalSubunits,
                settledAt = now,
                createdBy = actorId,
                createdAt = now,
                updatedAt = now,
                rowVersion = 1,
                deletedAt = null,
                // The chain's first payment: what the server's last-edit-wins rule groups replacements by.
                originalSettlementId = existing.originalSettlementId ?: existing.id,
            )
        val replaced =
            try {
                settlementDao.replaceSettlement(settlementId.value, now, replacement, newAllocations)
            } catch (e: SettlementReplaceRefused) {
                // The in-transaction guard tripped (a payment landed mid-edit), so the void rolled back with
                // it and the original payment is intact.
                false
            }
        if (!replaced) return validationErr("amount", AppError.Validation.Reason.OutOfRange)

        // One history row per bill whose money moved: "Bob changed Alice's payment $15.00 → $5.00",
        // plus what now lands on that bill. Token: edit:<oldTotal>:<newTotal>:<payer>:<newOnThisBill>.
        historyEventDao?.let { dao ->
            val beforeByBill = inputs.portions.groupBy { it.expenseId }.mapValues { (_, p) -> p.sumOf { it.appliedSubunits } }
            val afterByBill =
                respread.appliedByShare.entries
                    .groupBy { inputs.billByShare[it.key]?.expenseId.orEmpty() }
                    .mapValues { (_, e) -> e.sumOf { it.value } }
            (beforeByBill.keys + afterByBill.keys)
                .filter { it.isNotEmpty() && (beforeByBill[it] ?: 0L) != (afterByBill[it] ?: 0L) }
                .forEach { expenseId ->
                    dao.upsert(
                        HistoryEventEntity(
                            id = newId(),
                            expenseId = expenseId,
                            groupId = existing.groupId,
                            actorUserId = actorId,
                            type = HistoryEventType.SETTLEMENT_EDITED.name,
                            detail =
                                "edit:${existing.paymentAmountSubunits}:${respread.newTotalSubunits}:" +
                                    "${existing.fromUserId}:${afterByBill[expenseId] ?: 0L}",
                            createdAt = now,
                        ),
                    )
                }
        }
        return replacement.toDomain().asOk()
    }

    override suspend fun voidSettlement(
        settlementId: SettlementId,
        actor: UserId?,
    ): AppResult<Unit> {
        val existing = settlementDao.getById(settlementId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("settlement", AppError.Validation.Reason.Required)
        }
        if (actor != null && !isPaymentParty(existing.fromUserId, existing.toUserId, actor.value)) {
            return AppError.NotAuthorized.asErr()
        }
        // Soft-delete only: the allocations stay as history but drop out of the derived remaining (the
        // join filters non-voided settlements), so the paid-down amount is restored automatically.
        val now = clock.nowEpochMillis()
        settlementDao.voidSettlement(settlementId.value, now)
        if (actor != null) {
            historyEventDao?.let { dao ->
                val shareIds = settlementDao.allocationsForSettlement(settlementId.value).map { it.shareId }
                val bills = if (shareIds.isEmpty()) emptyList() else settlementDao.shareExpenses(shareIds).map { it.expenseId }.distinct()
                bills.forEach { expenseId ->
                    dao.upsert(
                        HistoryEventEntity(
                            id = newId(),
                            expenseId = expenseId,
                            groupId = existing.groupId,
                            actorUserId = actor.value,
                            type = HistoryEventType.SETTLEMENT_REMOVED.name,
                            detail = "removed:${existing.paymentAmountSubunits}:${existing.fromUserId}",
                            createdAt = now,
                        ),
                    )
                }
            }
        }
        return AppResult.Ok(Unit)
    }

    private fun validationErr(
        field: String,
        reason: AppError.Validation.Reason,
    ): AppResult<Nothing> = AppError.Validation(mapOf(field to reason)).asErr()
}
