package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.CommentEntity
import da.chelimo.sharecost.data.db.entity.ConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.entity.UserEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlin.coroutines.cancellation.CancellationException

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
        val conflicts = selectIn<ConflictEntity>("conflicts", "group_id", groupIds)
        // Expense activity (F5) hangs off the expenses, keyed by expense_id like shares.
        val comments = if (expenseIds.isEmpty()) emptyList() else selectIn<CommentEntity>("comments", "expense_id", expenseIds)
        val receipts = if (expenseIds.isEmpty()) emptyList() else selectIn<ReceiptEntity>("receipts", "expense_id", expenseIds)
        val history = if (expenseIds.isEmpty()) emptyList() else selectIn<HistoryEventEntity>("expense_history", "expense_id", expenseIds)
        val userIds = (members.map { it.userId } + expenses.mapNotNull { it.payerUserId } + shares.map { it.userId }).distinct()
        val users = if (userIds.isEmpty()) emptyList() else selectIn<UserEntity>("users", "id", userIds)

        // 3. Land them in Room (parents before children isn't required — there are no FK constraints).
        if (users.isNotEmpty()) db.userDao().upsertAll(users)
        db.groupDao().upsertAll(groups)
        db.memberDao().upsertAll(members)
        if (expenses.isNotEmpty()) db.expenseDao().upsertAll(expenses)
        if (shares.isNotEmpty()) db.shareDao().upsertAll(shares)
        if (settlements.isNotEmpty()) db.settlementDao().upsertAll(settlements)
        if (conflicts.isNotEmpty()) db.conflictDao().upsertAll(conflicts)
        if (comments.isNotEmpty()) db.commentDao().upsertAll(comments)
        if (receipts.isNotEmpty()) db.receiptDao().upsertAll(receipts)
        if (history.isNotEmpty()) db.historyEventDao().upsertAll(history)
    }

    /** Push the device's local rows up (local → server). Last-write-wins upsert per table. */
    suspend fun push(): AppResult<Unit> = runCatchingSync {
        upsertAll("users", db.userDao().allForSync())
        upsertAll("groups", db.groupDao().allForSync())
        upsertAll("members", db.memberDao().allForSync())
        upsertAll("expenses", db.expenseDao().allForSync())
        upsertAll("shares", db.shareDao().allForSync())
        upsertAll("settlements", db.settlementDao().allForSync())
        upsertAll("conflicts", db.conflictDao().allForSync())
        upsertAll("comments", db.commentDao().allForSync())
        upsertAll("receipts", db.receiptDao().allForSync())
        upsertAll("expense_history", db.historyEventDao().allForSync())
    }

    /** A full round trip: push local mutations, then pull the latest. */
    suspend fun syncNow(userId: String): AppResult<Unit> {
        push().let { if (it is AppResult.Err) return it }
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
}
