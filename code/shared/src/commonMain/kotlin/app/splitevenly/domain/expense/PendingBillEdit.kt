package app.splitevenly.domain.expense

import app.splitevenly.core.id.UserId

/**
 * A change a web guest proposed to a bill's menu, waiting on the payer (WEB_CLAIM_SPEC.md §2.7, §3.9.1).
 *
 * Editing a line moves the bill total and therefore *everyone's* money, so unlike joining a claim it
 * never applies on its own. Each proposal is decided individually — there is deliberately no
 * "approve all", because a payer who approves three cards in one tap has reviewed none of them.
 */

/** What the guest wants to change. Mirrors the `kind` text column; unknown values are ignored. */
enum class PendingEditKind { ADD, RELABEL, REPRICE, REQUANTITY, REMOVE }

/** The payer's verdict. Absent while the proposal is still waiting. */
enum class PendingEditDecision { APPROVED, REJECTED }

/**
 * One proposal as the review screen renders it. [previous*] were captured when the guest proposed, so the
 * before → after shown is the world the guest was actually looking at.
 *
 * [proposedUnitPriceSubunits] is a **per-unit** price (that is what the web editor collects); the bill
 * itself stores a line total, so applying a REPRICE multiplies by the quantity that survives the change.
 */
data class PendingBillEdit(
    val id: String,
    val expenseId: String,
    val itemId: String?,
    val kind: PendingEditKind,
    val proposedBy: UserId,
    val proposedAt: Long,
    val proposedLabel: String? = null,
    val proposedQuantity: Int? = null,
    val proposedUnitPriceSubunits: Long? = null,
    val previousLabel: String? = null,
    val previousQuantity: Int? = null,
    val previousUnitPriceSubunits: Long? = null,
    val decision: PendingEditDecision? = null,
    val decidedAt: Long? = null,
    val decidedBy: UserId? = null,
) {
    val isPending: Boolean get() = decision == null

    /** The line total this proposal would produce, or null when it does not change the money (a RELABEL,
     *  or a proposal missing the number it needs). A REMOVE is worth its previous total, negated. */
    val proposedLineTotalSubunits: Long?
        get() = when (kind) {
            PendingEditKind.ADD -> proposedUnitPriceSubunits?.let { it * (proposedQuantity ?: 1) }
            PendingEditKind.REPRICE -> proposedUnitPriceSubunits?.let { it * (proposedQuantity ?: previousQuantity ?: 1) }
            PendingEditKind.REQUANTITY -> proposedQuantity?.let { qty ->
                (proposedUnitPriceSubunits ?: previousUnitPriceSubunits)?.let { it * qty }
            }
            PendingEditKind.REMOVE -> 0L
            PendingEditKind.RELABEL -> null
        }

    /** The line total this proposal replaces, for the "takes the total to X" arithmetic. */
    val previousLineTotalSubunits: Long
        get() = if (kind == PendingEditKind.ADD) 0L
        else (previousUnitPriceSubunits ?: 0L) * (previousQuantity ?: 1)

    /** Signed change to the bill total if approved. Zero when the proposal touches no money. */
    val totalDeltaSubunits: Long
        get() = proposedLineTotalSubunits?.let { it - previousLineTotalSubunits } ?: 0L
}
