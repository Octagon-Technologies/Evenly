package app.splitevenly.ui

import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.ExtraField
import app.splitevenly.ui.screen.bill.ScanMergeMode
import app.splitevenly.ui.screen.bill.canAutoSave
import app.splitevenly.ui.screen.bill.editBillItemUi
import app.splitevenly.ui.screen.bill.incompleteItemCount
import app.splitevenly.ui.screen.bill.incompleteItemsNotice
import app.splitevenly.ui.screen.bill.leaveNotice
import app.splitevenly.ui.screen.bill.mergeScan
import app.splitevenly.ui.screen.bill.savableItems
import app.splitevenly.ui.screen.bill.submitRows
import app.splitevenly.ui.screen.bill.withExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillEditLogicTest {
    private fun item(label: String, cents: Long) = editBillItemUi(null, label, 1, cents)

    private fun state(items: List<app.splitevenly.ui.screen.bill.EditBillItemUi>, tax: String = "") =
        EditBillState("Dinner", items, tax, "", "", "", "")

    @Test
    fun incompleteRowsAreCountedButBlankRowsAreNot() {
        val items = listOf(item("Pasta", 1800), item("", 0), item("", 500), item("Salad", 0))
        assertEquals(2, incompleteItemCount(items))
        assertEquals(listOf("Pasta"), savableItems(items).map { it.label })
    }

    @Test
    fun autoSaveNeedsNameAndOneCompleteItem() {
        assertFalse(canAutoSave("", listOf(item("Pasta", 1800))))
        assertFalse(canAutoSave("Dinner", listOf(item("Pasta", 0))))
        assertTrue(canAutoSave("Dinner", listOf(item("Pasta", 1800), item("", 0))))
    }

    @Test
    fun noticeCopyPluralises() {
        assertNull(incompleteItemsNotice(0))
        assertEquals("1 item has no name or price. Fix it or remove it.", incompleteItemsNotice(1))
        assertEquals("3 items have no name or price. Fix them or remove them.", incompleteItemsNotice(3))
    }

    @Test
    fun addAppendsItemsAndKeepsExtrasAsSuggestions() {
        val current = state(listOf(item("Pasta", 1800), item("", 0)), tax = "2.00")
        val scanned = state(listOf(item("Wine", 2500)), tax = "2.50")
        val r = mergeScan(current, scanned, ScanMergeMode.ADD)
        assertEquals(listOf("Pasta", "Wine"), r.state.items.map { it.label })
        assertEquals("2.00", r.state.taxText)
        assertEquals(1, r.suggestions.size)
        assertEquals("2.50", r.state.withExtra(r.suggestions.single()).taxText)
        assertEquals(ExtraField.TAX, r.suggestions.single().field)
    }

    @Test
    fun addSkipsIdenticalExtras() {
        val r = mergeScan(state(listOf(item("A", 100)), "2.00"), state(listOf(item("B", 100)), "2"), ScanMergeMode.ADD)
        assertTrue(r.suggestions.isEmpty())
    }

    @Test
    fun replaceSwapsEverythingAndAddOnEmptyBillBehavesLikeReplace() {
        val scanned = state(listOf(item("Wine", 2500)), tax = "2.50")
        val replaced = mergeScan(state(listOf(item("Pasta", 1800)), "2.00"), scanned, ScanMergeMode.REPLACE)
        assertEquals(listOf("Wine"), replaced.state.items.map { it.label })
        assertEquals("2.50", replaced.state.taxText)
        val onEmpty = mergeScan(state(listOf(item("", 0))), scanned, ScanMergeMode.ADD)
        assertEquals(listOf("Wine"), onEmpty.state.items.map { it.label })
        assertTrue(onEmpty.suggestions.isEmpty())
    }

    @Test
    fun incompleteSavedRowIsWrittenAsItsLastSavedVersion() {
        val saved = editBillItemUi("a", "Pasta", 1, 1800)
        val retyping = saved.copy(label = "")
        val rows = submitRows(listOf(retyping, item("", 0), item("Wine", 2500)), mapOf("a" to saved))
        assertEquals(listOf(0, 2), rows.map { it.first })
        assertEquals("Pasta", rows[0].second.label)
    }

    @Test
    fun leaveNoticeCoversMissingNameAndIncompleteRows() {
        assertNull(leaveNotice("Dinner", listOf(item("Pasta", 1800))))
        assertNull(leaveNotice("", listOf(item("", 0))))
        assertEquals("Give the bill a name or it won't be saved.", leaveNotice("", listOf(item("Pasta", 1800))))
        assertEquals("1 item has no name or price. Fix it or remove it.", leaveNotice("Dinner", listOf(item("Pasta", 0))))
        assertEquals("Add at least one item or this bill won't be saved.", leaveNotice("Dinner", listOf(item("", 0))))
        assertEquals("Add at least one item or this bill won't be saved.", leaveNotice("Dinner", emptyList()))
    }
}
