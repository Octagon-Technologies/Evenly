package app.splitevenly.domain.expense

import app.splitevenly.core.id.UserId

/**
 * A change a web guest made to a bill's menu (WEB_CLAIM_SPEC.md §2.7, §3.9.1).
 *
 * **It already happened.** Editing a line moves the bill total and therefore everyone's money, so unlike
 * joining a claim it is *announced* — but it is not held. The payer is told, and anyone on the bill can
 * undo it. Against an honest mistake (which is the whole risk model here: friends splitting a dinner) an
 * undo is worth exactly as much as an approval, and costs nothing in the overwhelming case where the
 * edit was fine.
 *
 * The type keeps its name because the table does, and the table is synced.
 */

/** What the guest changed. Mirrors the `kind` text column; unknown values are ignored. */
enum class PendingEditKind { ADD, RELABEL, REPRICE, REQUANTITY, REMOVE }

/** Where a change stands. [APPLIED] is live and undoable; [UNDONE] is history and stays on screen. */
enum class PendingEditDecision { APPLIED, UNDONE }

/**
 * One change as the "What changed" screen renders it. [previous*] were captured server-side at the
 * moment the change was applied, so the before → after shown is the world it actually replaced.
 *
 * [proposedUnitPriceSubunits] and [previousUnitPriceSubunits] are **per-unit** and exist for display.
 * [previousLineTotalSubunits] is what an undo restores: the line total is the entered source of truth
 * (`domain/AGENTS.md`) and per-unit is a rounded view of it, so rebuilding a $10.00 line over 3 units
 * from the rounded per-unit hands back $9.99.
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
    val previousLineTotalSubunits: Long? = null,
    val decision: PendingEditDecision? = null,
    val decidedAt: Long? = null,
    val decidedBy: UserId? = null,
) {
    /** Still standing, so still undoable. */
    val isLive: Boolean get() = decision != PendingEditDecision.UNDONE

    /** The line total this change produced, or null when it moved no money (a RELABEL). */
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

    /** The line total this change replaced. Exact where the server recorded it. */
    val previousLineTotalOrDerived: Long
        get() = if (kind == PendingEditKind.ADD) 0L
        else previousLineTotalSubunits ?: ((previousUnitPriceSubunits ?: 0L) * (previousQuantity ?: 1))

    /** Signed change to the bill total. Zero when the change touched no money. */
    val totalDeltaSubunits: Long
        get() = proposedLineTotalSubunits?.let { it - previousLineTotalOrDerived } ?: 0L
}
