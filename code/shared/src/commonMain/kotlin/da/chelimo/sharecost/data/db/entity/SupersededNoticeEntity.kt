package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local (NOT synced, NOT @Serializable) one-sided "your split edit was superseded" notice.
 *
 * Track F replaces the old bilateral "you both edited this" conflict card. When this device pushes a
 * split edit that lost the causal guard (its base [split_version] was behind the server's), the
 * `merge_expense` RPC returns `status = 'superseded'` — the server kept its advanced split and logged
 * ours to the append-only `superseded_split_edits` audit. We adopt the canonical split locally and drop
 * a single dismissible notice here, shown ONLY to the person whose edit was set aside ("your change to
 * X was superseded while you were away — review?"). Never shown to anyone else; never a two-sided card.
 *
 * Keyed by [expenseId] (one live notice per expense — a newer supersession just refreshes it). Purely
 * local, so it stays out of [da.chelimo.sharecost.data.remote.supabase.SyncEngine]'s table list.
 */
@Entity(tableName = "superseded_notices")
data class SupersededNoticeEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "dismissed") val dismissed: Boolean = false,
)
