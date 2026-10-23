package app.splitevenly.domain.expense

/**
 * How far a bill's picking has got, counted two separate ways that must never be merged: **items** that
 * are fully and correctly assigned (`ItemStatus.RESOLVED`), and **people** who have marked themselves
 * done. A bill can have every line assigned while someone is still choosing, and vice versa.
 */
data class BillProgress(
    val itemCount: Int,
    val resolvedCount: Int,
    val peopleCount: Int,
    val doneCount: Int,
) {
    val remainingCount: Int get() = itemCount - resolvedCount
    val allItemsResolved: Boolean get() = itemCount > 0 && resolvedCount == itemCount
    val everyoneDone: Boolean get() = peopleCount > 0 && doneCount == peopleCount
    val fraction: Float get() = if (itemCount == 0) 0f else resolvedCount.toFloat() / itemCount
}

/** Counts from the current reconcile, never from local chip state. A line missing from it is unresolved. */
fun BillView.progress(): BillProgress {
    val statusById = reconcile.associate { it.itemId to it.status }
    return BillProgress(
        itemCount = items.size,
        resolvedCount = items.count { statusById[it.id] == ItemStatus.RESOLVED },
        peopleCount = participants.size,
        doneCount = participants.count { it.doneAt != null },
    )
}

/** A line that still needs someone, with the food money on it nobody holds yet. */
data class UnresolvedLine(
    val itemId: String,
    val label: String,
    val quantity: Int,
    val assignedUnits: Int,
    val status: ItemStatus,
    val unassignedSubunits: Long,
)

/**
 * Every non-RESOLVED line in receipt order. The unassigned amount is the line total minus what the
 * engine already allocated on it ([BillView.perItemByUser]), so it never drifts from the allocator.
 */
fun BillView.unresolvedLines(): List<UnresolvedLine> {
    val statusById = reconcile.associate { it.itemId to it.status }
    val assigned = assignedQuantityByItem
    val byId = items.associateBy { it.id }
    return receiptOrder().mapNotNull { id ->
        val item = byId[id] ?: return@mapNotNull null
        val status = statusById[id] ?: ItemStatus.UNCLAIMED
        if (status == ItemStatus.RESOLVED) return@mapNotNull null
        val held = perItemByUser[id]?.values?.sum() ?: 0L
        UnresolvedLine(id, item.label, item.quantity, assigned[id] ?: 0, status, (item.lineTotalSubunits - held).coerceAtLeast(0L))
    }
}
