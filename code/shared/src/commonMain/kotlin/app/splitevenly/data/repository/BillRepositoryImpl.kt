package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.error.asOk
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.ExpenseStatus
import app.splitevenly.data.db.dao.BillParticipantDao
import app.splitevenly.data.db.dao.ExpenseDao
import app.splitevenly.data.db.dao.ExpenseItemDao
import app.splitevenly.data.db.dao.HistoryEventDao
import app.splitevenly.data.db.dao.ItemClaimDao
import app.splitevenly.data.db.dao.ItemShareDao
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.expense.BillClaimView
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.BillItem
import app.splitevenly.domain.expense.BillItemView
import app.splitevenly.domain.expense.IndividualClaim
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.BillParticipantView
import app.splitevenly.domain.expense.BillShareView
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.SharedMember
import app.splitevenly.domain.expense.SharedPortion
import app.splitevenly.domain.expense.SPLIT_MODE_ITEMIZED
import app.splitevenly.domain.expense.UnresolvedBill
import app.splitevenly.domain.expense.perUnitSubunits
import app.splitevenly.domain.expense.splitBill
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.newId
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [BillRepository] (the "Split the bill" itemized flow).
 *
 * The design centre of gravity is that an itemized expense's `shares` are a **derived materialization**
 * of its items + claims + extras — not user input. [materializeShares] runs [splitBill] and writes the
 * result with **deterministic share ids** (`"<expenseId>__<userId>"`), so (a) every device computes the
 * same rows from the same synced claims and converges without a CAS, and (b) re-deriving on an edit
 * updates the *same* row id, keeping settlement allocations linked (editing a price never wipes a
 * payment). Claims are partitioned by user, so the live multi-device claim layer is conflict-free.
 *
 * Unlike a normal expense, a bill does **not** enforce `Σ shares == amount` while claiming: the amount
 * is the full bill total (items + extras), but shares only cover what's been claimed so far. The Finish
 * flow assigns any leftover before the bill is treated as settled.
 */
@OptIn(ExperimentalTime::class)
class BillRepositoryImpl(
    private val expenseDao: ExpenseDao,
    private val expenseItemDao: ExpenseItemDao,
    private val itemClaimDao: ItemClaimDao,
    private val itemShareDao: ItemShareDao,
    private val billParticipantDao: BillParticipantDao,
    private val shareDao: ShareDao,
    private val clock: Clock = Clock.System,
    // Optional activity log (F5). Null in unit tests => no history rows; production DI wires it.
    private val historyEventDao: HistoryEventDao? = null,
    // Analytics: null in unit tests; production DI passes AndroidAnalytics.
    private val analytics: EvAnalytics? = null,
) : BillRepository {

    // The bill's shares are a derived materialization of its items + claims + extras. The same derivation
    // runs from SyncEngine on pull (P0 #3), so it lives in a shared collaborator, not inline here.
    private val materializer = BillMaterializer(expenseDao, expenseItemDao, itemClaimDao, itemShareDao, shareDao)

    // Deterministic ids for USER-PARTITIONED rows (#5). Two devices assigning the same person to the same
    // slot used to mint two random PKs; the loser's upsert then violated the active unique index (23505)
    // and aborted the ENTIRE push (every table after it too), wedging sync permanently. Keying the id on
    // the slot means concurrent writers converge on one PK and upsert instead of colliding. A re-add also
    // lands back on its own tombstone (resurrects it) instead of leaving a duplicate. Mirrors the `shares`
    // "<expenseId>__<userId>" convention. NOTE: existing random-id rows are found by (item,user[,portion])
    // and updated in place, so only the CREATE path adopts these ids — old rows keep working.
    private fun claimId(itemId: String, userId: String) = "${itemId}__$userId"
    private fun participantId(expenseId: String, userId: String) = "${expenseId}__$userId"
    private fun shareId(itemId: String, userId: String, portionId: String?) = "${itemId}__${userId}__${portionId ?: "leftover"}"

    override fun observeBill(expenseId: ExpenseId): Flow<BillView?> =
        combine(
            expenseDao.observeById(expenseId.value),
            expenseItemDao.observeByExpense(expenseId.value),
            itemClaimDao.observeByExpense(expenseId.value),
            itemShareDao.observeByExpense(expenseId.value),
            billParticipantDao.observeByExpense(expenseId.value),
        ) { expense, items, claims, shares, participants ->
            expense ?: return@combine null
            val itemViews = items.map { it.toView() }
            val claimViews = claims.map { it.toView() }
            val shareViews = shares.map { it.toView() }
            val extras = expense.toExtras()
            val result = splitBill(
                itemViews.toBillItems(),
                claimViews.toIndividualClaims(),
                shareViews.toSharedMembers(),
                extras.toEngine(),
                participants = participants.map { UserId(it.userId) },
                sharedPortions = shareViews.toSharedPortions(),
            )
            BillView(
                expense = expense.toDomain(),
                items = itemViews,
                claims = claimViews,
                shares = shareViews,
                participants = participants.map { BillParticipantView(UserId(it.userId), it.doneAt) },
                extras = extras,
                tabByUser = result.owedByUser,
                tabBreakdownByUser = result.breakdownByUser,
                reconcile = result.items,
                perItemByUser = result.perItemByUser,
            )
        }

    override suspend fun createBill(input: NewBill): AppResult<ExpenseId> {
        validate(input.title, input.items.map { it.quantity to it.lineTotalSubunits })?.let { return it }

        val now = clock.nowEpochMillis()
        val expenseId = newId()
        val amount = total(input.items.map { it.quantity to it.lineTotalSubunits }, input.extras)
        val expense = ExpenseEntity(
            id = expenseId,
            groupId = input.groupId.value,
            title = input.title.trim(),
            amountSubunits = amount,
            currency = input.currency,
            expenseDate = input.expenseDate,
            payerUserId = input.payerUserId?.value,
            payerOutsideName = input.payerOutsideName,
            splitMode = SPLIT_MODE_ITEMIZED,
            taxSubunits = input.extras.taxSubunits,
            tipSubunits = input.extras.tipSubunits,
            tipSplitMode = input.extras.tipSplitMode.name,
            gratuitySubunits = input.extras.gratuitySubunits,
            discountSubunits = input.extras.discountSubunits,
            categoryId = input.categoryId,
            status = ExpenseStatus.ACTIVE,
            createdBy = input.createdBy.value,
            createdAt = now,
            updatedAt = now,
            // Track F: a fresh bill stamps every Zone-1 field at `now` and starts the split at gen 1.
            titleUpdatedAt = now,
            notesUpdatedAt = now,
            categoryUpdatedAt = now,
            dateUpdatedAt = now,
            splitVersion = 1,
            splitUpdatedBy = input.createdBy.value,
        )
        val items = input.items.mapIndexed { index, item ->
            ExpenseItemEntity(
                id = newId(),
                expenseId = expenseId,
                groupId = input.groupId.value,
                label = item.label.trim(),
                quantity = item.quantity,
                unitPriceSubunits = perUnitSubunits(item.lineTotalSubunits, item.quantity),
                lineTotalSubunits = item.lineTotalSubunits,
                sortOrder = index,
                createdAt = now,
                updatedAt = now,
            )
        }
        // Participants: whoever the creator picked, plus the creator and payer (they're always on the bill).
        val participantIds = (input.participantUserIds.map { it.value } +
            input.createdBy.value + listOfNotNull(input.payerUserId?.value)).distinct()
        val participants = participantIds.map { uid ->
            BillParticipantEntity(
                id = participantId(expenseId, uid),
                expenseId = expenseId,
                groupId = input.groupId.value,
                userId = uid,
                createdAt = now,
                updatedAt = now,
            )
        }
        expenseDao.upsert(expense)
        expenseItemDao.upsertAll(items)
        if (participants.isNotEmpty()) billParticipantDao.upsertAll(participants)
        materializeShares(expense, now) // no claims yet → no shares; tab fills in as people claim
        recordHistory(expenseId, input.groupId.value, HistoryEventType.CREATED, input.createdBy.value, now)
        analytics?.capture(
            AnalyticsEvents.BILL_CREATED,
            mapOf("item_count" to items.size, "group_id" to input.groupId.value),
        )
        return ExpenseId(expenseId).asOk()
    }

    override suspend fun editBill(expenseId: ExpenseId, input: EditBill): AppResult<Unit> {
        validate(input.title, input.items.map { it.quantity to it.lineTotalSubunits })?.let { return it }

        val existing = expenseDao.getById(expenseId.value)
        if (existing == null || existing.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        val existingItems = expenseItemDao.getByExpense(expenseId.value)
        val byId = existingItems.associateBy { it.id }
        val desiredIds = input.items.mapNotNullTo(HashSet()) { it.id }

        // Identity-preserving item merge: a surviving line keeps its id, so claims pointing at it stay
        // valid; a removed line (and every claim on it) is tombstoned.
        val upserts = input.items.mapIndexed { index, item ->
            val current = item.id?.let { byId[it] }
            current?.copy(
                label = item.label.trim(),
                quantity = item.quantity,
                unitPriceSubunits = perUnitSubunits(item.lineTotalSubunits, item.quantity),
                lineTotalSubunits = item.lineTotalSubunits,
                sortOrder = index,
                updatedAt = now,
                rowVersion = current.rowVersion + 1,
            ) ?: ExpenseItemEntity(
                id = newId(),
                expenseId = expenseId.value,
                groupId = existing.groupId,
                label = item.label.trim(),
                quantity = item.quantity,
                unitPriceSubunits = perUnitSubunits(item.lineTotalSubunits, item.quantity),
                lineTotalSubunits = item.lineTotalSubunits,
                sortOrder = index,
                createdAt = now,
                updatedAt = now,
            )
        }
        val removedItemIds = existingItems.filter { it.id !in desiredIds }.map { it.id }

        val amount = total(input.items.map { it.quantity to it.lineTotalSubunits }, input.extras)
        // Track F Zone 2: the bill's money value is the split. Advance the causal split_version whenever
        // anything money-relevant changed — the total (covers price/qty/extra/count changes), the payer,
        // the item set (add/remove/swap), or who's on the bill. Over-bumping is harmless (a re-apply);
        // under-bumping would let the merge drop a real split edit, so we err toward bumping.
        val newTitle = input.title.trim()
        val amountChanged = amount != existing.amountSubunits
        val payerChanged = input.payerUserId?.value != existing.payerUserId ||
            input.payerOutsideName != existing.payerOutsideName
        // Content diff, not just a count/add/remove heuristic: two offsetting line edits (line A +$5,
        // line B −$5) keep the count AND the bill total unchanged, so a size/amount check misses them and
        // the merge silently reverts the split (P0 #2). Compare each surviving line's (quantity, lineTotal).
        val existingActiveItems = existingItems.filter { it.deletedAt == null }
        val existingContent = existingActiveItems.associate { it.id to (it.quantity to it.lineTotalSubunits) }
        val itemsChanged = removedItemIds.isNotEmpty() ||
            input.items.any { it.id == null } ||
            input.items.size != existingActiveItems.size ||
            input.items.any { it.id != null && existingContent[it.id] != (it.quantity to it.lineTotalSubunits) }
        // Extras reshape the split even when they net to the same total (tax↔tip swap; tax +$5 / discount
        // +$5). Tax/gratuity split proportionally, tip evenly, discount negative-proportionally, so any
        // extras change is a split change regardless of the bill total.
        val extrasChanged = input.extras.taxSubunits != existing.taxSubunits ||
            input.extras.gratuitySubunits != existing.gratuitySubunits ||
            input.extras.tipSubunits != existing.tipSubunits ||
            input.extras.tipSplitMode.name != existing.tipSplitMode ||
            input.extras.discountSubunits != existing.discountSubunits
        val participantsChanged = input.participantUserIds.isNotEmpty() && run {
            val desired = input.participantUserIds.mapTo(HashSet()) { it.value }
            desired != billParticipantDao.getByExpense(expenseId.value)
                .filter { it.deletedAt == null }.mapTo(HashSet()) { it.userId }
        }
        val splitChanged = amountChanged || payerChanged || itemsChanged || extrasChanged || participantsChanged
        val updated = existing.copy(
            title = newTitle,
            amountSubunits = amount,
            expenseDate = input.expenseDate,
            payerUserId = input.payerUserId?.value,
            payerOutsideName = input.payerOutsideName,
            taxSubunits = input.extras.taxSubunits,
            tipSubunits = input.extras.tipSubunits,
            tipSplitMode = input.extras.tipSplitMode.name,
            gratuitySubunits = input.extras.gratuitySubunits,
            discountSubunits = input.extras.discountSubunits,
            status = ExpenseStatus.ACTIVE,
            updatedAt = now,
            rowVersion = existing.rowVersion + 1,
            titleUpdatedAt = if (newTitle != existing.title) now else existing.titleUpdatedAt,
            dateUpdatedAt = if (input.expenseDate != existing.expenseDate) now else existing.dateUpdatedAt,
            splitVersion = if (splitChanged) existing.splitVersion + 1 else existing.splitVersion,
            splitUpdatedBy = if (splitChanged) input.editedBy?.value else existing.splitUpdatedBy,
        )
        expenseDao.upsert(updated)
        expenseItemDao.upsertAll(upserts)
        if (removedItemIds.isNotEmpty()) {
            expenseItemDao.softDeleteByIds(removedItemIds, now)
            itemClaimDao.softDeleteByItems(removedItemIds, now)
            itemShareDao.softDeleteByItems(removedItemIds, now)
        }
        // Reconcile participants (only when the caller manages them — an empty set never wipes silently):
        // add the newly-selected, soft-delete the deselected, and preserve surviving rows' done stamp.
        if (input.participantUserIds.isNotEmpty()) {
            val desired = input.participantUserIds.mapTo(HashSet()) { it.value }
            val existingParts = billParticipantDao.getByExpense(expenseId.value)
            val existingUsers = existingParts.mapTo(HashSet()) { it.userId }
            val added = desired.filter { it !in existingUsers }.map { uid ->
                BillParticipantEntity(
                    id = participantId(expenseId.value, uid),
                    expenseId = expenseId.value,
                    groupId = existing.groupId,
                    userId = uid,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            if (added.isNotEmpty()) billParticipantDao.upsertAll(added)
            val removedParts = existingParts.filter { it.userId !in desired }.map { it.id }
            if (removedParts.isNotEmpty()) billParticipantDao.softDeleteByIds(removedParts, now)
        }
        materializeShares(updated, now)
        recordHistory(expenseId.value, existing.groupId, HistoryEventType.EDITED, input.editedBy?.value, now)
        return AppResult.Ok(Unit)
    }

    override suspend fun setClaim(expenseId: ExpenseId, itemId: String, userId: UserId, quantity: Int): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        val existing = itemClaimDao.getActiveClaim(itemId, userId.value)
        when {
            quantity <= 0 -> existing?.let { itemClaimDao.softDeleteByIds(listOf(it.id), now) }
            existing != null -> itemClaimDao.upsert(
                existing.copy(quantity = quantity, updatedAt = now, rowVersion = existing.rowVersion + 1),
            )
            else -> itemClaimDao.upsert(
                ItemClaimEntity(
                    id = claimId(itemId, userId.value),
                    itemId = itemId,
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = userId.value,
                    quantity = quantity,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // Solo-claiming a single unit takes you out of its share (the two are mutually exclusive there).
        if (quantity > 0 && isSingleUnit(expenseId, itemId)) {
            itemShareDao.getActiveShare(itemId, userId.value)?.let { itemShareDao.softDeleteByIds(listOf(it.id), now) }
        }
        materializeShares(expense, now)
        return AppResult.Ok(Unit)
    }

    override suspend fun setShareMember(
        expenseId: ExpenseId,
        itemId: String,
        memberUserId: UserId,
        addedBy: UserId,
        inShare: Boolean,
    ): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        val existing = itemShareDao.getActiveShare(itemId, memberUserId.value)
        when {
            !inShare -> existing?.let { itemShareDao.softDeleteByIds(listOf(it.id), now) }
            existing == null -> itemShareDao.upsert(
                ItemShareEntity(
                    id = shareId(itemId, memberUserId.value, portionId = null),
                    itemId = itemId,
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = memberUserId.value,
                    addedBy = addedBy.value,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // already a member → no-op (the set is idempotent; auto-union means re-adding is harmless)
        }
        // A single unit can't be both solo-claimed and split: joining its share drops your individual claim
        // (else it lingers and would resurrect as a solo claim if you later leave the share). Multi-unit
        // lines legitimately mix individual + leftover-share, so leave those alone.
        if (inShare && isSingleUnit(expenseId, itemId)) {
            itemClaimDao.getActiveClaim(itemId, memberUserId.value)?.let { itemClaimDao.softDeleteByIds(listOf(it.id), now) }
        }
        materializeShares(expense, now)
        return AppResult.Ok(Unit)
    }

    override suspend fun setPortion(
        expenseId: ExpenseId,
        itemId: String,
        portionId: String,
        memberIds: List<UserId>,
        quantity: Int,
        addedBy: UserId,
    ): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        val targets = memberIds.mapTo(LinkedHashSet()) { it.value }
        val activeForItem = itemShareDao.getByExpense(expenseId.value).filter { it.itemId == itemId && it.deletedAt == null }

        // Empty set or non-positive quantity removes the whole slice.
        if (targets.isEmpty() || quantity <= 0) {
            val ids = activeForItem.filter { it.portionId == portionId }.map { it.id }
            if (ids.isNotEmpty()) itemShareDao.softDeleteByIds(ids, now)
            materializeShares(expense, now)
            return AppResult.Ok(Unit)
        }
        // Drop this slice's former members who aren't in the target set any more.
        val dropped = activeForItem.filter { it.portionId == portionId && it.userId !in targets }.map { it.id }
        if (dropped.isNotEmpty()) itemShareDao.softDeleteByIds(dropped, now)
        for (uid in targets) {
            // A person CAN be in more than one portion of the same line now (per-serving assignment: the
            // same person may be solo on one serving and shared with someone else on another) — so unlike
            // the old single-portion-builder invariant, we only touch this portionId's row, never siblings.
            val here = activeForItem.firstOrNull { it.userId == uid && it.portionId == portionId }
            if (here != null) {
                if (here.quantity != quantity) {
                    itemShareDao.upsert(here.copy(quantity = quantity, updatedAt = now, rowVersion = here.rowVersion + 1))
                }
            } else {
                itemShareDao.upsert(
                    ItemShareEntity(
                        id = shareId(itemId, uid, portionId),
                        itemId = itemId,
                        expenseId = expenseId.value,
                        groupId = expense.groupId,
                        userId = uid,
                        portionId = portionId,
                        quantity = quantity,
                        addedBy = addedBy.value,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        materializeShares(expense, now)
        return AppResult.Ok(Unit)
    }

    override suspend fun setServings(
        expenseId: ExpenseId,
        itemId: String,
        servings: List<List<UserId>>,
        addedBy: UserId,
    ): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
        if (expense == null || expense.deletedAt != null) {
            return validationErr("expense", AppError.Validation.Reason.Required)
        }
        val now = clock.nowEpochMillis()
        // One quantity-1 portion per assigned serving slot; deterministic ids (#5) so re-slicing lands back
        // on the same rows and concurrent identical assignments converge instead of duplicating.
        val newPortions = servings.flatMapIndexed { index, memberIds ->
            if (memberIds.isEmpty()) return@flatMapIndexed emptyList()
            val portionId = "${itemId}__slot$index"
            memberIds.map { uid ->
                ItemShareEntity(
                    id = shareId(itemId, uid.value, portionId),
                    itemId = itemId,
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = uid.value,
                    portionId = portionId,
                    quantity = 1,
                    addedBy = addedBy.value,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        // Atomic teardown + rebuild of the item's claims + portions (#15). Shares re-derive after (they're a
        // self-healing materialization, not part of the atomic unit).
        itemShareDao.setServings(itemId, newPortions, now)
        materializeShares(expense, now)
        return AppResult.Ok(Unit)
    }

    /** True when the item is a single-unit line (where individual claim and share are mutually exclusive). */
    private suspend fun isSingleUnit(expenseId: ExpenseId, itemId: String): Boolean =
        expenseItemDao.getByExpense(expenseId.value).firstOrNull { it.id == itemId }?.quantity == 1

    override suspend fun setParticipant(expenseId: ExpenseId, userId: UserId, included: Boolean): AppResult<Unit> {
        val expense = expenseDao.getById(expenseId.value)
            ?: return validationErr("expense", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        val existing = billParticipantDao.getActive(expenseId.value, userId.value)
        when {
            !included -> existing?.let { billParticipantDao.softDeleteByIds(listOf(it.id), now) }
            existing == null -> billParticipantDao.upsert(
                BillParticipantEntity(
                    id = participantId(expenseId.value, userId.value),
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = userId.value,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return AppResult.Ok(Unit)
    }

    override suspend fun markDone(expenseId: ExpenseId, userId: UserId, done: Boolean): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        val existing = billParticipantDao.getActive(expenseId.value, userId.value)
        if (existing != null) {
            billParticipantDao.setDone(existing.id, if (done) now else null, now)
        } else if (done) {
            // Someone marking done who wasn't formally a participant (they still claimed) becomes one.
            val expense = expenseDao.getById(expenseId.value)
                ?: return validationErr("expense", AppError.Validation.Reason.Required)
            billParticipantDao.upsert(
                BillParticipantEntity(
                    id = participantId(expenseId.value, userId.value),
                    expenseId = expenseId.value,
                    groupId = expense.groupId,
                    userId = userId.value,
                    doneAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return AppResult.Ok(Unit)
    }

    override fun observeUnresolvedBills(groupId: GroupId, viewer: UserId?): Flow<List<UnresolvedBill>> =
        combine(
            expenseDao.observeByGroup(groupId.value),
            expenseItemDao.observeByGroup(groupId.value),
            itemClaimDao.observeByGroup(groupId.value),
            itemShareDao.observeByGroup(groupId.value),
            billParticipantDao.observeByGroup(groupId.value),
        ) { expenses, items, claims, shares, participants ->
            val itemsByExpense = items.groupBy { it.expenseId }
            val claimsByExpense = claims.groupBy { it.expenseId }
            val sharesByExpense = shares.groupBy { it.expenseId }
            val partsByExpense = participants.groupBy { it.expenseId }
            expenses.asSequence()
                .filter { it.splitMode == SPLIT_MODE_ITEMIZED }
                .mapNotNull { e ->
                    val its = itemsByExpense[e.id].orEmpty()
                    if (its.isEmpty()) return@mapNotNull null
                    val sharedRows = sharesByExpense[e.id].orEmpty()
                    val result = splitBill(
                        its.map { BillItem(it.id, it.lineTotalSubunits, it.quantity) },
                        claimsByExpense[e.id].orEmpty().map { IndividualClaim(it.itemId, UserId(it.userId), it.quantity) },
                        sharedRows.toLegacyMembers(),
                        e.toExtras().toEngine(),
                        sharedPortions = sharedRows.toEnginePortions(),
                    )
                    val parts = partsByExpense[e.id].orEmpty()
                    val stillToClaim = parts.count { it.doneAt == null }
                    // Unresolved = a line still needs someone, or a participant hasn't marked done.
                    if (result.unclaimedCount == 0 && stillToClaim == 0) return@mapNotNull null
                    UnresolvedBill(
                        expenseId = ExpenseId(e.id),
                        title = e.title,
                        currency = e.currency,
                        amountSubunits = e.amountSubunits,
                        unclaimedCount = result.unclaimedCount,
                        participantCount = parts.size,
                        stillToClaimCount = stillToClaim,
                        youNeedToClaim = viewer != null && parts.any { it.userId == viewer.value && it.doneAt == null },
                    )
                }
                .toList()
        }

    /** Re-derive the bill's shares from its current items + claims + extras (see [BillMaterializer]). */
    private suspend fun materializeShares(expense: ExpenseEntity, now: Long) = materializer.materialize(expense, now)

    private suspend fun recordHistory(expenseId: String, groupId: String, type: HistoryEventType, actorUserId: String?, now: Long) {
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

    /** Title required; at least one line; every line a positive quantity and non-negative line total. */
    private fun validate(title: String, lines: List<Pair<Int, Long>>): AppResult<Nothing>? {
        val fieldErrors = buildMap {
            if (title.trim().isEmpty()) put("title", AppError.Validation.Reason.Required)
            when {
                lines.isEmpty() -> put("items", AppError.Validation.Reason.Required)
                lines.any { (qty, lineTotal) -> qty <= 0 || lineTotal < 0L } -> put("items", AppError.Validation.Reason.OutOfRange)
            }
        }
        return if (fieldErrors.isEmpty()) null else AppError.Validation(fieldErrors).asErr()
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()

    /** Bill total = Σ(line totals) + tax + gratuity + tip − discount. Each line's total is entered directly. */
    private fun total(lines: List<Pair<Int, Long>>, extras: BillExtrasInput): Long =
        lines.sumOf { (_, lineTotal) -> lineTotal } +
            extras.taxSubunits + extras.gratuitySubunits + extras.tipSubunits - extras.discountSubunits
}

private fun ExpenseItemEntity.toView() = BillItemView(id, label, quantity, lineTotalSubunits, sortOrder)
private fun ItemClaimEntity.toView() = BillClaimView(id, itemId, UserId(userId), quantity)
private fun ItemShareEntity.toView() = BillShareView(id, itemId, UserId(userId), UserId(addedBy), portionId, quantity)

// toExtras / toEngine / toLegacyMembers (List<ItemShareEntity>) / toEnginePortions (List<ItemShareEntity>)
// are shared with SyncEngine's pull-side re-derivation, so they live in BillMaterializer.kt (internal).

private fun List<BillItemView>.toBillItems() = map { BillItem(it.id, it.lineTotalSubunits, it.quantity) }
private fun List<BillClaimView>.toIndividualClaims() = map { IndividualClaim(it.itemId, it.userId, it.quantity) }

// A line's sharing splits two ways: legacy rows (no portion) feed the old single all-leftover set; portioned
// rows group by portion_id into explicit slices (quantity is denormalised, so take the first row's).
private fun List<BillShareView>.toSharedMembers() = filter { it.portionId == null }.map { SharedMember(it.itemId, it.userId) }
private fun List<BillShareView>.toSharedPortions(): List<SharedPortion> =
    filter { it.portionId != null }
        .groupBy { it.itemId to it.portionId!! }
        .map { (key, rows) -> SharedPortion(key.first, key.second, rows.first().quantity, rows.map { it.userId }) }
