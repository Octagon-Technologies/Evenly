package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.domain.expense.BillExtrasInput
import da.chelimo.sharecost.domain.expense.EditBill
import da.chelimo.sharecost.domain.expense.EditBillItem
import da.chelimo.sharecost.domain.expense.NewBill
import da.chelimo.sharecost.domain.expense.NewBillItem
import da.chelimo.sharecost.domain.expense.TipSplitMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillRepositoryTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var bills: BillRepositoryImpl

    private val group = GroupId("g1")
    private val me = UserId("a")
    private val bob = UserId("b")
    private val cara = UserId("c")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        bills = BillRepositoryImpl(db.expenseDao(), db.expenseItemDao(), db.itemClaimDao(), db.shareDao(), clockAt("2026-06-28"))
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun newBill(extras: BillExtrasInput = BillExtrasInput()): ExpenseId =
        (bills.createBill(
            NewBill(
                groupId = group,
                title = "Dinner at Tavolo",
                currency = "USD",
                expenseDate = "2026-06-28",
                payerUserId = me,
                createdBy = me,
                items = listOf(
                    NewBillItem("Margherita pizza", quantity = 2, unitPriceSubunits = 1800), // line 3600
                    NewBillItem("Caesar salad", quantity = 1, unitPriceSubunits = 1200),      // line 1200
                ),
                extras = extras,
            ),
        ) as AppResult.Ok).value

    private suspend fun owed(expenseId: ExpenseId): Map<String, Long> =
        db.shareDao().getByExpense(expenseId.value).associate { it.userId to it.shareOwedSubunits }

    private suspend fun itemId(expenseId: ExpenseId, label: String): String =
        db.expenseItemDao().getByExpense(expenseId.value).first { it.label == label }.id

    @Test
    fun newBill_hasNoSharesUntilClaimed() = runTest {
        val bill = newBill()
        assertTrue(owed(bill).isEmpty(), "nothing claimed yet → no derived shares")
        // The expense amount is the full bill total even with nothing claimed.
        assertEquals(4800L, db.expenseDao().getById(bill.value)!!.amountSubunits)
    }

    @Test
    fun claims_deriveShares_exactly() = runTest {
        val bill = newBill()
        val pizza = itemId(bill, "Margherita pizza")
        val salad = itemId(bill, "Caesar salad")
        bills.setClaim(bill, pizza, me, 1)   // 1 of 2 → 1800
        bills.setClaim(bill, pizza, bob, 1)  // 1 of 2 → 1800
        bills.setClaim(bill, salad, me, 1)   // → 1200

        assertEquals(mapOf("a" to 3000L, "b" to 1800L), owed(bill))
    }

    @Test
    fun unclaim_removesTheShare() = runTest {
        val bill = newBill()
        val salad = itemId(bill, "Caesar salad")
        bills.setClaim(bill, salad, bob, 1)
        assertEquals(mapOf("b" to 1200L), owed(bill))

        bills.setClaim(bill, salad, bob, 0) // un-claim
        assertTrue(owed(bill).isEmpty())
        assertNull(db.itemClaimDao().getActiveClaim(salad, bob.value), "claim is tombstoned, not active")
    }

    @Test
    fun taxGratuityProportional_tipEven() = runTest {
        val bill = newBill(
            BillExtrasInput(taxSubunits = 800, gratuitySubunits = 1200, tipSubunits = 1000, tipSplitMode = TipSplitMode.EVEN),
        )
        val pizza = itemId(bill, "Margherita pizza")
        val salad = itemId(bill, "Caesar salad")
        // me: pizza ×1 (1800) + salad (1200) = 3000 ; bob: pizza ×1 = 1800 ; subtotal 4800
        bills.setClaim(bill, pizza, me, 1)
        bills.setClaim(bill, salad, me, 1)
        bills.setClaim(bill, pizza, bob, 1)

        val result = owed(bill)
        // tax+gratuity = 2000 proportional (me 3000/4800, bob 1800/4800) → me 1250, bob 750
        // tip 1000 even → 500 / 500
        assertEquals(3000L + 1250L + 500L, result["a"]) // 4750
        assertEquals(1800L + 750L + 500L, result["b"])  // 3050
        assertEquals(4800L + 2000L + 1000L, result.values.sum())
    }

    @Test
    fun editingPrice_reDerives_withoutDisturbingClaims_andKeepsShareId() = runTest {
        val bill = newBill()
        val pizza = itemId(bill, "Margherita pizza")
        bills.setClaim(bill, pizza, me, 1)
        bills.setClaim(bill, pizza, bob, 1)
        assertEquals(mapOf("a" to 1800L, "b" to 1800L), owed(bill))

        val meShareIdBefore = db.shareDao().getByExpense(bill.value).first { it.userId == "a" }.id
        assertEquals("${bill.value}__a", meShareIdBefore, "deterministic share id")

        // Raise the pizza price; keep the item id so claims stay attached.
        bills.editBill(
            bill,
            EditBill(
                title = "Dinner at Tavolo",
                expenseDate = "2026-06-28",
                payerUserId = me,
                items = listOf(EditBillItem(id = pizza, label = "Margherita pizza", quantity = 2, unitPriceSubunits = 2000)),
                extras = BillExtrasInput(),
            ),
        )

        // Claims survived; shares re-derived to the new price; the share row id is unchanged so any
        // settlement allocation pointing at it stays linked.
        assertEquals(2, db.itemClaimDao().getByExpense(bill.value).size, "both claims survive the edit")
        assertEquals(mapOf("a" to 2000L, "b" to 2000L), owed(bill))
        assertEquals(meShareIdBefore, db.shareDao().getByExpense(bill.value).first { it.userId == "a" }.id)
    }

    @Test
    fun removingAnItem_tombstonesItsClaims() = runTest {
        val bill = newBill()
        val pizza = itemId(bill, "Margherita pizza")
        val salad = itemId(bill, "Caesar salad")
        bills.setClaim(bill, pizza, me, 1)
        bills.setClaim(bill, salad, cara, 1)

        // Edit the bill down to just the pizza — the salad (and Cara's claim on it) should disappear.
        bills.editBill(
            bill,
            EditBill(
                title = "Dinner at Tavolo",
                expenseDate = "2026-06-28",
                payerUserId = me,
                items = listOf(EditBillItem(id = pizza, label = "Margherita pizza", quantity = 2, unitPriceSubunits = 1800)),
                extras = BillExtrasInput(),
            ),
        )
        assertNull(db.itemClaimDao().getActiveClaim(salad, cara.value), "Cara's claim on the removed item is gone")
        assertEquals(mapOf("a" to 1800L), owed(bill), "only the pizza claim remains")
    }

    @Test
    fun observeBill_exposesItemsClaimsAndDerivedTab() = runTest {
        val bill = newBill()
        val pizza = itemId(bill, "Margherita pizza")
        bills.setClaim(bill, pizza, bob, 2) // both pizzas → 3600

        val view = bills.observeBill(bill).first()!!
        assertEquals(2, view.items.size)
        assertEquals(1, view.claims.size)
        assertEquals(3600L, view.tabByUser[bob])
        assertEquals(2, view.claimedQuantityByItem[pizza])
    }
}
