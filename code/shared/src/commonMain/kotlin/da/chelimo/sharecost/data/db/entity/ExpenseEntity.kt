package da.chelimo.sharecost.data.db.entity

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
 * `status` is denormalized (02 §6). Postgres maintains it by trigger; locally we recompute it in
 * app code on insert/update (02 §7.5) via [da.chelimo.sharecost.data.db.computeExpenseStatus].
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
)
