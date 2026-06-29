package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local (NOT synced, NOT @Serializable) tracking of the last server-confirmed `row_version`
 * for each expense — the `base_version` the next push hands to the server's `commit_expense` CAS.
 *
 * An expense is "dirty" (has an unsynced local edit) when its local `row_version` differs from
 * [syncedVersion]. Seeded/refreshed on pull (a pulled expense is confirmed at its server version) and
 * updated when a commit succeeds (→ the new version) or is parked (→ the canonical server version).
 * Never leaves the device, so it stays out of [da.chelimo.sharecost.data.remote.supabase.SyncEngine]'s
 * table list and the realtime push trigger.
 */
@Entity(tableName = "expense_sync_state")
data class ExpenseSyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "synced_version") val syncedVersion: Long,
)
