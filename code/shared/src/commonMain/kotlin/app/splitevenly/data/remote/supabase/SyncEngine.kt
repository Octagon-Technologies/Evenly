package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.CategoryEntity
import app.splitevenly.data.db.entity.CommentEntity
import app.splitevenly.data.db.entity.ConflictEntity
import app.splitevenly.data.db.entity.ExpenseEditConflictEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseSyncStateEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.PendingItemEditEntity
import app.splitevenly.data.db.entity.PlaceholderClaimAnswerEntity
import app.splitevenly.data.db.entity.ReceiptEntity
import app.splitevenly.data.db.entity.RowSyncStateEntity
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.SupersededNoticeEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.repository.BillMaterializer
import app.splitevenly.domain.expense.SPLIT_MODE_ITEMIZED
import app.splitevenly.core.time.nowEpochMillis
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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

@OptIn(ExperimentalTime::class)
class SyncEngine(
    private val client: SupabaseClient,
    private val db: EvenlyDatabase,
) {

    // An itemized bill's shares are a LOCAL derived materialization of its synced items/claims, never
    // trusted from the server (its shares freeze at the last split edit). Re-derive on pull + push-adopt.
    private val billMaterializer = BillMaterializer(
        db.expenseDao(), db.expenseItemDao(), db.itemClaimDao(), db.itemShareDao(), db.shareDao(),
    )

    // Serialize sync ops (#6): three independent triggers (debounced push, realtime pull, the 60s
    // syncNow) plus mirrorCurrentUser's syncNow can otherwise overlap. Two concurrent pushes send the
    // same split edit twice → the second sees a stale base and the device SUPERSEDES ITSELF (a junk audit
    // row + a false "your change was superseded" notice). A single mutex means at most one push/pull runs
    // at a time; it also gives #8's in-flight-edit guard a stable window (no second sync op racing).
    private val syncMutex = Mutex()

    /** Pull the signed-in user's data into Room (server → local). Best-effort: errors are returned. */
    suspend fun pull(userId: String): AppResult<Unit> = syncMutex.withLock {
      runCatchingSync {
        // 1. The user's memberships give the set of groups to hydrate.
        val myMemberships = client.from("members").select(Columns.ALL) {
            filter { eq("user_id", userId) }
        }.decodeList<MemberEntity>()
        val groupIds = myMemberships.map { it.groupId }.distinct()
        if (groupIds.isEmpty()) {
            db.memberDao().upsertAll(myMemberships)
            // Still hydrate the caller's OWN users row — a membership-less account (fresh sign-in on a new
            // device) otherwise never pulls its real profile, so the "You" default mirrorCurrentUser seeded
            // would stand and then push over the server's real name/handles/prefs (P0 #4).
            val me = selectIn<UserEntity>("users", "id", listOf(userId))
            land("users", me, db.userDao().allForSync(), { it.id }, { it.updatedAt }) { db.userDao().upsertAll(it) }
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
        // "That name is not me" answers. Group-scoped rather than user-scoped on purpose: knowing that
        // EVERY member ruled a name out is what makes it confirmed account-less.
        val claimAnswers = selectIn<PlaceholderClaimAnswerEntity>("placeholder_claim_answers", "group_id", groupIds)
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
        // Web guests' proposed menu edits, awaiting the payer's individual approval (WEB_CLAIM_SPEC.md
        // §2.7). Pull-heavy by nature: the guests write them from the browser, the app only decides.
        val pendingItemEdits = if (expenseIds.isEmpty()) emptyList() else selectIn<PendingItemEditEntity>("pending_item_edits", "expense_id", expenseIds)
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
        land("users", users, db.userDao().allForSync(), { it.id }, { it.updatedAt }) { db.userDao().upsertAll(it) }
        land("groups", groups, db.groupDao().allForSync(), { it.id }, { it.updatedAt }) { db.groupDao().upsertAll(it) }
        land("members", members, db.memberDao().allForSync(), { it.id }, { it.updatedAt }) { db.memberDao().upsertAll(it) }
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
        // A bill's shares are a local derived materialization (rematerialized below), NOT server truth —
        // the server's copy freezes at the last split edit. Never adopt them on pull, or a stale/empty
        // server set would clobber the correct derived rows (P0 #3). Normal-expense shares still ride here.
        val itemizedExpenseIds = (expenses.asSequence() + localExpenses.asSequence())
            .filter { it.splitMode == SPLIT_MODE_ITEMIZED }.mapTo(HashSet()) { it.id }
        val freshShares = keepNewer(shares, db.shareDao().allForSync(), { it.id }, { it.updatedAt })
            .filterNot { it.expenseId in itemizedExpenseIds }
        if (freshShares.isNotEmpty()) db.shareDao().upsertAll(freshShares) // shares ride with expenses; no independent push to track
        land("settlements", settlements, db.settlementDao().allForSync(), { it.id }, { it.updatedAt }) { db.settlementDao().upsertAll(it) }
        land("comments", comments, db.commentDao().allForSync(), { it.id }, { it.updatedAt }) { db.commentDao().upsertAll(it) }
        land("receipts", receipts, db.receiptDao().allForSync(), { it.id }, { it.updatedAt }) { db.receiptDao().upsertAll(it) }
        land("categories", categories, db.categoryDao().allForSync(), { it.id }, { it.updatedAt }) { db.categoryDao().upsertAll(it) }
        // Bill items + claims carry updated_at → last-write-wins guard (Rule 5). Claims are partitioned by
        // user, so the guard simply keeps each side's own newest claim; no cross-user clobber is possible.
        val freshItems = land("expense_items", expenseItems, db.expenseItemDao().allForSync(), { it.id }, { it.updatedAt }) { db.expenseItemDao().upsertAll(it) }
        val freshClaims = land("item_claims", itemClaims, db.itemClaimDao().allForSync(), { it.id }, { it.updatedAt }) { db.itemClaimDao().upsertAll(it) }
        val freshItemShares = land("item_shares", itemShares, db.itemShareDao().allForSync(), { it.id }, { it.updatedAt }) { db.itemShareDao().upsertAll(it) }
        val freshParticipants = land("bill_participants", billParticipants, db.billParticipantDao().allForSync(), { it.id }, { it.updatedAt }) { db.billParticipantDao().upsertAll(it) }
        // Pending edits carry updated_at, so the same last-write-wins guard applies: a stale server copy
        // must not un-decide a verdict this device just stamped and hasn't pushed yet.
        land("pending_item_edits", pendingItemEdits, db.pendingItemEditDao().allForSync(), { it.id }, { it.updatedAt }) { db.pendingItemEditDao().upsertAll(it) }
        land("placeholder_claim_answers", claimAnswers, db.placeholderClaimAnswerDao().allForSync(), { it.id }, { it.updatedAt }) { db.placeholderClaimAnswerDao().upsertAll(it) }
        // Allocations are append-only ground truth (no updated_at); blind upsert is correct.
        if (allocations.isNotEmpty()) {
            db.settlementDao().upsertAllocations(allocations)
            stampSynced("settlement_allocations", allocations) { it.id }
        }
        // conflicts / edit-conflicts have no updated_at, but a blind upsert on pull can REVERT a just-made
        // local resolution (#10): a racing pull (600ms debounce, ahead of the 1.2s push) overwrites our
        // resolved row with the server's still-unresolved copy, then stampSynced marks it clean — so the
        // resolution never pushes and the card resurrects. Resolution is monotonic (resolved_at, once set,
        // never clears), so drop any incoming row still unresolved that we've already resolved locally.
        val localResolvedConflicts = db.conflictDao().allForSync()
            .filter { it.resolvedAt != null }.mapTo(HashSet()) { it.id }
        val freshConflicts = conflicts.filterNot { it.resolvedAt == null && it.id in localResolvedConflicts }
        if (freshConflicts.isNotEmpty()) {
            db.conflictDao().upsertAll(freshConflicts)
            stampSynced("conflicts", freshConflicts) { it.id }
        }
        val localResolvedEditConflicts = db.expenseEditConflictDao().allForSync()
            .filter { it.resolvedAt != null }.mapTo(HashSet()) { it.id }
        val freshEditConflicts = editConflicts.filterNot { it.resolvedAt == null && it.id in localResolvedEditConflicts }
        if (freshEditConflicts.isNotEmpty()) {
            db.expenseEditConflictDao().upsertAll(freshEditConflicts)
            stampSynced("expense_edit_conflicts", freshEditConflicts) { it.id }
        }
        // expense_history has no updated_at and is an append-only event feed — blind upsert is correct.
        if (history.isNotEmpty()) {
            db.historyEventDao().upsertAll(history)
            stampSynced("expense_history", history) { it.id }
        }

        // Re-derive every itemized bill's shares from the freshly-pulled items/claims so a non-writing
        // participant / fresh device converges on the correct shares (P0 #3). Runs LAST — it's the sole
        // writer of bill shares (they're excluded from the shares upsert above). Gated on bill-source
        // changes so an unrelated pull doesn't sweep every bill; the materialize itself is a no-op when a
        // share's owed is unchanged, so the sweep is cheap.
        val billSourcesChanged = freshExpenses.isNotEmpty() || freshItems.isNotEmpty() ||
            freshClaims.isNotEmpty() || freshItemShares.isNotEmpty() || freshParticipants.isNotEmpty()
        if (billSourcesChanged) billMaterializer.rematerializeGroups(groupIds, Clock.System.nowEpochMillis())
      }
    }

    /** Record each row's current fingerprint as synced, so [push] won't re-upload it unchanged. */
    private suspend fun <T : Any> stampSynced(table: String, rows: List<T>, id: (T) -> String) {
        db.rowSyncStateDao().upsertAll(rows.map { RowSyncStateEntity(table, id(it), it.hashCode()) })
    }

    /**
     * The ordinary landing for a synced table: drop incoming rows the local DB has *newer* ([keepNewer],
     * Rule 5), write the rest, and fingerprint them as synced. Returns what was actually landed.
     *
     * Extracted because [pull] does this a dozen times and inlining it there put the generated `pull`
     * over the JVM's 64KB per-method ceiling. The tables with their own rules (expenses' dirty guard,
     * a bill's derived shares, the no-`updated_at` append-only feeds) stay written out in [pull].
     */
    private suspend fun <T : Any> land(
        table: String,
        incoming: List<T>,
        local: List<T>,
        id: (T) -> String,
        updatedAt: (T) -> Long,
        upsert: suspend (List<T>) -> Unit,
    ): List<T> {
        val fresh = keepNewer(incoming, local, id, updatedAt)
        if (fresh.isNotEmpty()) {
            upsert(fresh)
            stampSynced(table, fresh, id)
        }
        return fresh
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
    suspend fun push(actorUserId: String): AppResult<Unit> = syncMutex.withLock {
        // Per-table isolation (#5): one table's push failing must NOT stop the tables sequenced after it.
        // A single wedged table (e.g. a unique-index 23505 from concurrent same-slot writes — now also
        // prevented by deterministic ids) otherwise blocks every later table forever, surfacing only as a
        // generic "unreachable". Each step is caught independently; a failed row stays dirty and retries
        // next cycle. We still report the FIRST failure so the round-trip isn't reported as fully clean.
        var firstError: Throwable? = null
        suspend fun step(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        step { pushDirty("users", db.userDao().allForSync()) { it.id } }
        step { pushDirty("groups", db.groupDao().allForSync()) { it.id } }
        step { pushDirty("members", db.memberDao().allForSync()) { it.id } }
        step { pushExpenses(actorUserId) } // expenses + shares move as one atomic, version-guarded unit
        step { pushDirty("settlements", db.settlementDao().allForSync()) { it.id } }
        step { pushDirty("settlement_allocations", db.settlementDao().allAllocationsForSync()) { it.id } }
        step { pushDirty("conflicts", db.conflictDao().allForSync()) { it.id } }
        step { pushDirty("expense_edit_conflicts", db.expenseEditConflictDao().allForSync()) { it.id } }
        step { pushDirty("comments", db.commentDao().allForSync()) { it.id } }
        step { pushDirty("receipts", db.receiptDao().allForSync()) { it.id } }
        step { pushDirty("categories", db.categoryDao().allForSync()) { it.id } }
        step { pushDirty("expense_history", db.historyEventDao().allForSync()) { it.id } }
        step { pushDirty("expense_items", db.expenseItemDao().allForSync()) { it.id } }
        step { pushDirty("item_claims", db.itemClaimDao().allForSync()) { it.id } }
        step { pushDirty("item_shares", db.itemShareDao().allForSync()) { it.id } }
        step { pushDirty("bill_participants", db.billParticipantDao().allForSync()) { it.id } }
        // Only the decision half ever originates here — the proposals arrive from the web.
        step { pushDirty("pending_item_edits", db.pendingItemEditDao().allForSync()) { it.id } }
        // No custom merge: the unique (group, name, answerer) key makes a re-insert idempotent, which is
        // what makes an offline "No" harmless if it ends up sent twice.
        step { pushDirty("placeholder_claim_answers", db.placeholderClaimAnswerDao().allForSync()) { it.id } }
        firstError.let { if (it == null) AppResult.Ok(Unit) else AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, it)) }
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
            // The causal base is our last server-confirmed split version. With a sync-state row that's just
            // its syncedSplitVersion. WITHOUT one (st == null) it's usually a clean create, so the local
            // split_version is the base. But st == null WITH a local split edit already applied
            // (split_version > 1) can also be a lost create-response: the server DID insert on an earlier
            // push whose confirmation never landed, then a later split edit advanced our split_version.
            // Using the local split_version as base would then compute client_changed=false and silently
            // DROP that split edit (#7). Pre-flight the server row: if it exists, seed sync state from it and
            // use ITS split_version as the base; only a genuinely absent row is a clean create.
            val baseSplit: Long = when {
                st != null -> st.syncedSplitVersion
                e.splitVersion > 1 -> {
                    val serverRow = client.from("expenses").select(Columns.ALL) { filter { eq("id", e.id) } }
                        .decodeList<ExpenseEntity>().firstOrNull()
                    if (serverRow != null) {
                        db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, serverRow.rowVersion, serverRow.splitVersion))
                        serverRow.splitVersion
                    } else {
                        e.splitVersion // genuinely absent → clean create with a pre-push split edit
                    }
                }
                else -> e.splitVersion
            }
            val sentRowVersion = e.rowVersion // snapshot for the in-flight-edit guard (#8)
            val shares = if (e.deletedAt != null) emptyList() else db.shareDao().getByExpense(e.id)
            val result = client.postgrest
                .rpc("merge_expense", MergeExpenseParams(e, shares, baseSplit, actorUserId))
                .decodeAs<MergeResult>()
            val canonical = result.expense
            if (canonical != null) {
                // Adopt the canonical ONLY if the local expense didn't change while the RPC was in flight
                // (#8): a user edit during the round-trip bumped row_version, and blindly adopting would
                // silently clobber it (and never re-dirty). When skipped, we leave the expense dirty (no
                // sync-state stamp, no notice) so the next push carries that newer edit.
                val adopted = if (canonical.splitMode == SPLIT_MODE_ITEMIZED) {
                    // A bill's shares are a LOCAL derived materialization, not server truth. A Zone-1-only
                    // (title fix) or superseded merge returns the server's frozen/stale shares, so adopt
                    // ONLY the canonical expense row and re-derive shares from the local items/claims —
                    // never the server share set, which would tombstone the correct derived rows (P0 #3).
                    db.expenseDao().upsertFromServerIfUnchanged(sentRowVersion, canonical)
                        .also { if (it) billMaterializer.materialize(canonical, now = canonical.updatedAt) }
                } else {
                    db.expenseDao().overwriteFromServerIfUnchanged(sentRowVersion, canonical, result.shares, now = canonical.updatedAt)
                }
                if (adopted) {
                    db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity(e.id, canonical.rowVersion, canonical.splitVersion))
                    if (result.status == "superseded") {
                        db.supersededNoticeDao().upsert(
                            SupersededNoticeEntity(e.id, e.groupId, createdAt = canonical.updatedAt),
                        )
                    }
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
