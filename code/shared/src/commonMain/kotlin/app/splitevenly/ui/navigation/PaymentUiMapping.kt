package app.splitevenly.ui.navigation

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.localNoonEpochMillis
import app.splitevenly.core.time.shortDate
import app.splitevenly.core.time.shortDateTime
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.PaymentEditRefusal
import app.splitevenly.domain.settlement.SettlementRecord
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.expense.paymentMethodLabel
import app.splitevenly.ui.screen.settle.PaymentPreviewLineUi
import app.splitevenly.ui.screen.settle.PaymentPreviewUi
import app.splitevenly.ui.screen.settle.PaymentUi

/**
 * A payment row for a payments list. [shownSubunits] is what the row is about (this bill's portion on a
 * bill screen, the total on the settle-up screen); [nameOf] returns "You" for the viewer.
 */
internal fun paymentUi(
    record: SettlementRecord,
    viewer: UserId?,
    shownSubunits: Long,
    billCount: Int,
    nameOf: (UserId?) -> String,
): PaymentUi =
    PaymentUi(
        id = record.id.value,
        payerName = nameOf(record.fromUserId),
        byMe = record.fromUserId == viewer,
        amountSubunits = shownSubunits,
        payeeName = nameOf(record.toUserId),
        toMe = record.toUserId == viewer,
        totalSubunits = record.paymentAmountSubunits,
        billCount = billCount,
        app = paymentMethodLabel(record.paymentApp),
        dateLabel = shortDate(record.settledAt),
        dateTimeLabel = shortDateTime(record.settledAt),
        recordedBy = record.createdBy?.let { if (it == viewer) "you" else nameOf(it) }.orEmpty(),
        fromUserId = record.fromUserId.value,
        storedMethod = record.paymentApp,
        settledAt = record.settledAt,
        chainId = record.chainId,
    )

/** The edit sheet's preview for an entered amount, with the refusal said in plain words. */
internal suspend fun paymentPreview(
    settlements: SettlementRepository,
    settlementId: String,
    amountSubunits: Long,
    currency: String,
    scopeExpenseId: ExpenseId?,
): PaymentPreviewUi {
    val r = settlements.previewSettlementEdit(SettlementId(settlementId), amountSubunits, scopeExpenseId)
    if (r !is AppResult.Ok) return PaymentPreviewUi(problem = "This payment can't be changed right now.")
    val p = r.value
    val problem =
        when (p.refusal) {
            null -> null
            PaymentEditRefusal.NotPositive -> "Enter an amount. To take the payment off, use \"I didn't pay this\"."
            PaymentEditRefusal.AboveCeiling -> "It can't be more than ${moneySubunits(p.ceilingSubunits, currency)}, what's owed."
            PaymentEditRefusal.NotCovered -> "This payment isn't on this bill."
        }
    return PaymentPreviewUi(
        lines = p.lines.map { PaymentPreviewLineUi(it.title, shortIsoDate(it.expenseDate), it.beforeSubunits, it.afterSubunits) },
        problem = problem,
    )
}

/** Saves an edit; returns the reason it was refused, or null once it is saved. */
internal suspend fun savePaymentEdit(
    settlements: SettlementRepository,
    settlementId: String,
    amountSubunits: Long,
    actor: UserId?,
    scopeExpenseId: ExpenseId?,
    paymentApp: String? = null,
    settledAt: Long? = null,
): String? {
    if (actor == null) return "Sign in to change this payment."
    val r = settlements.editSettlement(SettlementId(settlementId), amountSubunits, actor, scopeExpenseId, paymentApp, settledAt)
    return when (r) {
        is AppResult.Ok -> {
            null
        }

        is AppResult.Err -> {
            if (r.error is AppError.NotAuthorized) {
                "Only the two people in this payment can change it."
            } else {
                "That amount is more than what's owed. Try a smaller one."
            }
        }
    }
}

/**
 * Records a payment on one bill, from the person who owed to the person who paid it. Returns the reason
 * it was refused, or null once it is saved. A payment dated today keeps the real time; an earlier day is
 * stored at local noon, since only the day was picked.
 */
internal suspend fun recordBillPayment(
    settlements: SettlementRepository,
    groupId: GroupId,
    expenseId: ExpenseId,
    currency: String,
    from: UserId,
    to: UserId,
    actor: UserId?,
    amountSubunits: Long,
    method: String?,
    date: String,
    today: String,
): String? {
    if (actor == null) return "Sign in to record a payment."
    val r =
        settlements.applySettlement(
            NewSettlement(
                groupId = groupId,
                fromUserId = from,
                toUserId = to,
                paymentCurrency = currency,
                paymentAmountSubunits = amountSubunits,
                createdBy = actor,
                expenseId = expenseId,
                paymentApp = method,
                settledAt = if (date == today) null else localNoonEpochMillis(date),
            ),
        )
    return when (r) {
        is AppResult.Ok -> null
        is AppResult.Err -> "That's more than what's left. It may have been paid already, so check History."
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-11-12" → "Nov 12"; anything unparseable passes through. */
private fun shortIsoDate(iso: String): String {
    val parts = iso.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return iso
    val day = parts.getOrNull(2)?.take(2)?.toIntOrNull() ?: return iso
    return MONTHS.getOrNull(month - 1)?.let { "$it $day" } ?: iso
}
