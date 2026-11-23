package app.splitevenly.domain.expense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpenseDateTest {
    private val today = "2026-11-22"

    @Test
    fun chipSaysTodayAndYesterday() {
        assertEquals("Today", expenseDateChipLabel("2026-11-22", today))
        assertEquals("Yesterday", expenseDateChipLabel("2026-11-21", today))
    }

    @Test
    fun chipShowsShortDateAndYearOnlyOutsideThisYear() {
        assertEquals("Nov 20", expenseDateChipLabel("2026-11-20", today))
        assertEquals("Dec 30, 2025", expenseDateChipLabel("2025-12-30", today))
    }

    @Test
    fun yesterdayCrossesAMonthAndYear() {
        assertEquals("Yesterday", expenseDateChipLabel("2025-12-31", "2026-01-01"))
    }

    @Test
    fun longLabelHasDayOfWeek() {
        assertEquals("Sat, Nov 21", expenseDateLongLabel("2026-11-21"))
    }

    @Test
    fun receiptDateInRangeIsTrusted() {
        assertEquals("2026-11-20", trustedReceiptDate("2026-11-20", today))
        assertEquals("2026-11-22", trustedReceiptDate("2026-11-22", today))
        assertEquals("2026-09-23", trustedReceiptDate("2026-09-23", today))
    }

    @Test
    fun futureOldOrGarbageReceiptDatesAreDropped() {
        assertNull(trustedReceiptDate("2026-11-23", today))
        assertNull(trustedReceiptDate("2026-09-22", today))
        assertNull(trustedReceiptDate("20/11/26", today))
        assertNull(trustedReceiptDate(null, today))
    }

    @Test
    fun futureCheckAndShift() {
        assertTrue(isFutureDate("2026-11-23", today))
        assertFalse(isFutureDate(today, today))
        assertEquals("2026-10-31", shiftIsoDate("2026-11-01", -1))
    }
}
