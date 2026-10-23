package app.splitevenly.ui.screen.bill

import app.splitevenly.domain.expense.ItemStatus

/** A person the bill is for. */
data class ClaimParticipantUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
)

/** One assignment on a line: a solo claim ([portionId] == null) or a shared slice ([portionId] set). */
data class AssignRowUi(
    val portionId: String?,
    val memberIds: List<String>,
    val memberNames: List<String>,
    val quantity: Int,
    val amountSubunits: Long,
) {
    val isShared: Boolean get() = memberIds.size >= 2
    val perHeadSubunits: Long get() = if (memberIds.isEmpty()) amountSubunits else amountSubunits / memberIds.size
}

/** One line on the assign screen, as who-had-what. */
data class ClaimItemUi(
    val id: String,
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
    val rows: List<AssignRowUi>,
    val status: ItemStatus,
    // 1-based position on the receipt. Stays on the card after it moves to the completed section.
    val number: Int = 0,
) {
    val assignedQty: Int get() = rows.sumOf { it.quantity }
    val leftQty: Int get() = (quantity - assignedQty).coerceAtLeast(0)
    val assignedMemberIds: Set<String> get() = rows.flatMapTo(HashSet()) { it.memberIds }

    /** Flattens [rows] into one member-set per physical unit ("serving"). Rows are ordered by the slot
     *  index encoded in their portionId (`"<itemId>__slot<N>"`, written by the servings sheet) rather
     *  than arrival order — the DB read order isn't guaranteed to match, and getting this wrong silently
     *  scrambles which serving shows which names. Anything not in that shape (legacy data) sorts last.
     *  This is purely a display/edit convenience: every write from the sheet re-derives a fresh
     *  one-portion-per-serving shape, so real data converges on the slot-indexed form after one edit. */
    fun servingSlots(): List<Set<String>> {
        val ordered =
            rows.sortedBy { row ->
                row.portionId?.let {
                    SlotSuffix
                        .matchEntire(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }
                    ?: Int.MAX_VALUE
            }
        val slots = ArrayList<Set<String>>(quantity)
        for (row in ordered) repeat(row.quantity) { if (slots.size < quantity) slots.add(row.memberIds.toSet()) }
        while (slots.size < quantity) slots.add(emptySet())
        return slots
    }

    private companion object {
        val SlotSuffix = Regex(".*__slot(\\d+)$")
    }
}
