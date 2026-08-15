package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Clears the device's cache of one account's data, as ONE transaction, so the next account signing in
 * on this device starts from empty.
 *
 * **Why this exists.** `signOut()` used to leave Room fully populated. Sign out, sign in as someone
 * else, and the next `push()` sent account A's rows up under account B's session — a cross-account data
 * leak in a money app. Evenly Pro widened it: `group_passes` and `user_subscriptions` are per-user, so a
 * stale local pass rode along too.
 *
 * **Three rules this file exists to keep straight.**
 *
 * 1. **`clearAllTables()` is not an option.** It resolves on Android/JVM and fails on Kotlin/Native
 *    (`AGENTS.md` §4.1). Every table is deleted explicitly, which is also why adding a table to
 *    `EvenlyDatabase` means adding a line here — a table left out is the leak coming back.
 * 2. **This is a CACHE wipe, not a delete.** Every row here is either server-owned (it rehydrates on
 *    the next pull) or device-local. It is emphatically NOT the soft-delete rule in `data/AGENTS.md`
 *    Rule 1 being bypassed: nothing is being deleted *for the user*, and no tombstone is produced.
 *    Which is exactly why the caller must confirm the rows have reached the server first — see
 *    `SupabaseAuthSession.signOut`. Never call this from the account-deletion path (Rule 9: a deletion
 *    is cancellable within its grace period, so its local state must survive).
 * 3. **FX reference data stays.** `fx_rates` and `fx_currencies` are global exchange-rate reference,
 *    carry nothing about anybody, and are expensive to refetch. `fx_baked` is keyed to expenses, so it
 *    goes with them.
 */
@Dao
interface SignOutWipeDao {
    @Transaction
    suspend fun wipeSignedOutAccount() {
        // Synced tables — the server is the authority for all of these; they rehydrate on next pull.
        clearUsers()
        clearGroups()
        clearMembers()
        clearPlaceholderClaimAnswers()
        clearExpenses()
        clearShares()
        clearSettlements()
        clearSettlementAllocations()
        clearConflicts()
        clearExpenseEditConflicts()
        clearComments()
        clearExpenseBlockedUsers()
        clearReceipts()
        clearExpenseHistory()
        clearCategories()
        clearExpenseItems()
        clearItemClaims()
        clearItemShares()
        clearBillParticipants()
        clearPendingItemEdits()
        // Pull-only Pro entitlement mirrors — per-USER, so the next account must not inherit them.
        clearGroupPasses()
        clearUserSubscriptions()
        // Sync bookkeeping. These MUST go with the rows they describe: a surviving fingerprint would
        // make the next account's freshly-pulled rows look already-pushed, and they'd never sync.
        clearRowSyncState()
        clearExpenseSyncState()
        // Device-local caches scoped to the account that just left.
        clearSupersededNotices()
        clearGroupScanUsage()
        clearReceiptUploads()
        clearFxBaked()
    }

    @Query("DELETE FROM users")
    suspend fun clearUsers()

    @Query("DELETE FROM groups")
    suspend fun clearGroups()

    @Query("DELETE FROM members")
    suspend fun clearMembers()

    @Query("DELETE FROM placeholder_claim_answers")
    suspend fun clearPlaceholderClaimAnswers()

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    @Query("DELETE FROM shares")
    suspend fun clearShares()

    @Query("DELETE FROM settlements")
    suspend fun clearSettlements()

    @Query("DELETE FROM settlement_allocations")
    suspend fun clearSettlementAllocations()

    @Query("DELETE FROM conflicts")
    suspend fun clearConflicts()

    @Query("DELETE FROM expense_edit_conflicts")
    suspend fun clearExpenseEditConflicts()

    @Query("DELETE FROM comments")
    suspend fun clearComments()

    @Query("DELETE FROM expense_blocked_users")
    suspend fun clearExpenseBlockedUsers()

    @Query("DELETE FROM receipts")
    suspend fun clearReceipts()

    @Query("DELETE FROM expense_history")
    suspend fun clearExpenseHistory()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM expense_items")
    suspend fun clearExpenseItems()

    @Query("DELETE FROM item_claims")
    suspend fun clearItemClaims()

    @Query("DELETE FROM item_shares")
    suspend fun clearItemShares()

    @Query("DELETE FROM bill_participants")
    suspend fun clearBillParticipants()

    @Query("DELETE FROM pending_item_edits")
    suspend fun clearPendingItemEdits()

    @Query("DELETE FROM group_passes")
    suspend fun clearGroupPasses()

    @Query("DELETE FROM user_subscriptions")
    suspend fun clearUserSubscriptions()

    @Query("DELETE FROM row_sync_state")
    suspend fun clearRowSyncState()

    @Query("DELETE FROM expense_sync_state")
    suspend fun clearExpenseSyncState()

    @Query("DELETE FROM superseded_notices")
    suspend fun clearSupersededNotices()

    @Query("DELETE FROM group_scan_usage")
    suspend fun clearGroupScanUsage()

    @Query("DELETE FROM receipt_uploads")
    suspend fun clearReceiptUploads()

    @Query("DELETE FROM fx_baked")
    suspend fun clearFxBaked()
}
