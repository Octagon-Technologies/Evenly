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
import da.chelimo.sharecost.data.db.entity.ExpenseItemEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.ItemClaimEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
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
 * Parameters for the `commit_expense` RPC. The snake_case serializer maps `pExpense` → `p_expense`
 * etc.; the nested entities serialize to snake_case columns (the same wire shape as a table upsert).
 */
@Serializable
private data class CommitExpenseParams(
    @SerialName("p_expense") val pExpense: ExpenseEntity,
    @SerialName("p_shares") val pShares: List<ShareEntity>,
    @SerialName("p_base_version") val pBaseVersion: Long,
    @SerialName("p_actor") val pActor: String,
)

/** Result of `commit_expense`: `created`/`committed` carry the new `version`; `conflict` the server's. */
@Serializable
private data class CommitResult(
    val status: String,
    val version: Long? = null,
    @SerialName("server_version") val serverVersion: Long? = null,
    @SerialName("conflict_id") val conflictId: String? = null,
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
        if (freshUsers.isNotEmpty()) db.userDao().upsertAll(freshUsers)
        val freshGroups = keepNewer(groups, db.groupDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshGroups.isNotEmpty()) db.groupDao().upsertAll(freshGroups)
        val freshMembers = keepNewer(members, db.memberDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshMembers.isNotEmpty()) db.memberDao().upsertAll(freshMembers)
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
            .map { ExpenseSyncStateEntity(it.id, it.rowVersion) }
        if (seedStates.isNotEmpty()) db.expenseSyncStateDao().upsertAll(seedStates)
        val freshShares = keepNewer(shares, db.shareDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshShares.isNotEmpty()) db.shareDao().upsertAll(freshShares)
        val freshSettlements = keepNewer(settlements, db.settlementDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshSettlements.isNotEmpty()) db.settlementDao().upsertAll(freshSettlements)
        val freshComments = keepNewer(comments, db.commentDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshComments.isNotEmpty()) db.commentDao().upsertAll(freshComments)
        val freshReceipts = keepNewer(receipts, db.receiptDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshReceipts.isNotEmpty()) db.receiptDao().upsertAll(freshReceipts)
        val freshCategories = keepNewer(categories, db.categoryDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshCategories.isNotEmpty()) db.categoryDao().upsertAll(freshCategories)
        // Bill items + claims carry updated_at → last-write-wins guard (Rule 5). Claims are partitioned by
        // user, so the guard simply keeps each side's own newest claim; no cross-user clobber is possible.
        val freshItems = keepNewer(expenseItems, db.expenseItemDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshItems.isNotEmpty()) db.expenseItemDao().upsertAll(freshItems)
        val freshClaims = keepNewer(itemClaims, db.itemClaimDao().allForSync(), { it.id }, { it.updatedAt })
        if (freshClaims.isNotEmpty()) db.itemClaimDao().upsertAll(freshClaims)
        // Allocations are append-only ground truth (no updated_at); blind upsert is correct.
        if (allocations.isNotEmpty()) db.settlementDao().upsertAllocations(allocations)
        // conflicts / edit-conflicts / expense_history carry no updated_at (see note above) — blind upsert.
        if (conflicts.isNotEmpty()) db.conflictDao().upsertAll(conflicts)
        if (editConflicts.isNotEmpty()) db.expenseEditConflictDao().upsertAll(editConflicts)
        if (history.isNotEmpty()) db.historyEventDao().upsertAll(history)
    }

    /**
     * Push the device's local rows up (local → server). Most tables are last-write-wins upserts;
     * EXPENSES (and their shares) go through the [pushExpenses] optimistic-concurrency RPC instead, so
     * a stale edit is parked rather than silently clobbering. [actorUserId] is the conflict's actor.
     */
    suspend fun push(actorUserId: String): AppResult<Unit> = runCatchingSync {
        upsertAll("users", db.userDao().allForSync())
        upsertAll("groups", db.groupDao().allForSync())
        upsertAll("members", db.memberDao().allForSync())
        pushExpenses(actorUserId) // expenses + shares move as one atomic, version-guarded unit
        upsertAll("settlements", db.settlementDao().allForSync())
        upsertAll("settlement_allocations", db.settlementDao().allAllocationsForSync())
        upsertAll("conflicts", db.conflictDao().allForSync())
        upsertAll("expense_edit_conflicts", db.expenseEditConflictDao().allForSync())
        upsertAll("comments", db.commentDao().allForSync())
        upsertAll("receipts", db.receiptDao().allForSync())
        upsertAll("categories", db.categoryDao().allForSync())
        upsertAll("expense_history", db.historyEventDao().allForSync())
        upsertAll("expense_items", db.expenseItemDao().allForSync())
        upsertAll("item_claims", db.itemClaimDao().allForSync())
    }

    /**
     * Push each dirty expense through the server's `commit_expense` compare-and-swap. The whole
     * expense + its active shares are sent as one unit with the `base_version` we last saw confirmed:
     *  - **created / committed** → adopt the server's new version into the local base tracker.
     *  - **conflict** (our base is stale) → the server parked our payload in `expense_edit_conflicts`;
     *    roll the local cache back to the canonical row so the device shows the winning version.
     * Soft-deleted expenses are tombstones — propagated by a plain upsert (LWW is safe for deletes).
     */
    private suspend fun pushExpenses(actorUserId: String) {
        val synced = db.expenseSyncStateDao().all().associate { it.expenseId to it.syncedVersion }
        for (e in db.expenseDao().allForSync()) {
            if (synced[e.id] == e.rowVersion) continue // not dirty — already confirmed at this version
            if (e.deletedAt != null) {
                upsertAll("expenses", listOf(e))
                db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, e.rowVersion))
                continue
            }
            val base = synced[e.id] ?: e.rowVersion
            val shares = db.shareDao().getByExpense(e.id) // active shares only
            val result = client.postgrest
                .rpc("commit_expense", CommitExpenseParams(e, shares, base, actorUserId))
                .decodeAs<CommitResult>()
            when (result.status) {
                "created", "committed" ->
                    db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, result.version ?: e.rowVersion))
                "conflict" ->
                    revertExpenseToServer(e.id)
            }
        }
    }

    /** Roll one expense's local cache back to the server's canonical row + shares after a parked edit. */
    private suspend fun revertExpenseToServer(expenseId: String) {
        val canonical = client.from("expenses").select(Columns.ALL) { filter { eq("id", expenseId) } }
            .decodeList<ExpenseEntity>().firstOrNull() ?: return
        val serverShares = client.from("shares").select(Columns.ALL) { filter { eq("expense_id", expenseId) } }
            .decodeList<ShareEntity>()
        db.expenseDao().overwriteFromServer(canonical, serverShares, now = canonical.updatedAt)
        db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(expenseId, canonical.rowVersion))
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
