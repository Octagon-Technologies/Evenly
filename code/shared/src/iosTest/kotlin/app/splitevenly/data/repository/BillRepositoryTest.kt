package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.EditBillItem
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import app.splitevenly.domain.expense.TipSplitMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillRepositoryTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var bills: BillRepositoryImpl

    private val group = GroupId("g1")
    private val me = UserId("a")
    private val bob = UserId("b")
    private val cara = UserId("c")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        bills =
            BillRepositoryImpl(
                db.expenseDao(),
                db.expenseItemDao(),
                db.itemClaimDao(),
                db.itemShareDao(),
                db.billParticipantDao(),
                db.shareDao(),
                db.billWriteDao(),
                clockAt("2026-06-28"),
            )
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun newBill(extras: BillExtrasInput = BillExtrasInput()): ExpenseId =
        (
            bills.createBill(
                NewBill(
                    groupId = group,
                    title = "Dinner at Tavolo",
                    currency = "USD",
                    expenseDate = "2026-06-28",
                    payerUserId = me,
                    createdBy = me,
                    items =
                        listOf(
                            NewBillItem("Margherita pizza", quantity = 2, lineTotalSubunits = 3600), // 2 × $18
                            NewBillItem("Caesar salad", quantity = 1, lineTotalSubunits = 1200),
                        ),
                    extras = extras,
                ),
            ) as AppResult.Ok
        ).value

    private suspend fun owed(expenseId: ExpenseId): Map<String, Long> =
        db.shareDao().getByExpense(expenseId.value).associate { it.userId to it.shareOwedSubunits }

    private suspend fun itemId(
        expenseId: ExpenseId,
        label: String,
    ): String =
        db
            .expenseItemDao()
            .getByExpense(expenseId.value)
            .first { it.label == label }
            .id

    @Test
    fun newBill_hasNoSharesUntilClaimed() =
        runTest {
            val bill = newBill()
            assertTrue(owed(bill).isEmpty(), "nothing claimed yet → no derived shares")
            // The expense amount is the full bill total even with nothing claimed.
            assertEquals(4800L, db.expenseDao().getById(bill.value)!!.amountSubunits)
        }

    @Test
    fun claims_deriveShares_exactly() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            val salad = itemId(bill, "Caesar salad")
            bills.setClaim(bill, pizza, me, 1) // 1 of 2 → 1800
            bills.setClaim(bill, pizza, bob, 1) // 1 of 2 → 1800
            bills.setClaim(bill, salad, me, 1) // → 1200

            assertEquals(mapOf("a" to 3000L, "b" to 1800L), owed(bill))
        }

    @Test
    fun unclaim_removesTheShare() =
        runTest {
            val bill = newBill()
            val salad = itemId(bill, "Caesar salad")
            bills.setClaim(bill, salad, bob, 1)
            assertEquals(mapOf("b" to 1200L), owed(bill))

            bills.setClaim(bill, salad, bob, 0) // un-claim
            assertTrue(owed(bill).isEmpty())
            assertNull(db.itemClaimDao().getActiveClaim(salad, bob.value), "claim is tombstoned, not active")
        }

    @Test
    fun taxGratuityProportional_tipEven() =
        runTest {
            val bill =
                newBill(
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
            assertEquals(1800L + 750L + 500L, result["b"]) // 3050
            assertEquals(4800L + 2000L + 1000L, result.values.sum())
        }

    @Test
    fun editingPrice_reDerives_withoutDisturbingClaims_andKeepsShareId() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            bills.setClaim(bill, pizza, me, 1)
            bills.setClaim(bill, pizza, bob, 1)
            assertEquals(mapOf("a" to 1800L, "b" to 1800L), owed(bill))

            val meShareIdBefore =
                db
                    .shareDao()
                    .getByExpense(bill.value)
                    .first { it.userId == "a" }
                    .id
            assertEquals("${bill.value}__a", meShareIdBefore, "deterministic share id")

            // Raise the pizza price; keep the item id so claims stay attached.
            bills.editBill(
                bill,
                EditBill(
                    title = "Dinner at Tavolo",
                    expenseDate = "2026-06-28",
                    payerUserId = me,
                    items = listOf(EditBillItem(id = pizza, label = "Margherita pizza", quantity = 2, lineTotalSubunits = 4000)),
                    extras = BillExtrasInput(),
                ),
            )

            // Claims survived; shares re-derived to the new price; the share row id is unchanged so any
            // settlement allocation pointing at it stays linked.
            assertEquals(2, db.itemClaimDao().getByExpense(bill.value).size, "both claims survive the edit")
            assertEquals(mapOf("a" to 2000L, "b" to 2000L), owed(bill))
            assertEquals(
                meShareIdBefore,
                db
                    .shareDao()
                    .getByExpense(bill.value)
                    .first { it.userId == "a" }
                    .id,
            )
        }

    @Test
    fun removingAnItem_tombstonesItsClaims() =
        runTest {
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
                    items = listOf(EditBillItem(id = pizza, label = "Margherita pizza", quantity = 2, lineTotalSubunits = 3600)),
                    extras = BillExtrasInput(),
                ),
            )
            assertNull(db.itemClaimDao().getActiveClaim(salad, cara.value), "Cara's claim on the removed item is gone")
            assertEquals(mapOf("a" to 1800L), owed(bill), "only the pizza claim remains")
        }

    @Test
    fun observeBill_exposesItemsClaimsAndDerivedTab() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            bills.setClaim(bill, pizza, bob, 2) // both pizzas → 3600

            val view = bills.observeBill(bill).first()!!
            assertEquals(2, view.items.size)
            assertEquals(1, view.claims.size)
            assertEquals(3600L, view.tabByUser[bob])
            assertEquals(2, view.claimedQuantityByItem[pizza])
        }

    /**
     * P1 #15: `setServings` re-slices an item in ONE atomic teardown+rebuild. Here the pizza (2 × $18) is
     * first split one-unit-each (me, bob), then fully replaced by a shared serving (me+bob on one unit)
     * plus a solo (cara on the other). The old assignment must be gone and the shares re-derived cleanly.
     */
    @Test
    fun setServings_atomicallyReslicesTheItem() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza") // qty 2, $18/unit

            bills.setServings(bill, pizza, listOf(listOf(me), listOf(bob)), addedBy = me)
            assertEquals(mapOf("a" to 1800L, "b" to 1800L), owed(bill), "two solo servings")

            // Fully replace: unit 1 shared by me+bob ($9 each), unit 2 solo cara ($18). Old rows torn down.
            bills.setServings(bill, pizza, listOf(listOf(me, bob), listOf(cara)), addedBy = me)
            assertEquals(mapOf("a" to 900L, "b" to 900L, "c" to 1800L), owed(bill), "reslice replaces cleanly")
        }

    /**
     * Taking someone off the bill has to take their money with them. A bill's owed amounts derive from
     * claims, so tombstoning the roster row alone left the person off the list and still paying for their
     * dishes — the "we added them by mistake" case.
     */
    @Test
    fun takingSomeoneOffTheBill_clearsTheirClaimsAndTheirMoney() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            val salad = itemId(bill, "Caesar salad")
            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob, cara)))
            bills.setClaim(bill, pizza, me, 1)
            bills.setClaim(bill, pizza, cara, 1)
            bills.setClaim(bill, salad, cara, 1)
            assertEquals(mapOf("a" to 1800L, "c" to 3000L), owed(bill), "Cara is on a pizza and the salad")

            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob)))

            assertEquals(mapOf("a" to 1800L), owed(bill), "Cara owes nothing once she is off the bill")
            assertNull(db.itemClaimDao().getActiveClaim(pizza, cara.value), "her pizza claim is tombstoned")
            assertNull(db.itemClaimDao().getActiveClaim(salad, cara.value), "her salad claim is tombstoned")
            val view = bills.observeBill(bill).first()!!
            assertEquals(1, view.assignedQuantityByItem[pizza], "the unit she held needs someone again")
        }

    /** A slice she shared survives with its remaining member; a slice she held alone frees its units. */
    @Test
    fun takingSomeoneOffTheBill_leavesTheRestOfASharedSlice() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza") // qty 2, $18/unit
            val salad = itemId(bill, "Caesar salad")
            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob, cara)))
            bills.setPortion(bill, pizza, portionId = "p1", memberIds = listOf(bob, cara), quantity = 1, addedBy = me)
            bills.setPortion(bill, pizza, portionId = "p2", memberIds = listOf(cara), quantity = 1, addedBy = me)
            assertEquals(mapOf("b" to 900L, "c" to 2700L), owed(bill))

            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob)))

            assertEquals(mapOf("b" to 1800L), owed(bill), "Bob now has the shared unit to himself")
            val view = bills.observeBill(bill).first()!!
            assertEquals(1, view.assignedQuantityByItem[pizza], "the unit Cara held alone is back to needing someone")
        }

    // ── Atomicity of the bill writes (R4, R8) ────────────────────────────────────────────────────
    //
    // The end states above are the same either way — a test cannot observe a half-written bill, which is
    // exactly why the review found this by reading. What these two pin is the *shape*: both writes go
    // through the one `BillWriteDao` transaction and nothing is left loose beside it. Both were a
    // sequence of separate calls issued from a `rememberCoroutineScope()`, so a back-tap between two of
    // them committed the first and dropped the rest, and neither partial state has anything that repairs
    // it (the pull re-derives a removed person's debt from her still-live claims rather than healing it).

    /** Delegates everything, and counts the loose per-table writes the transaction is meant to replace. */
    private class CountingExpenseDao(
        private val real: app.splitevenly.data.db.dao.ExpenseDao,
    ) : app.splitevenly.data.db.dao.ExpenseDao by real {
        var looseUpserts = 0

        override suspend fun upsert(expense: app.splitevenly.data.db.entity.ExpenseEntity) {
            looseUpserts++
            real.upsert(expense)
        }
    }

    private class CountingBillParticipantDao(
        private val real: app.splitevenly.data.db.dao.BillParticipantDao,
    ) : app.splitevenly.data.db.dao.BillParticipantDao by real {
        var looseSoftDeletes = 0

        override suspend fun softDeleteByIds(
            ids: List<String>,
            ts: Long,
        ) {
            looseSoftDeletes++
            real.softDeleteByIds(ids, ts)
        }
    }

    private fun repoCounting(
        expenses: CountingExpenseDao = CountingExpenseDao(db.expenseDao()),
        participants: CountingBillParticipantDao = CountingBillParticipantDao(db.billParticipantDao()),
    ) = BillRepositoryImpl(
        expenses,
        db.expenseItemDao(),
        db.itemClaimDao(),
        db.itemShareDao(),
        participants,
        db.shareDao(),
        db.billWriteDao(),
        clockAt("2026-06-28"),
    )

    @Test
    fun createBill_writesTheExpenseItsLinesAndItsRosterInOneTransaction() =
        runTest {
            val expenses = CountingExpenseDao(db.expenseDao())
            val counting = repoCounting(expenses = expenses)

            val bill =
                (
                    counting.createBill(
                        NewBill(
                            groupId = group,
                            title = "Dinner at Tavolo",
                            currency = "USD",
                            expenseDate = "2026-06-28",
                            payerUserId = me,
                            createdBy = me,
                            items = listOf(NewBillItem("Margherita pizza", quantity = 2, lineTotalSubunits = 3600)),
                            participantUserIds = listOf(me, bob),
                        ),
                    ) as AppResult.Ok
                ).value

            assertEquals(
                0,
                expenses.looseUpserts,
                "an expense written outside the transaction can outlive a cancelled scope with no lines under it",
            )
            assertEquals(3600L, db.expenseDao().getById(bill.value)!!.amountSubunits)
            assertEquals(1, db.expenseItemDao().getByExpense(bill.value).size)
            assertEquals(2, db.billParticipantDao().getByExpense(bill.value).size)
        }

    @Test
    fun editBill_takesAPersonAndTheirClaimsOffInOneTransaction() =
        runTest {
            val participants = CountingBillParticipantDao(db.billParticipantDao())
            val counting = repoCounting(participants = participants)
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            val salad = itemId(bill, "Caesar salad")
            counting.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob, cara)))
            counting.setClaim(bill, salad, cara, 1)

            counting.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob)))

            assertEquals(
                0,
                participants.looseSoftDeletes,
                "a roster tombstone written on its own leaves Cara off the bill and still paying for the salad",
            )
            assertNull(db.itemClaimDao().getActiveClaim(salad, cara.value))
            assertEquals(emptyMap(), owed(bill).filterValues { it != 0L })
        }

    /** Off then back on: the deterministic row id means the tombstone must be revived, not re-inserted. */
    @Test
    fun puttingSomeoneBackOnTheBill_restoresThem() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            val salad = itemId(bill, "Caesar salad")
            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob, cara)))
            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob)))
            bills.editBill(bill, editWith(pizza, salad, participants = listOf(me, bob, cara)))

            val active = db.billParticipantDao().getByExpense(bill.value).filter { it.deletedAt == null }
            assertEquals(setOf("a", "b", "c"), active.mapTo(HashSet()) { it.userId }, "Cara is back on the bill")
            assertEquals(3, active.size, "revived, not duplicated")
        }

    // --- a bill's total must be positive (#21) ---------------------------------------------------
    // `validate` used to take only (title, lines), so `extras` never reached it and an oversized
    // discount produced a zero or negative expense — an always-positive entity invariant broken and
    // then hidden by the `> 0` outstanding filters, so the bill just vanished from the balances.

    @Test
    fun createBill_rejectsADiscountBiggerThanTheBill() =
        runTest {
            val result =
                bills.createBill(
                    NewBill(
                        groupId = group,
                        title = "Dinner at Tavolo",
                        currency = "USD",
                        expenseDate = "2026-06-28",
                        payerUserId = me,
                        createdBy = me,
                        items = listOf(NewBillItem("Margherita pizza", quantity = 1, lineTotalSubunits = 1800)),
                        extras = BillExtrasInput(discountSubunits = 2000),
                    ),
                )

            val err = assertIs<AppResult.Err>(result, "a $18 bill with a $20 discount must not be recorded")
            val validation = assertIs<AppError.Validation>(err.error)
            assertEquals(AppError.Validation.Reason.OutOfRange, validation.fieldErrors["discount"])
            assertTrue(db.expenseDao().allForSync().isEmpty(), "nothing was written")
        }

    @Test
    fun createBill_rejectsADiscountThatZeroesTheBill() =
        runTest {
            val result =
                bills.createBill(
                    NewBill(
                        groupId = group,
                        title = "Dinner at Tavolo",
                        currency = "USD",
                        expenseDate = "2026-06-28",
                        payerUserId = me,
                        createdBy = me,
                        items = listOf(NewBillItem("Margherita pizza", quantity = 1, lineTotalSubunits = 1800)),
                        extras = BillExtrasInput(discountSubunits = 1800),
                    ),
                )

            assertIs<AppResult.Err>(result, "a bill totalling zero is not an expense")
        }

    // …and the total staying positive is not enough (findings-domain.md D1). A discount rides
    // proportional to the ITEM subtotal, so a $30 voucher on $26 of food hands the only claimant a
    // −$1.00 tab even though the $9 tip keeps the bill total at $5.00 — and `ShareDao`'s `> 0` filters
    // then erase the credit, showing the payer owed $6.00 on a $5.00 bill.
    @Test
    fun createBill_rejectsADiscountBiggerThanTheItems_evenWhenATipKeepsTheTotalPositive() =
        runTest {
            val result =
                bills.createBill(
                    NewBill(
                        groupId = group,
                        title = "Dinner at Tavolo",
                        currency = "USD",
                        expenseDate = "2026-06-28",
                        payerUserId = me,
                        createdBy = me,
                        items = listOf(NewBillItem("Dinner", quantity = 1, lineTotalSubunits = 2600)),
                        extras = BillExtrasInput(tipSubunits = 900, discountSubunits = 3000),
                    ),
                )

            val err = assertIs<AppResult.Err>(result, "a $30 voucher on $26 of food must not be recorded")
            val validation = assertIs<AppError.Validation>(err.error)
            assertEquals(AppError.Validation.Reason.OutOfRange, validation.fieldErrors["discount"])
            assertTrue(db.expenseDao().allForSync().isEmpty(), "nothing was written")
        }

    @Test
    fun createBill_allowsADiscountThatLeavesSomethingOwed() =
        runTest {
            val bill = newBill(BillExtrasInput(discountSubunits = 4799)) // 4800 items - 4799 = 1 subunit

            assertEquals(1L, db.expenseDao().getById(bill.value)!!.amountSubunits)
        }

    @Test
    fun editBill_rejectsADiscountBiggerThanTheBill() =
        runTest {
            val bill = newBill()
            val pizza = itemId(bill, "Margherita pizza")
            val salad = itemId(bill, "Caesar salad")

            val result =
                bills.editBill(
                    bill,
                    editWith(pizza, salad, participants = listOf(me, bob))
                        .copy(extras = BillExtrasInput(discountSubunits = 5000)),
                )

            assertIs<AppResult.Err>(result, "editing a bill into a negative total must fail too")
            assertEquals(4800L, db.expenseDao().getById(bill.value)!!.amountSubunits, "the bill is untouched")
        }

    /** The bill's own items and extras, unchanged — only the roster varies across the participant tests. */
    private fun editWith(
        pizza: String,
        salad: String,
        participants: List<UserId>,
    ) = EditBill(
        title = "Dinner at Tavolo",
        expenseDate = "2026-06-28",
        payerUserId = me,
        items =
            listOf(
                EditBillItem(id = pizza, label = "Margherita pizza", quantity = 2, lineTotalSubunits = 3600),
                EditBillItem(id = salad, label = "Caesar salad", quantity = 1, lineTotalSubunits = 1200),
            ),
        extras = BillExtrasInput(),
        participantUserIds = participants,
        editedBy = me,
    )
}
