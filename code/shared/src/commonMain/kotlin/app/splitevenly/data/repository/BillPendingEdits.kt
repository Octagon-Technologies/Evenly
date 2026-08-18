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
import app.splitevenly.domain.expense.PendingBillEdit
import app.splitevenly.domain.expense.PendingEditDecision
import app.splitevenly.domain.expense.PendingEditKind
import app.splitevenly.domain.expense.billTotalProblem
import app.splitevenly.domain.expense.billTotalSubunits
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
    suspend fun undo(
        editId: String,
        undoneBy: UserId,
    ): AppResult<Unit> {
        val row =
            pendingItemEditDao.getById(editId)
                ?: return validationErr("pendingEdit", AppError.Validation.Reason.Required)
        val edit = row.toDomain() ?: return validationErr("pendingEdit", AppError.Validation.Reason.Malformed)
        val expense = expenseDao.getById(row.expenseId)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()

        // Work out the whole restore, and reject it, BEFORE anything is written. The stamp is
        // first-undo-wins, so it is the one resource an undo can spend and not get back.
        val plan = plan(expense, edit, undoneBy, now)
        if (plan == null) {
            // The only refusal: putting the line back would leave a bill nobody can be billed for.
            return validationErr("discount", AppError.Validation.Reason.OutOfRange)
        }

        // Claim the undo and put the line back together, or neither (R3). Losing the conditional UPDATE
        // means the change was already undone by someone else, which is the desired end state, so it is
        // an Ok rather than an error, and this device writes nothing on top of the winner's restore.
        val applied =
            pendingItemEditDao.undoAndRestore(
                editId = editId,
                undoneBy = undoneBy.value,
                ts = now,
                restoredItem = plan.restoredItem,
                droppedItemIds = plan.droppedItemIds,
                expense = plan.expense,
            )
        // Shares are a self-healing materialization, so they re-derive outside the transaction — the same
        // division `ItemShareDao.setServings` makes.
        if (applied && plan.expense != null) materializer.materialize(plan.expense, now)
        return AppResult.Ok(Unit)
    }

    /** The rows an undo will write. [expense] is null when there is no line to put back and so no total to move. */
    private data class RestorePlan(
        val restoredItem: ExpenseItemEntity? = null,
        val droppedItemIds: List<String> = emptyList(),
        val expense: ExpenseEntity? = null,
    )

    /**
     * Work out what putting the line back means, or **null** when the result would not be a legal bill.
     *
     * The re-versioned expense advances the causal `split_version` exactly as
     * [BillRepositoryImpl.editBill] does — an undo is a Zone-2 edit and has to lose to, or beat, a
     * concurrent split edit on the same causal rules as any other (see `data/AGENTS.md`). Missing the bump
     * would let `merge_expense` treat the undo as causally stale and silently drop it. It is stamped with
     * [undoneBy], not with whoever last edited the split: `split_updated_by` is what the one-sided
     * superseded notice reads to decide whose change lost, so carrying the previous author forward names
     * the wrong person (R15).
     */
    // Guard clauses, each naming a different "there is nothing to write": a change with no line, a line
    // that is not there, and a restore that would not leave a legal bill. Nesting them reads worse.
    @Suppress("ReturnCount")
    private suspend fun plan(
        expense: ExpenseEntity,
        edit: PendingBillEdit,
        undoneBy: UserId,
        now: Long,
    ): RestorePlan? {
        val itemId = edit.itemId
        // Only reachable for a row written before `item_id` was backfilled on an ADD, of which there are
        // none. The log entry still gets stamped UNDONE; there is simply no line to put back.
        if (itemId == null) return RestorePlan()

        // `getByExpense` excludes tombstones, and undoing a REMOVE has to find one, so this reads the
        // sync view instead. A null result is "there is no such line": stamp it and move nothing.
        val all = expenseItemDao.allForSync().filter { it.expenseId == expense.id }
        val (restored, dropped) = restoredLine(all, edit, itemId, now) ?: return RestorePlan()

        val extras = expense.toExtras()
        val before = expenseItemDao.getByExpense(expense.id).filter { it.deletedAt == null }
        val after = (before.filterNot { it.id in dropped || it.id == restored?.id } + listOfNotNull(restored))
        // The same rule create and edit enforce, now enforced here too (R5). A discount entered while a
        // guest's inflated line was live can exceed the bill without it, and the undo would then store a
        // negative expense whose shares fall out of every `remaining > 0` filter: the bill leaves the
        // balances rather than failing. Only a *newly* illegal total is refused, so a bill that arrived
        // broken (older client, the web guest path) can still have its cosmetic changes taken back.
        val wasLegal = billTotalProblem(before.map { it.lineTotalSubunits }, extras) == null
        if (wasLegal && billTotalProblem(after.map { it.lineTotalSubunits }, extras) != null) return null

        return RestorePlan(
            restoredItem = restored,
            droppedItemIds = dropped,
            expense =
                expense.copy(
                    amountSubunits = billTotalSubunits(after.map { it.lineTotalSubunits }, extras),
                    updatedAt = now,
                    rowVersion = expense.rowVersion + 1,
                    splitVersion = expense.splitVersion + 1,
                    splitUpdatedBy = undoneBy.value,
                ),
        )
    }

    /**
     * The line row this undo writes, and the line ids it takes off the bill. Null when the change refers
     * to a line that is not in [all] at all, which is a stamp with nothing to restore.
     */
    private fun restoredLine(
        all: List<ExpenseItemEntity>,
        edit: PendingBillEdit,
        itemId: String,
        now: Long,
    ): Pair<ExpenseItemEntity?, List<String>>? {
        val live = all.firstOrNull { it.id == itemId && it.deletedAt == null }
        return when (edit.kind) {
            // Undoing an ADD takes the line off the bill, and the claims people made against it go with
            // it: a claim on a line that is not there any more would keep billing for it (spec E22).
            PendingEditKind.ADD -> {
                null to if (live != null) listOf(itemId) else emptyList()
            }

            // Undoing a REMOVE puts the line back. The claims that removal killed are NOT revived here:
            // the app cannot tell them apart from claims their owners dropped at the same moment, and the
            // server's `undo_web_bill_edit` — which can, because it matches the removal's exact
            // `deleted_at` stamp — is the path a guest's undo takes. A payer undoing a removal in-app
            // restores the line and leaves the claiming to the table, which is the safe direction to be
            // wrong in: nobody is billed for something they did not re-claim.
            PendingEditKind.REMOVE -> {
                val tombstoned = all.firstOrNull { it.id == itemId } ?: return null
                val quantity = (edit.previousQuantity ?: tombstoned.quantity).coerceAtLeast(1)
                tombstoned.copy(
                    deletedAt = null,
                    label = edit.previousLabel ?: tombstoned.label,
                    quantity = quantity,
                    lineTotalSubunits = edit.previousLineTotalOrDerived,
                    unitPriceSubunits = perUnitSubunits(edit.previousLineTotalOrDerived, quantity),
                    updatedAt = now,
                    rowVersion = tombstoned.rowVersion + 1,
                ) to emptyList()
            }

            PendingEditKind.RELABEL -> {
                live?.copy(
                    label =
                        edit.previousLabel
                            ?.trim()
                            .orEmpty()
                            .ifEmpty { live.label },
                    updatedAt = now,
                    rowVersion = live.rowVersion + 1,
                ) to emptyList()
            }

            // Both restore the quantity AND the recorded line total, never per-unit x quantity: the line
            // total is the entered source of truth (`domain/AGENTS.md`) and per-unit is a rounded view of
            // it, so rebuilding a $10.00 line over 3 units gives back $9.99.
            PendingEditKind.REPRICE, PendingEditKind.REQUANTITY -> {
                val quantity = (edit.previousQuantity ?: live?.quantity ?: 1).coerceAtLeast(1)
                val lineTotal = edit.previousLineTotalOrDerived
                live?.copy(
                    quantity = quantity,
                    lineTotalSubunits = lineTotal,
                    unitPriceSubunits = perUnitSubunits(lineTotal, quantity),
                    updatedAt = now,
                    rowVersion = live.rowVersion + 1,
                ) to emptyList()
            }
        }
    }

    private fun validationErr(
        field: String,
        reason: AppError.Validation.Reason,
    ): AppResult<Nothing> = AppError.Validation(mapOf(field to reason)).asErr()
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
    private fun shareId(
        itemId: String,
        userId: String,
        portionId: String,
    ) = "${itemId}__${userId}__$portionId"

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
            // A NEW portion the first time round, so nobody's existing claim is being rewritten and the
            // "claims are partitioned by user" invariant is never in play — no server RPC needed. A
            // deterministic id means running this twice converges on one slice instead of stacking two.
            val portionId = "${item.id}__remainder"
            val here = shares.filter { it.itemId == item.id && it.portionId == portionId }
            // Units already spoken for = solo claims + one count per distinct live portion (never per
            // member) — the same arithmetic join_item_portion does server-side. **Excluding this line's
            // own remainder slice**, which is the thing being recomputed: counting it made a second call
            // with a different member set read zero left, write nothing, and still report success, so the
            // payer could not change their mind through the control that offers it (R10).
            val soloUnits = claims.filter { it.itemId == item.id }.sumOf { it.quantity }
            val portionUnits =
                shares
                    .filter { it.itemId == item.id && it.portionId != null && it.portionId != portionId }
                    .groupBy { it.portionId }
                    .values
                    .sumOf { rows -> rows.first().quantity }
            val left = item.quantity - soloUnits - portionUnits
            if (left <= 0) continue
            // Drop the people this slice used to hold who are not in the new set — the step `setPortion`
            // already has and this did not, which is why it could only ever add.
            val dropped = here.filter { it.userId !in targets }.map { it.id }
            if (dropped.isNotEmpty()) itemShareDao.softDeleteByIds(dropped, now)
            val byUser = here.associateBy { it.userId }
            val rows =
                targets.map { uid ->
                    // Keep a surviving member's row identity and version rather than minting a fresh row over
                    // it, so a re-assignment does not reset `row_version` under the sync push.
                    byUser[uid]?.copy(
                        quantity = left,
                        addedBy = addedBy.value,
                        updatedAt = now,
                        rowVersion = byUser.getValue(uid).rowVersion + 1,
                    ) ?: ItemShareEntity(
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

    private fun validationErr(
        field: String,
        reason: AppError.Validation.Reason,
    ): AppResult<Nothing> = AppError.Validation(mapOf(field to reason)).asErr()
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
