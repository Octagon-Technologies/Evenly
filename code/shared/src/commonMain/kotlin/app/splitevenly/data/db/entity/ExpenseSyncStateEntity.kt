package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local (NOT synced, NOT @Serializable) tracking of the last server-confirmed versions for each
 * expense — the bases the next push hands to the server's `merge_expense` merge.
 *
 * An expense is "dirty" (has an unsynced local edit) when its local `row_version` differs from
 * [syncedVersion]. [syncedSplitVersion] is the last server-confirmed Zone-2 `split_version` — the causal
 * base for `merge_expense`: the client stamps its local `split_version` to `syncedSplitVersion + 1` only
 * when it actually changed the split, so the server can tell a real split edit from a metadata-only one.
 * Seeded/refreshed on pull (a pulled expense is confirmed at its server versions) and updated when a
 * merge succeeds. Never leaves the device, so it stays out of
 * [app.splitevenly.data.remote.supabase.SyncEngine]'s table list and the realtime push trigger.
 */
@Entity(tableName = "expense_sync_state")
data class ExpenseSyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "synced_version") val syncedVersion: Long,
    @ColumnInfo(name = "synced_split_version") val syncedSplitVersion: Long = 1,
)
