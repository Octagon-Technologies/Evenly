package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Device-local (NOT synced, NOT @Serializable) last-known-pushed fingerprint for one row of one
 * synced table, keyed by ([tableName], [rowId]). [syncedHash] is the row's `.hashCode()` as of the
 * last successful push or pull — a row is dirty (needs pushing) when its current `.hashCode()`
 * differs from this value.
 *
 * Generalizes [ExpenseSyncStateEntity]'s dirty-tracking idea to every other synced table, which had
 * none: [da.chelimo.sharecost.data.remote.supabase.SyncEngine.push] used to blindly re-upsert every
 * local row on every trigger (each local edit + a 60s fallback tick, forever), which re-wrote
 * unchanged rows over and over — each write is a Postgres change fanned out to every connected
 * Realtime client, which is what actually burned the Realtime-message quota. A hash (not
 * `updated_at`) is used because two of these tables (`conflicts`, `expense_edit_conflicts`) carry no
 * `updated_at` but are still mutated locally (resolution fields) after creation.
 */
@Entity(tableName = "row_sync_state", primaryKeys = ["table_name", "row_id"])
data class RowSyncStateEntity(
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    @ColumnInfo(name = "synced_hash") val syncedHash: Int,
)
