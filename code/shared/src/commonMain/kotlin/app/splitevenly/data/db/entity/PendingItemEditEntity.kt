package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `pending_item_edits` — the permanent, attributed log of every change a **web guest**
 * made to a bill's menu (WEB_CLAIM_SPEC.md §2.7, §5.2).
 *
 * **Nothing here is pending.** The name is the table's, and the table is synced, so renaming it costs
 * more than the confusion it removes. Every row arrives already `APPLIED`: `apply_web_bill_edit` writes
 * the change to `expense_items` and this row in one transaction. What the payer does with it is Undo.
 *
 * The asymmetry this encodes is deliberate and must not be "harmonised": joining someone's claim moves
 * two people's money with both of them at the table, so nothing is announced; **editing a line changes
 * the bill total and therefore everyone's money**, so it is. Announced, not adjudicated — against an
 * honest mistake an undo is worth as much as an approval and costs nothing when the edit was fine.
 *
 * The `previous_*` columns are captured at APPLY time, server-side, from the row as it then stood: they
 * are what Undo restores. [previousLineTotalSubunits] is the one Undo actually uses;
 * [previousUnitPriceSubunits] is display only, and rebuilding a line total from it would give back
 * $9.99 for a $10.00 line over 3 units.
 *
 * Rows are **never deleted**: this is the audit trail of who changed what on a shared bill, and an undo
 * is another entry in it rather than an erasure. There is no `deleted_at` column for the same reason.
 *
 * Synced (`@Serializable`, snake_case columns 1:1 with Postgres, no Room FK), carries `group_id` for
 * pull scoping plus `row_version` + `updated_at` for the `keepNewer` last-write-wins guard.
 */
@Entity(
    tableName = "pending_item_edits",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class PendingItemEditEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    /** The line this changed. Always set, **including for an ADD**, where it is the line the ADD
     *  created: without it Undo has nothing to target and an added line becomes unremovable. Nullable
     *  only because the column is. */
    @ColumnInfo(name = "item_id")
    val itemId: String? = null,

    /** ADD | RELABEL | REPRICE | REQUANTITY | REMOVE. Text, validated at the domain boundary. */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "proposed_label")
    val proposedLabel: String? = null,

    @ColumnInfo(name = "proposed_quantity")
    val proposedQuantity: Int? = null,

    @ColumnInfo(name = "proposed_unit_price_subunits")
    val proposedUnitPriceSubunits: Long? = null,

    @ColumnInfo(name = "previous_label")
    val previousLabel: String? = null,

    @ColumnInfo(name = "previous_quantity")
    val previousQuantity: Int? = null,

    /** Display only, for "Price each  $18.00 → $20.00". Never rebuild a line total from this. */
    @ColumnInfo(name = "previous_unit_price_subunits")
    val previousUnitPriceSubunits: Long? = null,

    /** What Undo restores. The line total is the entered source of truth (`domain/AGENTS.md`); per-unit
     *  is a rounded view of it, so this is the only value that restores exactly. */
    @ColumnInfo(name = "previous_line_total_subunits")
    val previousLineTotalSubunits: Long? = null,

    @ColumnInfo(name = "proposed_by")
    val proposedBy: String,

    @ColumnInfo(name = "proposed_at")
    val proposedAt: Long,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long? = null,

    @ColumnInfo(name = "decided_by")
    val decidedBy: String? = null,

    /** APPLIED (live, undoable) | UNDONE (history). Null only on a row written before this vocabulary,
     *  of which there are none in the wild — verified empty before the change. */
    @ColumnInfo(name = "decision")
    val decision: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
