package app.splitevenly.domain.expense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Acceptance criterion 6 and the §3 ordering rules, one per test. */
class AssignOrderTest {
    private val receipt = listOf("A", "B", "C", "D", "E")

    private fun layoutAfter(session: AssignSession) = AssignOrder.layout(session, receipt)

    @Test
    fun freshVisitGroupsCompletedInReceiptOrderAndOpensFirstUnfinished() {
        val s = AssignOrder.start(receipt, resolved = setOf("C", "A"))
        val l = layoutAfter(s)
        assertEquals(listOf("A", "C"), l.completed)
        assertEquals(listOf("B", "D", "E"), l.unfinished)
        assertEquals("B", l.activeItemId)
    }

    @Test
    fun assignedActiveCardStaysAnchoredThenJoinsBottomWhenAnotherOpens() {
        var s = AssignOrder.start(receipt, setOf("A", "C"))
        // B completes while open: it stays where it is.
        s = AssignOrder.reconcile(s, receipt, setOf("A", "B", "C"))
        assertEquals(listOf("A", "C"), layoutAfter(s).completed)
        assertEquals(listOf("B", "D", "E"), layoutAfter(s).unfinished)
        assertEquals("B", s.activeItemId)
        // Opening D settles B at the bottom of the completed section.
        s = AssignOrder.open(s, "D", receipt, setOf("A", "B", "C"))
        assertEquals(listOf("A", "C", "B"), layoutAfter(s).completed)
        assertEquals(listOf("D", "E"), layoutAfter(s).unfinished)
        assertEquals("D", s.activeItemId)
    }

    @Test
    fun editingACompletedItemThatStaysCompleteKeepsItsPlace() {
        var s = AssignSession(activeItemId = null, completed = listOf("A", "C", "B"))
        s = AssignOrder.open(s, "C", receipt, setOf("A", "B", "C"))
        s = AssignOrder.reconcile(s, receipt, setOf("A", "B", "C"))
        s = AssignOrder.open(s, "D", receipt, setOf("A", "B", "C"))
        assertEquals(listOf("A", "C", "B"), layoutAfter(s).completed)
    }

    @Test
    fun unassignedThenRecompletedItemAppendsAsANewCompletion() {
        var s = AssignSession(activeItemId = "D", completed = listOf("A", "B", "C"))
        // B loses its assignee (e.g. from another device): it leaves the completed section.
        s = AssignOrder.reconcile(s, receipt, setOf("A", "C"))
        assertEquals(listOf("A", "C"), layoutAfter(s).completed)
        assertEquals(listOf("B", "D", "E"), layoutAfter(s).unfinished)
        // It completes again: appended after C, not restored to its old slot.
        s = AssignOrder.reconcile(s, receipt, setOf("A", "B", "C"))
        assertEquals(listOf("A", "C", "B"), layoutAfter(s).completed)
    }

    @Test
    fun activeCompletedCardThatBecomesUnfinishedStaysUntilFocusMoves() {
        var s = AssignOrder.open(AssignSession(null, listOf("A", "C")), "C", receipt, setOf("A", "C"))
        s = AssignOrder.reconcile(s, receipt, setOf("A"))
        assertEquals(listOf("A", "C"), layoutAfter(s).completed)
        s = AssignOrder.open(s, "E", receipt, setOf("A"))
        assertEquals(listOf("A"), layoutAfter(s).completed)
        assertEquals(listOf("B", "C", "D", "E"), layoutAfter(s).unfinished)
    }

    @Test
    fun remoteCompletionOfAnotherItemDoesNotStealFocus() {
        var s = AssignOrder.start(receipt, emptySet())
        assertEquals("A", s.activeItemId)
        s = AssignOrder.reconcile(s, receipt, setOf("D"))
        assertEquals("A", s.activeItemId)
        assertEquals(listOf("D"), layoutAfter(s).completed)
    }

    @Test
    fun reopeningTheBillResetsToReceiptOrder() {
        val reopened = AssignOrder.start(receipt, setOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), layoutAfter(reopened).completed)
        assertEquals("D", reopened.activeItemId)
    }

    @Test
    fun deletedItemsLeaveBothSectionsAndDropFocus() {
        val s = AssignSession(activeItemId = "B", completed = listOf("A", "C"))
        val shorter = listOf("A", "C", "D")
        val r = AssignOrder.reconcile(s, shorter, setOf("A"))
        assertNull(r.activeItemId)
        val l = AssignOrder.layout(r, shorter)
        assertEquals(listOf("A"), l.completed)
        assertEquals(listOf("C", "D"), l.unfinished)
    }

    @Test
    fun newlyAddedItemTakesItsReceiptSlotInUnfinished() {
        val s = AssignOrder.start(listOf("A", "C"), setOf("A"))
        val withB = listOf("A", "B", "C")
        val l = AssignOrder.layout(AssignOrder.reconcile(s, withB, setOf("A")), withB)
        assertEquals(listOf("B", "C"), l.unfinished)
    }

    @Test
    fun focusRequestOpensThatItemEvenIfComplete() {
        val s = AssignOrder.start(receipt, setOf("A", "B"), focus = "B")
        assertEquals("B", s.activeItemId)
        assertEquals(listOf("A", "B"), layoutAfter(s).completed)
    }

    @Test
    fun everythingDoneOpensNothing() {
        assertNull(AssignOrder.start(receipt, receipt.toSet()).activeItemId)
    }

    @Test
    fun collapsingSettlesTheCard() {
        var s = AssignOrder.start(receipt, emptySet())
        s = AssignOrder.open(s, null, receipt, setOf("A"))
        assertNull(s.activeItemId)
        assertEquals(listOf("A"), layoutAfter(s).completed)
    }
}
