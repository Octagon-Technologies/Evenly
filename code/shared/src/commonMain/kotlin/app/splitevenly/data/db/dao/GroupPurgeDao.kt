package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Removes one deleted group from this device, permanently, as ONE transaction.
 *
 * **This is the client half of `purge_deleted_groups()`** (`supabase/schema.sql`), and the only place
 * in the app that hard-deletes user data. It is exempt from `data/AGENTS.md` Rule 1 by an explicit
 * owner decision (2026-08-17), on a narrow argument: the group was deleted for *every* member, no
 * member has any surface left that can reach it, and the 30 days they had to change their minds have
 * elapsed. A tombstone nobody can read is not a record, it is a copy of private financial data we
 * promised to delete and then kept.
 *
 * **Why the client purges on its own timer instead of following a server tombstone.** After the
 * server purge there is nothing left to follow: `SyncEngine.pull` scopes to the groups the user is an
 * ACTIVE member of, and the `members` rows are gone with everything else, so the group simply stops
 * appearing in any response. `land()` only upserts what came back and never deletes what didn't, so a
 * device that waited to be told would keep the group forever. Both sides therefore run the same
 * 30-day rule off the same `groups.deleted_at`, and a device that was offline for the whole window
 * purges on its next launch.
 *
 * **Order is load-bearing in two places** and nowhere else (Room has no FKs here, by design):
 *  1. `row_sync_state`/`expense_sync_state` fingerprints are resolved through the rows they describe,
 *     so they must be deleted *while those rows still exist*.
 *  2. `shares` has no `group_id` and reaches the group through its expense, so it goes before
 *     `expenses`.
 *
 * Adding a group-scoped table to `EvenlyDatabase` means adding a line here, same contract as
 * [SignOutWipeDao]. A table left out survives its group forever.
 */
@Dao
interface GroupPurgeDao {
    /**
     * The sandbox files this group's queued uploads are standing on. Read BEFORE [purgeGroup], since
     * afterwards nothing records where they were — the rows are the only index of those bytes, and a
     * receipt photo surviving a purge is exactly what the feature promises it will not do.
     */
    @Query("SELECT local_path FROM receipt_uploads WHERE group_id = :groupId")
    suspend fun stagedReceiptPaths(groupId: String): List<String>

    /** Hard-delete every local trace of [groupId]. Idempotent: a second call deletes nothing. */
    @Transaction
    suspend fun purgeGroup(groupId: String) {
        // 1. Sync fingerprints first, while the rows they point at are still here to be found.
        clearExpenseSyncStateFor(groupId)
        clearRowSyncStateForGroupRow(groupId)
        clearRowSyncStateFor("members", groupId)
        clearRowSyncStateFor("expenses", groupId)
        clearRowSyncStateFor("settlements", groupId)
        clearRowSyncStateFor("settlement_allocations", groupId)
        clearRowSyncStateFor("categories", groupId)
        clearRowSyncStateFor("conflicts", groupId)
        clearRowSyncStateFor("expense_edit_conflicts", groupId)
        clearRowSyncStateFor("comments", groupId)
        clearRowSyncStateFor("expense_blocked_users", groupId)
        clearRowSyncStateFor("receipts", groupId)
        clearRowSyncStateFor("expense_history", groupId)
        clearRowSyncStateFor("expense_items", groupId)
        clearRowSyncStateFor("item_claims", groupId)
        clearRowSyncStateFor("item_shares", groupId)
        clearRowSyncStateFor("bill_participants", groupId)
        clearRowSyncStateFor("pending_item_edits", groupId)
        clearRowSyncStateFor("placeholder_claim_answers", groupId)
        clearRowSyncStateForShares(groupId)
        clearRowSyncStateForPlaceholders(groupId)

        // 2. The money, children before parents where a child names its parent.
        clearShares(groupId)
        clearItemClaims(groupId)
        clearItemShares(groupId)
        clearExpenseItems(groupId)
        clearBillParticipants(groupId)
        clearPendingItemEdits(groupId)
        clearSettlementAllocations(groupId)
        clearSettlements(groupId)
        clearComments(groupId)
        clearExpenseBlockedUsers(groupId)
        clearReceipts(groupId)
        clearExpenseHistory(groupId)
        clearConflicts(groupId)
        clearExpenseEditConflicts(groupId)
        clearExpenses(groupId)
        clearCategories(groupId)
        clearPlaceholderClaimAnswers(groupId)

        // 3. Device-local caches keyed to this group. `receipt_uploads` is the outbox: a queued upload
        //    for a purged group has nowhere left to land, and its staged bytes are cleaned up by the
        //    caller before this runs.
        clearSupersededNotices(groupId)
        clearReceiptUploads(groupId)

        // 4. The roster. Placeholders are group-private by construction and die with the group; real
        //    users are shared across groups and are never touched here.
        clearPlaceholderUsers(groupId)
        clearMembers(groupId)
        clearGroup(groupId)
    }

    // --- Sync fingerprints ------------------------------------------------------------------------

    @Query("DELETE FROM expense_sync_state WHERE expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)")
    suspend fun clearExpenseSyncStateFor(groupId: String)

    @Query("DELETE FROM row_sync_state WHERE table_name = 'groups' AND row_id = :groupId")
    suspend fun clearRowSyncStateForGroupRow(groupId: String)

    /**
     * The generic arm. `table_name` is a bound value rather than interpolated SQL, so the subquery has
     * to read from a table Room can name statically — hence the union of every group-scoped id below
     * rather than one query per table. Ids are UUIDs, so a cross-table collision is not a concern.
     */
    @Query(
        """
        DELETE FROM row_sync_state
        WHERE table_name = :table
          AND row_id IN (
            SELECT id FROM members WHERE group_id = :groupId
            UNION ALL SELECT id FROM expenses WHERE group_id = :groupId
            UNION ALL SELECT id FROM settlements WHERE group_id = :groupId
            UNION ALL SELECT id FROM settlement_allocations WHERE group_id = :groupId
            UNION ALL SELECT id FROM categories WHERE group_id = :groupId
            UNION ALL SELECT id FROM conflicts WHERE group_id = :groupId
            UNION ALL SELECT id FROM expense_edit_conflicts WHERE group_id = :groupId
            UNION ALL SELECT id FROM comments WHERE group_id = :groupId
            UNION ALL SELECT id FROM expense_blocked_users WHERE group_id = :groupId
            UNION ALL SELECT id FROM receipts WHERE group_id = :groupId
            UNION ALL SELECT id FROM expense_history WHERE group_id = :groupId
            UNION ALL SELECT id FROM expense_items WHERE group_id = :groupId
            UNION ALL SELECT id FROM item_claims WHERE group_id = :groupId
            UNION ALL SELECT id FROM item_shares WHERE group_id = :groupId
            UNION ALL SELECT id FROM bill_participants WHERE group_id = :groupId
            UNION ALL SELECT id FROM pending_item_edits WHERE group_id = :groupId
            UNION ALL SELECT id FROM placeholder_claim_answers WHERE group_id = :groupId
          )
        """,
    )
    suspend fun clearRowSyncStateFor(
        table: String,
        groupId: String,
    )

    @Query(
        """
        DELETE FROM row_sync_state
        WHERE table_name = 'shares'
          AND row_id IN (SELECT id FROM shares WHERE expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId))
        """,
    )
    suspend fun clearRowSyncStateForShares(groupId: String)

    @Query(
        """
        DELETE FROM row_sync_state
        WHERE table_name = 'users'
          AND row_id IN (SELECT id FROM users WHERE is_placeholder = 1 AND placeholder_group_id = :groupId)
        """,
    )
    suspend fun clearRowSyncStateForPlaceholders(groupId: String)

    // --- Synced rows ------------------------------------------------------------------------------

    @Query("DELETE FROM shares WHERE expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)")
    suspend fun clearShares(groupId: String)

    @Query("DELETE FROM item_claims WHERE group_id = :groupId")
    suspend fun clearItemClaims(groupId: String)

    @Query("DELETE FROM item_shares WHERE group_id = :groupId")
    suspend fun clearItemShares(groupId: String)

    @Query("DELETE FROM expense_items WHERE group_id = :groupId")
    suspend fun clearExpenseItems(groupId: String)

    @Query("DELETE FROM bill_participants WHERE group_id = :groupId")
    suspend fun clearBillParticipants(groupId: String)

    @Query("DELETE FROM pending_item_edits WHERE group_id = :groupId")
    suspend fun clearPendingItemEdits(groupId: String)

    @Query("DELETE FROM settlement_allocations WHERE group_id = :groupId")
    suspend fun clearSettlementAllocations(groupId: String)

    @Query("DELETE FROM settlements WHERE group_id = :groupId")
    suspend fun clearSettlements(groupId: String)

    @Query("DELETE FROM comments WHERE group_id = :groupId")
    suspend fun clearComments(groupId: String)

    @Query("DELETE FROM expense_blocked_users WHERE group_id = :groupId")
    suspend fun clearExpenseBlockedUsers(groupId: String)

    @Query("DELETE FROM receipts WHERE group_id = :groupId")
    suspend fun clearReceipts(groupId: String)

    @Query("DELETE FROM expense_history WHERE group_id = :groupId")
    suspend fun clearExpenseHistory(groupId: String)

    @Query("DELETE FROM conflicts WHERE group_id = :groupId")
    suspend fun clearConflicts(groupId: String)

    @Query("DELETE FROM expense_edit_conflicts WHERE group_id = :groupId")
    suspend fun clearExpenseEditConflicts(groupId: String)

    @Query("DELETE FROM expenses WHERE group_id = :groupId")
    suspend fun clearExpenses(groupId: String)

    @Query("DELETE FROM categories WHERE group_id = :groupId")
    suspend fun clearCategories(groupId: String)

    @Query("DELETE FROM placeholder_claim_answers WHERE group_id = :groupId")
    suspend fun clearPlaceholderClaimAnswers(groupId: String)

    // --- Device-local caches ----------------------------------------------------------------------

    @Query("DELETE FROM superseded_notices WHERE group_id = :groupId")
    suspend fun clearSupersededNotices(groupId: String)

    @Query("DELETE FROM receipt_uploads WHERE group_id = :groupId")
    suspend fun clearReceiptUploads(groupId: String)

    // --- The roster -------------------------------------------------------------------------------

    @Query("DELETE FROM users WHERE is_placeholder = 1 AND placeholder_group_id = :groupId")
    suspend fun clearPlaceholderUsers(groupId: String)

    @Query("DELETE FROM members WHERE group_id = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun clearGroup(groupId: String)
}
