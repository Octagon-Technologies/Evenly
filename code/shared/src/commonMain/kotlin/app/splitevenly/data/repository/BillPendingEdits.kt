package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.ExpenseDao
import app.splitevenly.data.db.dao.ExpenseItemDao
import app.splitevenly.data.db.dao.ItemClaimDao
import app.splitevenly.data.db.dao.ItemShareDao
import app.splitevenly.data.db.dao.PendingItemEditDao
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.entity.PendingItemEditEntity
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.PendingBillEdit
import app.splitevenly.domain.expense.PendingEditDecision
import app.splitevenly.domain.expense.PendingEditKind
import app.splitevenly.domain.expense.perUnitSubunits
import app.splitevenly.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The payer's half of the web claim flow (WEB_CLAIM_SPEC.md §3.9), split out of [BillRepositoryImpl] —
 * which is a quarantined size offender (`ui/AGENTS.md`) — because approving a guest's edit is a
 * self-contained "apply, re-version, re-derive" unit with no overlap with the live claim writes.
 *
 * [BillRepositoryImpl] owns the public contract; these two only do the work.
 */

/** Deciding a web guest's proposed menu change, one card at a time (spec §2.7, §3.9.1). */
@OptIn(ExperimentalTime::class)
internal class BillPendingEdits(
    private val expenseDao: ExpenseDao,
    private val expenseItemDao: ExpenseItemDao,
    private val itemClaimDao: ItemClaimDao,
    private val itemShareDao: ItemShareDao,
    private val pendingItemEditDao: PendingItemEditDao,
    private val materializer: BillMaterializer,
    private val clock: Clock,
) {

    fun observe(expenseId: ExpenseId): Flow<List<PendingBillEdit>> =
        pendingItemEditDao.observeByExpense(expenseId.value).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun decide(editId: String, approve: Boolean, decidedBy: UserId): AppResult<Unit> {
        val row = pendingItemEditDao.getById(editId) ?: return validationErr("pendingEdit", AppError.Validation.Reason.Required)
        // Already decided. Idempotent rather than an error: two taps on a slow card must not produce a
        // second application of the same price change.
        if (row.decidedAt != null) return AppResult.Ok(Unit)
        val edit = row.toDomain() ?: return validationErr("pendingEdit", AppError.Validation.Reason.Malformed)
        val expense = expenseDao.getById(row.expenseId)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()

        if (approve) applyApprovedEdit(expense, edit, now)?.let { return it }
        // Stamp last: an approval that could not be applied leaves the card in the payer's inbox rather
        // than recording a verdict for a change that never landed.
        pendingItemEditDao.decide(editId, if (approve) "APPROVED" else "REJECTED", decidedBy.value, now)
        return AppResult.Ok(Unit)
    }

    /**
     * Apply an approved proposal to the bill's menu, then re-derive. Returns an error to abort the
     * decision, or null on success.
     *
     * Every branch touches money, so it advances the causal `split_version` exactly as
     * [BillRepositoryImpl.editBill] does —
     * a guest's approved reprice is a Zone-2 edit and has to lose to, or beat, a concurrent split edit on
     * the same causal rules as any other (see `data/AGENTS.md`). Missing it would let `merge_expense`
     * silently drop the approval.
     */
    private suspend fun applyApprovedEdit(
        expense: ExpenseEntity,
        edit: PendingBillEdit,
        now: Long,
    ): AppResult<Unit>? {
        val items = expenseItemDao.getByExpense(expense.id)
        val target = edit.itemId?.let { id -> items.firstOrNull { it.id == id && it.deletedAt == null } }

        when (edit.kind) {
            PendingEditKind.ADD -> {
                val quantity = (edit.proposedQuantity ?: 1).coerceAtLeast(1)
                val lineTotal = (edit.proposedUnitPriceSubunits ?: 0L) * quantity
                expenseItemDao.upsert(
                    ExpenseItemEntity(
                        id = newId(),
                        expenseId = expense.id,
                        groupId = expense.groupId,
                        label = edit.proposedLabel?.trim().orEmpty().ifEmpty { "Item" },
                        quantity = quantity,
                        unitPriceSubunits = perUnitSubunits(lineTotal, quantity),
                        lineTotalSubunits = lineTotal,
                        // Lands at the end of the receipt: it wasn't printed on it.
                        sortOrder = (items.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            // The line went while the proposal waited. Nothing to apply, and refusing would strand the
            // card forever, so this is a successful no-op that still records the verdict.
            PendingEditKind.RELABEL, PendingEditKind.REPRICE, PendingEditKind.REQUANTITY, PendingEditKind.REMOVE -> {
                val item = target ?: return null
                when (edit.kind) {
                    PendingEditKind.REMOVE -> {
                        expenseItemDao.softDeleteByIds(listOf(item.id), now)
                        // Claims on a line that no longer exists must go with it, or they keep counting
                        // toward people's tabs (spec E16 — the guest's next poll drops the line).
                        itemClaimDao.softDeleteByItems(listOf(item.id), now)
                        itemShareDao.softDeleteByItems(listOf(item.id), now)
                    }
                    PendingEditKind.RELABEL -> expenseItemDao.upsert(
                        item.copy(
                            label = edit.proposedLabel?.trim().orEmpty().ifEmpty { item.label },
                            updatedAt = now,
                            rowVersion = item.rowVersion + 1,
                        ),
                    )
                    // The web editor collects a per-unit price and a quantity; the bill stores the line
                    // total as truth (`domain/AGENTS.md`), so both branches multiply back out rather than
                    // writing unit_price_subunits, which is vestigial.
                    PendingEditKind.REPRICE -> {
                        val quantity = item.quantity
                        val lineTotal = (edit.proposedUnitPriceSubunits ?: return null) * quantity
                        expenseItemDao.upsert(
                            item.copy(
                                lineTotalSubunits = lineTotal,
                                unitPriceSubunits = perUnitSubunits(lineTotal, quantity),
                                updatedAt = now,
                                rowVersion = item.rowVersion + 1,
                            ),
                        )
                    }
                    PendingEditKind.REQUANTITY -> {
                        val quantity = (edit.proposedQuantity ?: return null).coerceAtLeast(1)
                        // Per-unit price is held constant, since that is what "change the quantity" means
                        // on a receipt: three bowls cost three times one bowl.
                        val perUnit = edit.proposedUnitPriceSubunits
                            ?: perUnitSubunits(item.lineTotalSubunits, item.quantity)
                        val lineTotal = perUnit * quantity
                        expenseItemDao.upsert(
                            item.copy(
                                quantity = quantity,
                                lineTotalSubunits = lineTotal,
                                unitPriceSubunits = perUnitSubunits(lineTotal, quantity),
                                updatedAt = now,
                                rowVersion = item.rowVersion + 1,
                            ),
                        )
                    }
                    PendingEditKind.ADD -> Unit // unreachable; handled above
                }
            }
        }

        val fresh = expenseItemDao.getByExpense(expense.id).filter { it.deletedAt == null }
        val amount = total(fresh.map { it.quantity to it.lineTotalSubunits }, expense.toExtras())
        val updated = expense.copy(
            amountSubunits = amount,
            updatedAt = now,
            rowVersion = expense.rowVersion + 1,
            splitVersion = expense.splitVersion + 1,
            splitUpdatedBy = expense.splitUpdatedBy,
        )
        expenseDao.upsert(updated)
        materializer.materialize(updated, now)
        return null
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()

    /** Bill total = Σ(line totals) + tax + gratuity + tip − discount, same rule as [BillRepositoryImpl]. */
    private fun total(lines: List<Pair<Int, Long>>, extras: BillExtrasInput): Long =
        lines.sumOf { (_, lineTotal) -> lineTotal } +
            extras.taxSubunits + extras.gratuitySubunits + extras.tipSubunits - extras.discountSubunits
}

/**
 * What the payer does about the people who never claimed at all (spec §3.9.2, E17). Kept separate from
 * [BillPendingEdits] because it needs no `pending_item_edits` DAO, so it is available on every build.
 */
@OptIn(ExperimentalTime::class)
internal class BillRemainder(
    private val expenseDao: ExpenseDao,
    private val expenseItemDao: ExpenseItemDao,
    private val itemClaimDao: ItemClaimDao,
    private val itemShareDao: ItemShareDao,
    private val materializer: BillMaterializer,
    private val clock: Clock,
) {

    private fun shareId(itemId: String, userId: String, portionId: String) = "${itemId}__${userId}__$portionId"

    suspend fun assign(
        expenseId: ExpenseId,
        memberIds: List<UserId>,
        addedBy: UserId,
    ): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val targets = memberIds.mapTo(LinkedHashSet()) { it.value }
        if (targets.isEmpty()) return validationErr("members", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        val items = expenseItemDao.getByExpense(expenseId.value).filter { it.deletedAt == null }
        val claims = itemClaimDao.getByExpense(expenseId.value).filter { it.deletedAt == null }
        val shares = itemShareDao.getByExpense(expenseId.value).filter { it.deletedAt == null }

        for (item in items) {
            // Units already spoken for = solo claims + one count per distinct live portion (never per
            // member) — the same arithmetic join_item_portion does server-side.
            val soloUnits = claims.filter { it.itemId == item.id }.sumOf { it.quantity }
            val portionUnits = shares.filter { it.itemId == item.id && it.portionId != null }
                .groupBy { it.portionId }
                .values.sumOf { rows -> rows.first().quantity }
            val left = item.quantity - soloUnits - portionUnits
            if (left <= 0) continue
            // A NEW portion, so nobody's existing claim is being rewritten and the "claims are partitioned
            // by user" invariant is never in play — no server RPC needed. A deterministic id means running
            // this twice converges on one slice instead of stacking two.
            val portionId = "${item.id}__remainder"
            val rows = targets.map { uid ->
                ItemShareEntity(
                    id = shareId(item.id, uid, portionId),
                    itemId = item.id,
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = uid,
                    portionId = portionId,
                    quantity = left,
                    addedBy = addedBy.value,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            rows.forEach { itemShareDao.upsert(it) }
        }
        materializer.materialize(expense, now)
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}

/** Row → domain. Returns null for a `kind` this build does not know, so a newer web client proposing
 *  something unrecognised is skipped rather than crashing the payer's review screen. */
private fun PendingItemEditEntity.toDomain(): PendingBillEdit? {
    val parsedKind = PendingEditKind.entries.firstOrNull { it.name == kind } ?: return null
    return PendingBillEdit(
        id = id,
        expenseId = expenseId,
        itemId = itemId,
        kind = parsedKind,
        proposedBy = UserId(proposedBy),
        proposedAt = proposedAt,
        proposedLabel = proposedLabel,
        proposedQuantity = proposedQuantity,
        proposedUnitPriceSubunits = proposedUnitPriceSubunits,
        previousLabel = previousLabel,
        previousQuantity = previousQuantity,
        previousUnitPriceSubunits = previousUnitPriceSubunits,
        decision = decision?.let { d -> PendingEditDecision.entries.firstOrNull { it.name == d } },
        decidedAt = decidedAt,
        decidedBy = decidedBy?.let { UserId(it) },
    )
}
