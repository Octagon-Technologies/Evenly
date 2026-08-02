package app.splitevenly.domain.expense

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId

/**
 * "Split the bill" domain models — the itemized-expense flow. A bill is an expense with `split_mode =
 * "ITEMIZED"`: its line items live in `expense_items`, who-had-what in `item_claims`, and the bill-level
 * extras (tax/gratuity/tip/discount) on the expense row. Each participant's owed share is **derived**
 * from those via [splitBill]; it is never typed in directly.
 */

const val SPLIT_MODE_ITEMIZED: String = "ITEMIZED"

/** A line on a brand-new bill (no id yet — the repository assigns one). [lineTotalSubunits] is the truth. */
data class NewBillItem(
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
)

/** A line on an edited bill. A null [id] is a freshly-added line; a non-null id updates that line. */
data class EditBillItem(
    val id: String?,
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
)

/** Bill-level surcharges. Tip defaults to an even split (toggleable); tax & gratuity ride proportionally. */
data class BillExtrasInput(
    val taxSubunits: Long = 0L,
    val gratuitySubunits: Long = 0L,
    val tipSubunits: Long = 0L,
    val tipSplitMode: TipSplitMode = TipSplitMode.EVEN,
    val discountSubunits: Long = 0L,
)

/** Input to create a bill: the menu + extras + who it's for. Claims arrive later, live. */
data class NewBill(
    val groupId: GroupId,
    val title: String,
    val currency: String,
    val expenseDate: String,
    val payerUserId: UserId?,
    val createdBy: UserId,
    val items: List<NewBillItem>,
    val extras: BillExtrasInput = BillExtrasInput(),
    val participantUserIds: List<UserId> = emptyList(),
    val payerOutsideName: String? = null,
    val categoryId: String? = null,
)

/** Input to edit a bill's menu + extras (the "Save bill" action, available any time — even mid-claim). */
data class EditBill(
    val title: String,
    val expenseDate: String,
    val payerUserId: UserId?,
    val items: List<EditBillItem>,
    val extras: BillExtrasInput,
    val participantUserIds: List<UserId> = emptyList(),
    val payerOutsideName: String? = null,
    val editedBy: UserId? = null,
)

/** A line item as shown on the edit/claim screens. [lineTotalSubunits] is the truth; per-unit is derived. */
data class BillItemView(
    val id: String,
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
    val sortOrder: Int,
) {
    /** The **derived** per-unit price for display (the line total shared evenly, rounded). */
    val unitPriceSubunits: Long get() = perUnitSubunits(lineTotalSubunits, quantity)
}

/** One person's active claim on a line. */
data class BillClaimView(
    val id: String,
    val itemId: String,
    val userId: UserId,
    val quantity: Int,
)

/** One person's active membership in a shared *portion* of a line ([portionId] groups a slice's members;
 *  null on legacy rows). [quantity] is the slice's unit count (same across its members). */
data class BillShareView(
    val id: String,
    val itemId: String,
    val userId: UserId,
    val addedBy: UserId,
    val portionId: String? = null,
    val quantity: Int = 1,
)

/** A person the bill is for. [doneAt] is their "I'm done claiming" stamp (null = still to claim). */
data class BillParticipantView(
    val userId: UserId,
    val doneAt: Long?,
)

/** A group's unresolved "Split the bill" — the home card / unresolved section. */
data class UnresolvedBill(
    val expenseId: ExpenseId,
    val title: String,
    val currency: String,
    val amountSubunits: Long,
    val unclaimedCount: Int,
    val participantCount: Int,
    val stillToClaimCount: Int,
    /** True when the viewer is a participant who hasn't marked done — drives the personal "claim your items" card. */
    val youNeedToClaim: Boolean,
)

/**
 * Everything the claim screen renders: the bill, its items, all live claims, the extras, and the
 * derived "tab" per participant. [unclaimedQuantityByItem] drives the "needs someone" highlighting.
 */
data class BillView(
    val expense: Expense,
    val items: List<BillItemView>,
    val claims: List<BillClaimView>,
    val shares: List<BillShareView> = emptyList(),
    val participants: List<BillParticipantView> = emptyList(),
    val extras: BillExtrasInput,
    val tabByUser: Map<UserId, Long>,
    val tabBreakdownByUser: Map<UserId, TabBreakdown> = emptyMap(),
    val reconcile: List<ItemReconcile> = emptyList(),
    // Per-item food allocation (itemId -> user -> subunits owed for that line) — the assign screen's
    // penny-exact "who pays what" rows, straight from the engine (never recomputed).
    val perItemByUser: Map<String, Map<UserId, Long>> = emptyMap(),
) {
    /** Claimed unit count per item (summed across people) — compare to quantity for "left"/over-claim. */
    val claimedQuantityByItem: Map<String, Int>
        get() = claims.groupBy { it.itemId }.mapValues { (_, cs) -> cs.sumOf { it.quantity } }

    /** Units ASSIGNED per item = solo claims + each shared portion's quantity (counted once). "N of Q left". */
    val assignedQuantityByItem: Map<String, Int>
        get() {
            val out = HashMap<String, Int>()
            claims.forEach { out[it.itemId] = (out[it.itemId] ?: 0) + it.quantity }
            shares.filter { it.portionId != null }
                .groupBy { it.itemId to it.portionId }
                .forEach { (key, rows) -> out[key.first] = (out[key.first] ?: 0) + rows.first().quantity }
            return out
        }

    /** The bill is resolved once every line is fully and correctly claimed. */
    val fullyResolved: Boolean get() = reconcile.isNotEmpty() && reconcile.all { it.status == ItemStatus.RESOLVED }

    /** Lines that still need someone — the "N dishes still need someone" nudge + the unresolved surface. */
    val unclaimedCount: Int get() = reconcile.count { it.status == ItemStatus.UNCLAIMED }
}
