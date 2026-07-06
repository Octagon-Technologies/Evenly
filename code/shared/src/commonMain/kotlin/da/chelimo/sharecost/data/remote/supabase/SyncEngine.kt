package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.CategoryEntity
import da.chelimo.sharecost.data.db.entity.CommentEntity
import da.chelimo.sharecost.data.db.entity.ConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEditConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ExpenseSyncStateEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.BillParticipantEntity
import da.chelimo.sharecost.data.db.entity.ExpenseItemEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.ItemClaimEntity
import da.chelimo.sharecost.data.db.entity.ItemShareEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import da.chelimo.sharecost.data.db.entity.RowSyncStateEntity
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.entity.SupersededNoticeEntity
import da.chelimo.sharecost.data.db.entity.UserEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local-first sync (04 §6) between Room and Supabase Postgrest. The schema mirrors 1:1 (snake_case
 * columns ↔ the entities' `@ColumnInfo` names, handled by the client's snake_case serializer), so the
 * Room `@Entity` rows double as the wire DTOs — no separate mapping layer.
 *
 * MVP strategy is last-write-wins on the whole row set: [pull] fetches the signed-in user's groups and
 * everything under them into Room; [push] upserts the device's rows back up. This is intentionally
 * simple — field-level merge / an outbox / realtime deltas are later refinements. RLS on the server
 * scopes every read to the caller, so [pull] never needs to over-filter.
 */
/**
 * Parameters for the `merge_expense` RPC (Track F zone-aware merge). The snake_case serializer maps
 * `pExpense` → `p_expense` etc.; the nested entities serialize to snake_case columns (the same wire
 * shape as a table upsert). `pBaseSplitVersion` is the client's last server-confirmed split version.
 */
@Serializable
private data class MergeExpenseParams(
    @SerialName("p_expense") val pExpense: ExpenseEntity,
    @SerialName("p_shares") val pShares: List<ShareEntity>,
    @SerialName("p_base_split_version") val pBaseSplitVersion: Long,
    @SerialName("p_actor") val pActor: String,
)

/**
 * Result of `merge_expense`. `status` is created / merged / superseded / deleted. The RPC always returns
 * the merged canonical [expense] (per-field-merged Zone 1 + resolved Zone-2 split) and its active
 * [shares], so the client adopts them directly — no re-pull. `superseded` additionally means the
 * device's split edit lost the causal guard (the server kept its advanced split; ours is logged) → we
 * adopt canonical and raise a one-sided notice.
 */
@Serializable
private data class MergeResult(
    val status: String,
    @SerialName("split_version") val splitVersion: Long? = null,
    val expense: ExpenseEntity? = null,
    val shares: List<ShareEntity> = emptyList(),
)

class SyncEngine(
    private val client: SupabaseClient,
    private val db: ShareCostDatabase,
) {

    /** Pull the signed-in user's data into Room (server → local). Best-effort: errors are returned. */
    suspend fun pull(userId: String): AppResult<Unit> = runCatchingSync {
        // 1. The user's memberships give the set of groups to hydrate.
        val myMemberships = client.from("members").select(Columns.ALL) {
            filter { eq("user_id", userId) }
        }.decodeList<MemberEntity>()
        val groupIds = myMemberships.map { it.groupId }.distinct()
        if (groupIds.isEmpty()) {
            db.memberDao().upsertAll(myMemberships)
            return@runCatchingSync
        }

        // 2. Everything under those groups.
        val groups = selectIn<GroupEntity>("groups", "id", groupIds)
        val members = selectIn<MemberEntity>("members", "group_id", groupIds)
        val expenses = selectIn<ExpenseEntity>("expenses", "group_id", groupIds)
        val expenseIds = expenses.map { it.id }
        val shares = if (expenseIds.isEmpty()) emptyList() else selectIn<ShareEntity>("shares", "expense_id", expenseIds)
        val settlements = selectIn<SettlementEntity>("settlements", "group_id", groupIds)
        // Allocations are synced ground truth — the client derives each share's remaining from them.
        val allocations = selectIn<SettlementAllocationEntity>("settlement_allocations", "group_id", groupIds)
        val categories = selectIn<CategoryEntity>("categories", "group_id", groupIds)
        val conflicts = selectIn<ConflictEntity>("conflicts", "group_id", groupIds)
        val editConflicts = selectIn<ExpenseEditConflictEntity>("expense_edit_conflicts", "group_id", groupIds)
        // Expense activity (F5) hangs off the expenses, keyed by expense_id like shares.
        val comments = if (expenseIds.isEmpty()) emptyList() else selectIn<CommentEntity>("comments", "expense_id", expenseIds)
        val receipts = if (expenseIds.isEmpty()) emptyList() else selectIn<ReceiptEntity>("receipts", "expense_id", expenseIds)
        val history = if (expenseIds.isEmpty()) emptyList() else selectIn<HistoryEventEntity>("expense_history", "expense_id", expenseIds)
        // "Split the bill" items + claims hang off the expense, keyed by expense_id like shares.
        val expenseItems = if (expenseIds.isEmpty()) emptyList() else selectIn<ExpenseItemEntity>("expense_items", "expense_id", expenseIds)
        val itemClaims = if (expenseIds.isEmpty()) emptyList() else selectIn<ItemClaimEntity>("item_claims", "expense_id", expenseIds)
        val itemShares = if (expenseIds.isEmpty()) emptyList() else selectIn<ItemShareEntity>("item_shares", "expense_id", expenseIds)
        val billParticipants = if (expenseIds.isEmpty()) emptyList() else selectIn<BillParticipantEntity>("bill_participants", "expense_id", expenseIds)
        val userIds = (members.map { it.userId } + expenses.mapNotNull { it.payerUserId } + shares.map { it.userId }).distinct()
        val users = if (userIds.isEmpty()) emptyList() else selectIn<UserEntity>("users", "id", userIds)

        // 3. Land them in Room (parents before children isn't required — there are no FK constraints).
        //    Every synced table that carries updated_at gets a last-write-wins guard (Rule 5): never let an
        //    incoming server row overwrite a *newer* local one. Without it, a pull that races ahead of our
        //    own push reverts a just-made local edit. Live vectors:
        //      • Financial: a just-recorded settle snaps back to its pre-payment value — the "first settle
        //        does nothing, second sticks" bug.
        //      • Identity/roster: a claimed/soft-left placeholder member (reconcile or claim-on-join stamps
        //        status='LEFT' + placeholder_claim_completed_at) gets RESURRECTED by the still-ACTIVE stale
        //        server row, and a Settings display-name rename gets REVERTED — both before our push lands.
        //      • Soft-deleted comments/receipts likewise un-delete if a stale ACTIVE server row wins.
        //    Every local mutation bumps updated_at, so compare it and keep the local row when it's strictly
        //    newer. conflicts/expense_history are exempt: they have no updated_at — a conflict is a sync
        //    artifact keyed on resolved_at, and expense_history is an append-only event feed (re-pull is
        //    idempotent), so neither has a "newer local edit" to clobber.
        val freshUsers = keepNewer(users, db.userDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshUsers.isNotEmpty()) {
            db.userDao().upsertAll(freshUsers)
            stampSynced("users", freshUsers) { it.id }
        }
        val freshGroups = keepNewer(groups, db.groupDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshGroups.isNotEmpty()) {
            db.groupDao().upsertAll(freshGroups)
            stampSynced("groups", freshGroups) { it.id }
        }
        val freshMembers = keepNewer(members, db.memberDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshMembers.isNotEmpty()) {
            db.memberDao().upsertAll(freshMembers)
            stampSynced("members", freshMembers) { it.id }
        }
        // Expenses additionally respect the optimistic-concurrency tracker: never let a pull overwrite a
        // locally-DIRTY expense (one with an unsynced edit, i.e. local row_version != its synced base).
        // That edit belongs to the next commit_expense CAS — pull clobbering it would be the silent loss
        // we're eliminating. Non-dirty expenses are seeded so the next push CASes against the right base.
        val syncState = db.expenseSyncStateDao().all().associate { it.expenseId to it.syncedVersion }
        val localExpenses = db.expenseDao().allForSync()
        val dirtyExpenseIds = localExpenses
            .filter { syncState[it.id] != null && syncState[it.id] != it.rowVersion }
            .mapTo(HashSet()) { it.id }
        val freshExpenses = keepNewer(expenses, localExpenses, { it.id }, { it.updatedAt })
            .filter { it.id !in dirtyExpenseIds }
        if (freshExpenses.isNotEmpty()) db.expenseDao().upsertAll(freshExpenses)
        val seedStates = expenses.filter { it.id !in dirtyExpenseIds }
            .map { ExpenseSyncStateEntity(it.id, it.rowVersion, it.splitVersion) }
        if (seedStates.isNotEmpty()) db.expenseSyncStateDao().upsertAll(seedStates)
        val freshShares = keepNewer(shares, db.shareDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshShares.isNotEmpty()) db.shareDao().upsertAll(freshShares) // shares ride with expenses; no independent push to track
        val freshSettlements = keepNewer(settlements, db.settlementDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshSettlements.isNotEmpty()) {
            db.settlementDao().upsertAll(freshSettlements)
            stampSynced("settlements", freshSettlements) { it.id }
        }
        val freshComments = keepNewer(comments, db.commentDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshComments.isNotEmpty()) {
            db.commentDao().upsertAll(freshComments)
            stampSynced("comments", freshComments) { it.id }
        }
        val freshReceipts = keepNewer(receipts, db.receiptDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshReceipts.isNotEmpty()) {
            db.receiptDao().upsertAll(freshReceipts)
            stampSynced("receipts", freshReceipts) { it.id }
        }
        val freshCategories = keepNewer(categories, db.categoryDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshCategories.isNotEmpty()) {
            db.categoryDao().upsertAll(freshCategories)
            stampSynced("categories", freshCategories) { it.id }
        }
        // Bill items + claims carry updated_at → last-write-wins guard (Rule 5). Claims are partitioned by
        // user, so the guard simply keeps each side's own newest claim; no cross-user clobber is possible.
        val freshItems = keepNewer(expenseItems, db.expenseItemDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshItems.isNotEmpty()) {
            db.expenseItemDao().upsertAll(freshItems)
            stampSynced("expense_items", freshItems) { it.id }
        }
        val freshClaims = keepNewer(itemClaims, db.itemClaimDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshClaims.isNotEmpty()) {
            db.itemClaimDao().upsertAll(freshClaims)
            stampSynced("item_claims", freshClaims) { it.id }
        }
        val freshItemShares = keepNewer(itemShares, db.itemShareDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshItemShares.isNotEmpty()) {
            db.itemShareDao().upsertAll(freshItemShares)
            stampSynced("item_shares", freshItemShares) { it.id }
        }
        val freshParticipants = keepNewer(billParticipants, db.billParticipantDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshParticipants.isNotEmpty()) {
            db.billParticipantDao().upsertAll(freshParticipants)
            stampSynced("bill_participants", freshParticipants) { it.id }
        }
        // Allocations are append-only ground truth (no updated_at); blind upsert is correct.
        if (allocations.isNotEmpty()) {
            db.settlementDao().upsertAllocations(allocations)
            stampSynced("settlement_allocations", allocations) { it.id }
        }
        // conflicts / edit-conflicts / expense_history carry no updated_at (see note above) — blind upsert.
        if (conflicts.isNotEmpty()) {
            db.conflictDao().upsertAll(conflicts)
            stampSynced("conflicts", conflicts) { it.id }
        }
        if (editConflicts.isNotEmpty()) {
            db.expenseEditConflictDao().upsertAll(editConflicts)
            stampSynced("expense_edit_conflicts", editConflicts) { it.id }
        }
        if (history.isNotEmpty()) {
            db.historyEventDao().upsertAll(history)
            stampSynced("expense_history", history) { it.id }
        }
    }

    /** Record each row's current fingerprint as synced, so [push] won't re-upload it unchanged. */
    private suspend fun <T : Any> stampSynced(table: String, rows: List<T>, id: (T) -> String) {
        db.rowSyncStateDao().upsertAll(rows.map { RowSyncStateEntity(table, id(it), it.hashCode()) })
    }

    /**
     * Push the device's local rows up (local → server). Only rows whose content actually changed
     * since the last successful push or pull go out ([pushDirty] — see [RowSyncStateEntity]); this
     * used to be a blind full-table re-upsert of every local row on every trigger (each local edit +
     * a 60s fallback tick, forever), which re-wrote unchanged rows over and over and was the actual
     * driver of Realtime-message-quota overage (every write, changed or not, fans out to every
     * connected client). EXPENSES (and their shares) still go through the [pushExpenses]
     * optimistic-concurrency RPC instead, so a stale edit is parked rather than silently clobbering.
     * [actorUserId] is the conflict's actor.
     */
    suspend fun push(actorUserId: String): AppResult<Unit> = runCatchingSync {
        pushDirty("users", db.userDao().allForSync()) { it.id }
        pushDirty("groups", db.groupDao().allForSync()) { it.id }
        pushDirty("members", db.memberDao().allForSync()) { it.id }
        pushExpenses(actorUserId) // expenses + shares move as one atomic, version-guarded unit
        pushDirty("settlements", db.settlementDao().allForSync()) { it.id }
        pushDirty("settlement_allocations", db.settlementDao().allAllocationsForSync()) { it.id }
        pushDirty("conflicts", db.conflictDao().allForSync()) { it.id }
        pushDirty("expense_edit_conflicts", db.expenseEditConflictDao().allForSync()) { it.id }
        pushDirty("comments", db.commentDao().allForSync()) { it.id }
        pushDirty("receipts", db.receiptDao().allForSync()) { it.id }
        pushDirty("categories", db.categoryDao().allForSync()) { it.id }
        pushDirty("expense_history", db.historyEventDao().allForSync()) { it.id }
        pushDirty("expense_items", db.expenseItemDao().allForSync()) { it.id }
        pushDirty("item_claims", db.itemClaimDao().allForSync()) { it.id }
        pushDirty("item_shares", db.itemShareDao().allForSync()) { it.id }
        pushDirty("bill_participants", db.billParticipantDao().allForSync()) { it.id }
    }

    /** Upsert + fingerprint only the rows in [rows] whose content differs from their last sync. */
    private suspend inline fun <reified T : Any> pushDirty(table: String, rows: List<T>, id: (T) -> String) {
        val synced = db.rowSyncStateDao().forTable(table).associate { it.rowId to it.syncedHash }
        val dirty = rows.filter { synced[id(it)] != it.hashCode() }
        if (dirty.isEmpty()) return
        upsertAll(table, dirty)
        db.rowSyncStateDao().upsertAll(dirty.map { RowSyncStateEntity(table, id(it), it.hashCode()) })
    }

    /**
     * Push each dirty expense through the server's `merge_expense` (Track F). The whole expense + its
     * active shares are sent as one unit with the `base_split_version` we last saw confirmed. The RPC
     * merges Zone 1 (title/notes/category/date) per-field by newest timestamp and guards Zone 2 (the
     * money value) by a causal `split_version`, then returns the merged canonical expense + shares:
     *  - **created / merged / deleted** → adopt the canonical directly (both versions confirmed).
     *  - **superseded** → our split edit was causally stale; the server kept its advanced split and
     *    logged ours. We adopt the canonical (reverting our split) and drop a one-sided "your change was
     *    superseded — review?" notice for THIS user only. No two-sided conflict card is ever created.
     * Adopting from the RPC payload means no re-pull — the returned row already reflects the merge.
     */
    private suspend fun pushExpenses(actorUserId: String) {
        val states = db.expenseSyncStateDao().all().associateBy { it.expenseId }
        for (e in db.expenseDao().allForSync()) {
            val st = states[e.id]
            if (st?.syncedVersion == e.rowVersion) continue // not dirty — already confirmed at this version
            // The causal base is our last-confirmed split version; a first push (no state) treats the
            // local split_version as the base so a brand-new expense is a clean create.
            val baseSplit = st?.syncedSplitVersion ?: e.splitVersion
            val shares = if (e.deletedAt != null) emptyList() else db.shareDao().getByExpense(e.id)
            val result = client.postgrest
                .rpc("merge_expense", MergeExpenseParams(e, shares, baseSplit, actorUserId))
                .decodeAs<MergeResult>()
            val canonical = result.expense
            if (canonical != null) {
                db.expenseDao().overwriteFromServer(canonical, result.shares, now = canonical.updatedAt)
                db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, canonical.rowVersion, canonical.splitVersion))
                if (result.status == "superseded") {
                    db.supersededNoticeDao().upsert(
                        SupersededNoticeEntity(e.id, e.groupId, createdAt = canonical.updatedAt),
                    )
                }
            } else {
                // Defensive: the RPC returned no canonical row. Mark synced at the base we sent so this
                // expense doesn't hot-loop; the next real change re-dirties it.
                db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, e.rowVersion, baseSplit))
            }
        }
    }

    /** A full round trip: push local mutations, then pull the latest. */
    suspend fun syncNow(userId: String): AppResult<Unit> {
        push(userId).let { if (it is AppResult.Err) return it }
        return pull(userId)
    }

    private suspend inline fun <reified T : Any> selectIn(table: String, column: String, values: List<String>): List<T> =
        client.from(table).select(Columns.ALL) { filter { isIn(column, values) } }.decodeList<T>()

    private suspend inline fun <reified T : Any> upsertAll(table: String, rows: List<T>) {
        if (rows.isNotEmpty()) client.from(table).upsert(rows)
    }

    private inline fun runCatchingSync(block: () -> Unit): AppResult<Unit> =
        try {
            block()
            AppResult.Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }

    companion object {
        /**
         * Keep only the incoming rows that are at least as new as the local copy (by `updated_at`). A row
         * the local DB has *newer* is dropped so the pull can't clobber an unsynced local edit (Rule 5) —
         * a just-recorded settle, a claimed/soft-left placeholder member, or a Settings rename that hasn't
         * pushed yet. Rows absent locally pass through (new from the server). `internal` so [SyncEngineTest]
         * can exercise the guard without standing up a Supabase client.
         */
        internal inline fun <T> keepNewer(
            incoming: List<T>,
            local: List<T>,
            id: (T) -> String,
            updatedAt: (T) -> Long,
        ): List<T> {
            if (incoming.isEmpty()) return incoming
            val localTs = local.associate { id(it) to updatedAt(it) }
            return incoming.filter { (localTs[id(it)] ?: Long.MIN_VALUE) <= updatedAt(it) }
        }
    }
}
