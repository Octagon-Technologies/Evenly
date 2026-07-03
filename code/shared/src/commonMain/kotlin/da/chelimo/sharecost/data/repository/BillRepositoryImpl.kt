package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.ExpenseStatus
import da.chelimo.sharecost.data.db.dao.BillParticipantDao
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.ExpenseItemDao
import da.chelimo.sharecost.data.db.dao.HistoryEventDao
import da.chelimo.sharecost.data.db.dao.ItemClaimDao
import da.chelimo.sharecost.data.db.dao.ItemShareDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.entity.BillParticipantEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ExpenseItemEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.ItemClaimEntity
import da.chelimo.sharecost.data.db.entity.ItemShareEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.domain.activity.HistoryEventType
import da.chelimo.sharecost.domain.expense.BillClaimView
import da.chelimo.sharecost.domain.expense.BillExtrasInput
import da.chelimo.sharecost.domain.expense.BillItem
import da.chelimo.sharecost.domain.expense.BillItemView
import da.chelimo.sharecost.domain.expense.IndividualClaim
import da.chelimo.sharecost.domain.expense.BillExtras
import da.chelimo.sharecost.domain.expense.BillView
import da.chelimo.sharecost.domain.expense.EditBill
import da.chelimo.sharecost.domain.expense.BillParticipantView
import da.chelimo.sharecost.domain.expense.BillShareView
import da.chelimo.sharecost.domain.expense.ItemStatus
import da.chelimo.sharecost.domain.expense.NewBill
import da.chelimo.sharecost.domain.expense.SharedMember
import da.chelimo.sharecost.domain.expense.SPLIT_MODE_ITEMIZED
import da.chelimo.sharecost.domain.expense.UnresolvedBill
import da.chelimo.sharecost.domain.expense.TipSplitMode
import da.chelimo.sharecost.domain.expense.perUnitSubunits
import da.chelimo.sharecost.domain.expense.splitBill
import da.chelimo.sharecost.domain.repository.BillRepository
import da.chelimo.sharecost.newId
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
) : BillRepository {

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
                id = newId(),
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
        val updated = existing.copy(
            title = input.title.trim(),
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
                    id = newId(),
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
                    id = newId(),
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
                    id = newId(),
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
                    id = newId(),
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
                    id = newId(),
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
                    val result = splitBill(
                        its.map { BillItem(it.id, it.lineTotalSubunits, it.quantity) },
                        claimsByExpense[e.id].orEmpty().map { IndividualClaim(it.itemId, UserId(it.userId), it.quantity) },
                        sharesByExpense[e.id].orEmpty().map { SharedMember(it.itemId, UserId(it.userId)) },
                        e.toExtras().toEngine(),
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

    /**
     * Re-derive the bill's shares from its current items + claims + extras and write them with
     * deterministic ids. A participant who drops out of every claim is tombstoned. Never touches the
     * expense row, so a claim change doesn't mark the expense dirty (claims sync on their own).
     */
    private suspend fun materializeShares(expense: ExpenseEntity, now: Long) {
        val items = expenseItemDao.getByExpense(expense.id)
        val claims = itemClaimDao.getByExpense(expense.id)
        val sharedMembers = itemShareDao.getByExpense(expense.id)
        val owed = splitBill(
            items.map { BillItem(it.id, it.lineTotalSubunits, it.quantity) },
            claims.map { IndividualClaim(it.itemId, UserId(it.userId), it.quantity) },
            sharedMembers.map { SharedMember(it.itemId, UserId(it.userId)) },
            expense.toExtras().toEngine(),
        ).owedByUser
        val owedUsers = owed.keys.mapTo(HashSet()) { it.value }
        val existing = shareDao.getByExpense(expense.id).associateBy { it.userId }

        val shares = owed.map { (user, amount) ->
            val current = existing[user.value]
            current?.copy(shareOwedSubunits = amount, updatedAt = now, rowVersion = current.rowVersion + 1)
                ?: ShareEntity(
                    id = "${expense.id}__${user.value}",
                    expenseId = expense.id,
                    userId = user.value,
                    shareOwedSubunits = amount,
                    createdAt = now,
                    updatedAt = now,
                )
        }
        val removed = existing.values.filter { it.userId !in owedUsers }.map { it.id }
        if (removed.isNotEmpty()) shareDao.softDeleteByIds(removed, now)
        if (shares.isNotEmpty()) shareDao.upsertAll(shares)
    }

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
private fun ItemShareEntity.toView() = BillShareView(id, itemId, UserId(userId), UserId(addedBy))

private fun ExpenseEntity.toExtras() = BillExtrasInput(
    taxSubunits = taxSubunits,
    gratuitySubunits = gratuitySubunits,
    tipSubunits = tipSubunits,
    tipSplitMode = TipSplitMode.entries.firstOrNull { it.name == tipSplitMode } ?: TipSplitMode.EVEN,
    discountSubunits = discountSubunits,
)

private fun BillExtrasInput.toEngine() = BillExtras(
    taxSubunits = taxSubunits,
    gratuitySubunits = gratuitySubunits,
    tipSubunits = tipSubunits,
    tipSplitMode = tipSplitMode,
    discountSubunits = discountSubunits,
)

private fun List<BillItemView>.toBillItems() = map { BillItem(it.id, it.lineTotalSubunits, it.quantity) }
private fun List<BillClaimView>.toIndividualClaims() = map { IndividualClaim(it.itemId, it.userId, it.quantity) }
private fun List<BillShareView>.toSharedMembers() = map { SharedMember(it.itemId, it.userId) }
