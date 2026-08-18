package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How many free scans a group has spent, cached so the meter renders instantly and offline.
 *
 * **Device-local, never synced** — not `@Serializable`, not in `SyncEngine`'s table list, never pushed
 * (`data/AGENTS.md`, "Device-local tables stay out of sync"). The count lives server-side in
 * `receipt_scan_log`, whose RLS shows a client only its own rows, so the group's total can only come
 * from the `my_group_scan_usage` RPC. This is a cache of that answer, not a second source of truth:
 * enforcement never reads it, and a stale row can only ever mislabel a meter.
 */
@Entity(tableName = "group_scan_usage")
data class GroupScanUsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "free_used")
    val freeUsed: Int,

    @ColumnInfo(name = "free_limit")
    val freeLimit: Int,

    /** When the RPC last answered. The meter uses it to stay quiet rather than show a number it cannot
     *  stand behind: a group that has never been fetched shows no meter at all, not "5 of 5 left". */
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
