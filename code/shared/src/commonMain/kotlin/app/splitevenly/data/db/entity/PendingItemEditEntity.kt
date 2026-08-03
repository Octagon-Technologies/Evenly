package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `pending_item_edits` — a change a **web guest** proposed to a bill's menu, waiting for
 * the payer to approve or reject it one card at a time (WEB_CLAIM_SPEC.md §2.7, §5.2).
 *
 * The asymmetry this table encodes is deliberate and must not be "harmonised": joining someone's claim
 * applies instantly, because it moves two people's money and one of them is at the table; **editing a
 * line changes the bill total and therefore everyone's money**, so only the payer can adjudicate it.
 * Nothing here has touched `expense_items` — the edge function only ever inserts a proposal, so an
 * unapproved ADD has no item row and therefore no claims on it.
 *
 * The `previous_*` columns are captured at proposal time, not read back at decision time, so the
 * before → after the payer sees is what the guest was actually looking at. If the payer repriced the
 * line in between, the card still shows the guest's world and approving simply overwrites.
 *
 * Rows are **never deleted**: a decided edit is the audit trail of who changed what on a shared bill.
 * There is no `deleted_at` column here for the same reason — nothing to soft-delete.
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

    /** The line being changed; null for an ADD, which has no line yet. */
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

    @ColumnInfo(name = "previous_unit_price_subunits")
    val previousUnitPriceSubunits: Long? = null,

    @ColumnInfo(name = "proposed_by")
    val proposedBy: String,

    @ColumnInfo(name = "proposed_at")
    val proposedAt: Long,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long? = null,

    @ColumnInfo(name = "decided_by")
    val decidedBy: String? = null,

    /** APPROVED | REJECTED, null while undecided. */
    @ColumnInfo(name = "decision")
    val decision: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
