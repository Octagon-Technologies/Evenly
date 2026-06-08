package da.chelimo.sharecost.data.db

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic tests for [computeExpenseStatus] (02 §6/§7.5, AC-INV-003). No DB needed, so this runs
 * on every target via commonTest.
 */
class ExpenseStatusTest {

    @Test
    fun deleted_winsOverEverything() {
        assertEquals(ExpenseStatus.DELETED, computeExpenseStatus(deletedAt = 5L, sumRemainingSubunits = 0L))
        assertEquals(ExpenseStatus.DELETED, computeExpenseStatus(deletedAt = 5L, sumRemainingSubunits = 999L))
    }

    @Test
    fun zeroRemaining_isSettled() {
        assertEquals(ExpenseStatus.SETTLED, computeExpenseStatus(deletedAt = null, sumRemainingSubunits = 0L))
    }

    @Test
    fun positiveRemaining_isActive() {
        assertEquals(ExpenseStatus.ACTIVE, computeExpenseStatus(deletedAt = null, sumRemainingSubunits = 1L))
    }
}
