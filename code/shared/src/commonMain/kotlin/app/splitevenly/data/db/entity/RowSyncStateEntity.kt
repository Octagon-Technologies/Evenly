package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Device-local (NOT synced, NOT @Serializable) last-known-pushed fingerprint for one row of one
 * synced table, keyed by ([tableName], [rowId]). [syncedHash] is [rowFingerprint] of the row as of
 * the last successful push or pull — a row is dirty (needs pushing) when its current fingerprint
 * differs from this value.
 *
 * Generalizes [ExpenseSyncStateEntity]'s dirty-tracking idea to every other synced table, which had
 * none: [app.splitevenly.data.remote.supabase.SyncEngine.push] used to blindly re-upsert every
 * local row on every trigger (each local edit + a 60s fallback tick, forever), which re-wrote
 * unchanged rows over and over — each write is a Postgres change fanned out to every connected
 * Realtime client, which is what actually burned the Realtime-message quota. A fingerprint (not
 * `updated_at`) is used because two of these tables (`conflicts`, `expense_edit_conflicts`) carry no
 * `updated_at` but are still mutated locally (resolution fields) after creation.
 */
@Entity(tableName = "row_sync_state", primaryKeys = ["table_name", "row_id"])
data class RowSyncStateEntity(
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    @ColumnInfo(name = "synced_hash") val syncedHash: Long,
)

/**
 * 64-bit content fingerprint for dirty detection: FNV-1a over `toString()` mixed with the structural
 * `hashCode()`. A bare 32-bit `hashCode()` was the whole fingerprint once, and a ~2⁻³² per-edit chance
 * of "edited row reads as already pushed" is a silent, permanent, unrepairable un-synced row — the
 * next edit re-bases from the same stale value, so nothing ever notices.
 *
 * The second hash must be from a DIFFERENT family, not just wider: `toString().hashCode()` looked
 * independent and is not — Kotlin's base-31 polynomial cancels identically when an edit swaps a
 * same-length, equal-hash substring in place ("Aa" → "BB"), which is exactly when the data-class
 * `hashCode()` collides too. `RowFingerprintTest` pins that case; FNV-1a's xor-then-multiply has no
 * such structural cancellation. The structural `hashCode()` stays mixed in because `toString()` alone
 * can be ambiguous (a String field can render the `, field=` separator).
 */
fun rowFingerprint(row: Any): Long = (fnv1a64(row.toString()) xor (row.hashCode().toULong() shl 32)).toLong()

private fun fnv1a64(s: String): ULong {
    var h = 0xCBF29CE484222325uL
    for (c in s) {
        h = h xor c.code.toULong()
        h *= 0x100000001B3uL
    }
    return h
}
