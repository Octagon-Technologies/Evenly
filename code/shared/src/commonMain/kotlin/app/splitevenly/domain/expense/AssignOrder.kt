package app.splitevenly.domain.expense

/**
 * One visit's accordion state on the assign screen: which card is open and the order items were
 * completed in (docs/features/split-by-items-flow.md §3). Screen-session only: never persisted or
 * synced, so a reopened bill starts fresh from [AssignOrder.start].
 *
 * All ids are stable `expense_items.id`s, never labels or list indices, so a remote edit can never
 * make a stale position assign a different line.
 */
data class AssignSession(
    val activeItemId: String?,
    val completed: List<String>,
)

/** What the screen draws: the completed section (completion order), then the rest (receipt order). */
data class AssignLayout(
    val completed: List<String>,
    val unfinished: List<String>,
    val activeItemId: String?,
)

/**
 * The ordering rules, pure so both targets test them. The one rule everything bends around: **the open
 * card never moves under the user's finger.** Its section membership is frozen while it is open, even
 * if an assignment (local or remote) completes or un-completes it, and settles only once focus moves.
 */
object AssignOrder {
    /**
     * A fresh visit: already-complete items in receipt order, the rest after them. Opens [focus] if it
     * still exists, else the first unfinished item (none when everything is done).
     */
    fun start(
        receiptOrder: List<String>,
        resolved: Set<String>,
        focus: String? = null,
    ): AssignSession =
        AssignSession(
            activeItemId = focus?.takeIf { it in receiptOrder } ?: receiptOrder.firstOrNull { it !in resolved },
            completed = receiptOrder.filter { it in resolved },
        )

    /**
     * Fold in the latest bill. Deleted items leave both sections; a completed item that is no longer
     * complete leaves the completed section; a newly complete item joins the bottom of it. The active
     * card is exempt from both moves, and is dropped only if its item was deleted.
     */
    fun reconcile(
        session: AssignSession,
        receiptOrder: List<String>,
        resolved: Set<String>,
    ): AssignSession {
        val exists = receiptOrder.toHashSet()
        val active = session.activeItemId?.takeIf { it in exists }
        val kept = session.completed.filter { it in exists && (it == active || it in resolved) }
        val keptSet = kept.toHashSet()
        val joined = receiptOrder.filter { it in resolved && it !in keptSet && it != active }
        return AssignSession(active, kept + joined)
    }

    /**
     * Move focus to [itemId] (null collapses). The card being left settles first, so a just-completed
     * card lands at the bottom of the completed section before the new one opens.
     */
    fun open(
        session: AssignSession,
        itemId: String?,
        receiptOrder: List<String>,
        resolved: Set<String>,
    ): AssignSession {
        if (itemId == session.activeItemId) return reconcile(session, receiptOrder, resolved)
        val settled = reconcile(session.copy(activeItemId = null), receiptOrder, resolved)
        return settled.copy(activeItemId = itemId?.takeIf { it in receiptOrder })
    }

    fun layout(
        session: AssignSession,
        receiptOrder: List<String>,
    ): AssignLayout {
        val exists = receiptOrder.toHashSet()
        val completed = session.completed.filter { it in exists }
        val completedSet = completed.toHashSet()
        return AssignLayout(
            completed = completed,
            unfinished = receiptOrder.filter { it !in completedSet },
            activeItemId = session.activeItemId?.takeIf { it in exists },
        )
    }
}

/** Receipt order for [AssignOrder]: `sort_order`, with the id as a stable tiebreak. */
fun BillView.receiptOrder(): List<String> = items.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }

/** Items whose current status is RESOLVED. OVERCLAIMED and UNCLAIMED both still need work. */
fun BillView.resolvedItemIds(): Set<String> = reconcile.filter { it.status == ItemStatus.RESOLVED }.mapTo(HashSet()) { it.itemId }
