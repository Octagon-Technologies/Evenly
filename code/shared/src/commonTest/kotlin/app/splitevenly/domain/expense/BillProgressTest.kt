package app.splitevenly.domain.expense

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillProgressTest {
    private val expense =
        Expense(
            ExpenseId("e"),
            GroupId("g"),
            "Dinner",
            6000,
            "USD",
            "2026-09-30",
            UserId("a"),
            null,
            SPLIT_MODE_ITEMIZED,
            "ACTIVE",
            null,
            null,
            UserId("a"),
            0L,
            0L,
        )

    private fun item(
        id: String,
        qty: Int = 1,
    ) = BillItemView(id, id, qty, 1000, 0)

    private fun rec(
        id: String,
        status: ItemStatus,
    ) = ItemReconcile(id, 1, 0, false, status)

    private fun view(
        reconcile: List<ItemReconcile>,
        done: List<Long?>,
    ) = BillView(
        expense = expense,
        items = listOf(item("a"), item("b"), item("c")),
        claims = emptyList(),
        participants = done.mapIndexed { i, d -> BillParticipantView(UserId("u$i"), d) },
        extras = BillExtrasInput(),
        tabByUser = emptyMap(),
        reconcile = reconcile,
    )

    @Test
    fun countsOnlyResolvedItemsAndTreatsOverclaimAsUnfinished() {
        val p =
            view(
                listOf(rec("a", ItemStatus.RESOLVED), rec("b", ItemStatus.OVERCLAIMED), rec("c", ItemStatus.UNCLAIMED)),
                listOf(null),
            ).progress()
        assertEquals(3, p.itemCount)
        assertEquals(1, p.resolvedCount)
        assertEquals(2, p.remainingCount)
        assertFalse(p.allItemsResolved)
    }

    @Test
    fun allItemsAssignedIsSeparateFromEveryoneDone() {
        val all = listOf(rec("a", ItemStatus.RESOLVED), rec("b", ItemStatus.RESOLVED), rec("c", ItemStatus.RESOLVED))
        val p = view(all, listOf(1L, null)).progress()
        assertTrue(p.allItemsResolved)
        assertFalse(p.everyoneDone)
        assertEquals(1, p.doneCount)
        assertTrue(view(all, listOf(1L, 2L)).progress().everyoneDone)
    }

    @Test
    fun itemMissingFromReconcileIsUnresolved() {
        val p = view(listOf(rec("a", ItemStatus.RESOLVED)), emptyList()).progress()
        assertEquals(1, p.resolvedCount)
        assertFalse(p.everyoneDone)
    }
}
