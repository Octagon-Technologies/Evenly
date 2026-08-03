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
 * which is a quarantined size offender (`ui/AGENTS.md`) — because undoing a guest's edit is a
 * self-contained "restore, re-version, re-derive" unit with no overlap with the live claim writes.
 *
 * [BillRepositoryImpl] owns the public contract; these two only do the work.
 */

/**
 * Undoing a web guest's change to the menu (spec §2.7, §3.9.1).
 *
 * There is no *apply* half here any more, and its absence is the design. A guest's edit applies
 * server-side, inside `apply_web_bill_edit`, because the edge function has a service key and no
 * `auth.uid()` and therefore cannot go through `merge_expense`. The app only ever learns about the
 * change on the next pull and offers to take it back.
 *
 * The undo itself stays **local-first**, like every other in-app money edit: it writes Room and reaches
 * the server through `merge_expense`, on the same causal rules as any split edit.
 */
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

    /**
     * Take a guest's change back. **Anyone on the bill may** (spec §2.7) — this method takes an actor
     * rather than checking one, because the undo is itself an attributed entry in the log and the log is
     * the tiebreak. Do not add a rights hierarchy on top.
     */
    suspend fun undo(editId: String, undoneBy: UserId): AppResult<Unit> {
        val row = pendingItemEditDao.getById(editId)
            ?: return validationErr("pendingEdit", AppError.Validation.Reason.Required)
        val edit = row.toDomain() ?: return validationErr("pendingEdit", AppError.Validation.Reason.Malformed)
        val expense = expenseDao.getById(row.expenseId)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()

        // First-undo-wins, decided by the conditional UPDATE rather than by a read-then-write: two people
        // tapping Undo at the same moment must produce one undo and one no-op. Losing here means the
        // change was already undone, which is the desired end state, so it is an Ok, not an error.
        if (pendingItemEditDao.markUndone(editId, undoneBy.value, now) == 0) return AppResult.Ok(Unit)

        restore(expense, edit, now)
        return AppResult.Ok(Unit)
    }

    /**
     * Put the line back the way it was, then re-derive.
     *
     * Every branch touches money, so it advances the causal `split_version` exactly as
     * [BillRepositoryImpl.editBill] does — an undo is a Zone-2 edit and has to lose to, or beat, a
     * concurrent split edit on the same causal rules as any other (see `data/AGENTS.md`). Missing the
     * bump would let `merge_expense` treat the undo as causally stale and silently drop it.
     */
    private suspend fun restore(
        expense: ExpenseEntity,
        edit: PendingBillEdit,
        now: Long,
    ) {
        val itemId = edit.itemId
        // Only reachable for a row written before `item_id` was backfilled on an ADD, of which there are
        // none. The log entry is still stamped UNDONE above; there is simply no line to put back.
        if (itemId != null) {
            // `getByExpense` excludes tombstones, and undoing a REMOVE has to find one, so this reads the
            // sync view instead.
            val all = expenseItemDao.allForSync().filter { it.expenseId == expense.id }
            val live = all.firstOrNull { it.id == itemId && it.deletedAt == null }

            when (edit.kind) {
                // Undoing an ADD takes the line off the bill, and the claims people made against it go
                // with it: a claim on a line that is not there any more would keep billing for it
                // (spec E22).
                PendingEditKind.ADD -> if (live != null) {
                    expenseItemDao.softDeleteByIds(listOf(itemId), now)
                    itemClaimDao.softDeleteByItems(listOf(itemId), now)
                    itemShareDao.softDeleteByItems(listOf(itemId), now)
                }

                // Undoing a REMOVE puts the line back. The claims that removal killed are NOT revived
                // here: the app cannot tell them apart from claims their owners dropped at the same
                // moment, and the server's `undo_web_bill_edit` — which can, because it matches the
                // removal's exact `deleted_at` stamp — is the path a guest's undo takes. A payer undoing
                // a removal in-app restores the line and leaves the claiming to the table, which is the
                // safe direction to be wrong in: nobody is billed for something they did not re-claim.
                PendingEditKind.REMOVE -> {
                    val tombstoned = all.firstOrNull { it.id == itemId } ?: return
                    expenseItemDao.upsert(
                        tombstoned.copy(
                            deletedAt = null,
                            label = edit.previousLabel ?: tombstoned.label,
                            quantity = (edit.previousQuantity ?: tombstoned.quantity).coerceAtLeast(1),
                            lineTotalSubunits = edit.previousLineTotalOrDerived,
                            unitPriceSubunits = perUnitSubunits(
                                edit.previousLineTotalOrDerived,
                                (edit.previousQuantity ?: tombstoned.quantity).coerceAtLeast(1),
                            ),
                            updatedAt = now,
                            rowVersion = tombstoned.rowVersion + 1,
                        ),
                    )
                }

                PendingEditKind.RELABEL -> if (live != null) {
                    expenseItemDao.upsert(
                        live.copy(
                            label = edit.previousLabel?.trim().orEmpty().ifEmpty { live.label },
                            updatedAt = now,
                            rowVersion = live.rowVersion + 1,
                        ),
                    )
                }

                // Both restore the quantity AND the recorded line total, never per-unit x quantity: the
                // line total is the entered source of truth (`domain/AGENTS.md`) and per-unit is a
                // rounded view of it, so rebuilding a $10.00 line over 3 units gives back $9.99.
                PendingEditKind.REPRICE, PendingEditKind.REQUANTITY -> if (live != null) {
                    val quantity = (edit.previousQuantity ?: live.quantity).coerceAtLeast(1)
                    val lineTotal = edit.previousLineTotalOrDerived
                    expenseItemDao.upsert(
                        live.copy(
                            quantity = quantity,
                            lineTotalSubunits = lineTotal,
                            unitPriceSubunits = perUnitSubunits(lineTotal, quantity),
                            updatedAt = now,
                            rowVersion = live.rowVersion + 1,
                        ),
                    )
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

/** Row → domain. Returns null for a `kind` this build does not know, so a newer web client making a
 *  change this one cannot describe is skipped rather than crashing the payer's screen. */
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
        previousLineTotalSubunits = previousLineTotalSubunits,
        decision = decision?.let { d -> PendingEditDecision.entries.firstOrNull { it.name == d } },
        decidedAt = decidedAt,
        decidedBy = decidedBy?.let { UserId(it) },
    )
}
