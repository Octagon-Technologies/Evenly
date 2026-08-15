package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `expenses` (02 §3.7). `amount_subunits` is always positive; a refund's "negative"
 * meaning lives in `kind` + inverted participants, not a sign (02 §3.7 invariant).
 *
 * `expense_date` is the local **calendar** date, stored as an ISO-8601 `TEXT` (e.g. "2026-06-08")
 * so it never drifts across time zones — unlike the epoch-ms `Long` used for true timestamps.
 *
 * `status` only ever holds ACTIVE or DELETED. It is NOT a settlement state: a share's `remaining` is
 * derived on read from the non-voided settlement allocations, and an expense is settled iff every
 * share's derived remaining is 0 (`data/AGENTS.md`). There is no stored settled flag to go stale, and
 * `computeExpenseStatus` — which the old wording here pointed at — has never existed.
 */
@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["group_id", "expense_date"]),
        Index(value = ["status", "group_id", "expense_date"]),
        Index(value = ["payer_user_id"]),
        Index(value = ["refund_of_expense_id"]),
    ],
)
@Serializable
data class ExpenseEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "kind")
    val kind: String = "EXPENSE",

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "amount_subunits")
    val amountSubunits: Long,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "expense_date")
    val expenseDate: String,

    @ColumnInfo(name = "payer_user_id")
    val payerUserId: String? = null,

    @ColumnInfo(name = "payer_outside_name")
    val payerOutsideName: String? = null,

    @ColumnInfo(name = "split_mode")
    val splitMode: String,

    @ColumnInfo(name = "has_tax_row")
    val hasTaxRow: Boolean = false,

    @ColumnInfo(name = "tax_subunits")
    val taxSubunits: Long = 0,

    @ColumnInfo(name = "tip_subunits")
    val tipSubunits: Long = 0,

    @ColumnInfo(name = "tip_split_mode")
    val tipSplitMode: String = "PROPORTIONAL",

    @ColumnInfo(name = "gratuity_subunits")
    val gratuitySubunits: Long = 0,

    @ColumnInfo(name = "discount_subunits")
    val discountSubunits: Long = 0,

    // Printed charges with no other slot: a delivery fee, bottle deposit, bag fee, card surcharge. Splits
    // proportionally like tax. Landed server-side first (2026-08-08) — the full-row upsert sends every
    // field, so a column the server lacks breaks ALL expense sync, not just this one value.
    @ColumnInfo(name = "other_charges_subunits", defaultValue = "0")
    val otherChargesSubunits: Long = 0,

    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,

    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String? = null,

    @ColumnInfo(name = "refund_of_expense_id")
    val refundOfExpenseId: String? = null,

    @ColumnInfo(name = "is_auto_refund")
    val isAutoRefund: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = "ACTIVE",

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    // Who wrote the canonical version (server-maintained by commit_expense). Surfaced as the "winner"
    // of a parked edit conflict so the UI can say whose change is currently saved. The client never sets
    // it authoritatively — it's stamped server-side on each commit and read back on pull.
    @ColumnInfo(name = "last_editor")
    val lastEditor: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    // ── Track F: zone-aware merge (see the `merge_expense` RPC). ──────────────────────────────────────
    // Zone 1 — per-field last-edited stamps so independent metadata edits coexist (title vs category by
    // two people both survive). Stamped only when THAT field actually changes (see ExpenseRepositoryImpl).
    @ColumnInfo(name = "title_updated_at")
    val titleUpdatedAt: Long? = null,

    @ColumnInfo(name = "notes_updated_at")
    val notesUpdatedAt: Long? = null,

    // Covers category_id + subcategory_id (they move together in the editor).
    @ColumnInfo(name = "category_updated_at")
    val categoryUpdatedAt: Long? = null,

    @ColumnInfo(name = "date_updated_at")
    val dateUpdatedAt: Long? = null,

    // Zone 2 — a CAUSAL version guarding the money value as one atomic unit (amount + split_mode + payer +
    // bill-extras + the per-user shares). Local value is (synced split_version + 1) iff the split changed
    // since last sync; the server assigns the canonical number in `merge_expense`.
    @ColumnInfo(name = "split_version")
    val splitVersion: Long = 1,

    @ColumnInfo(name = "split_updated_by")
    val splitUpdatedBy: String? = null,
)
