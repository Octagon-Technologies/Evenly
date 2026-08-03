package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.PendingItemEditEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The payer's half of a web guest's menu edit (WEB_CLAIM_SPEC.md §2.7, §3.9.1, build-order step 6).
 *
 * A proposal never touches `expense_items` until the payer approves it, which is the whole reason the
 * table exists: editing a line moves the bill total and therefore everyone's money. These pin the four
 * things that would silently corrupt a bill if they drifted — that an approval actually applies, that a
 * rejection actually doesn't, that the causal `split_version` advances so `merge_expense` can't drop the
 * approval, and that a second tap can't apply the same change twice.
 *
 * [BillRepositoryImpl.assignRemainder] is covered here too, because it is the other half of the same
 * screen pair: what the payer does about the people who never claimed at all (§3.9.2, E17).
 */
class BillPendingEditTest {

    private lateinit var db: EvenlyDatabase

    private val group = GroupId("g1")
    private val me = UserId("a")
    private val bob = UserId("b")
    private val cara = UserId("c")
    private val guest = UserId("guest")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun repo() = BillRepositoryImpl(
        db.expenseDao(), db.expenseItemDao(), db.itemClaimDao(), db.itemShareDao(),
        db.billParticipantDao(), db.shareDao(), clockAt("2026-08-03"),
        pendingItemEditDao = db.pendingItemEditDao(),
    )

    /** One 2-unit line at $36.00, plus a 1-unit juice at $4.50. Bill total $40.50, no extras. */
    private suspend fun newBill(bills: BillRepositoryImpl): ExpenseId =
        (bills.createBill(
            NewBill(
                groupId = group,
                title = "Ramen night",
                currency = "USD",
                expenseDate = "2026-08-03",
                payerUserId = me,
                createdBy = me,
                items = listOf(
                    NewBillItem("Margherita pizza", quantity = 2, lineTotalSubunits = 3600),
                    NewBillItem("Juice", quantity = 1, lineTotalSubunits = 450),
                ),
                participantUserIds = listOf(me, bob, cara),
            ),
        ) as AppResult.Ok).value

    private suspend fun itemId(expenseId: ExpenseId, label: String): String =
        db.expenseItemDao().getByExpense(expenseId.value).first { it.label == label }.id

    private suspend fun owed(expenseId: ExpenseId): Map<String, Long> =
        db.shareDao().getByExpense(expenseId.value)
            .filter { it.deletedAt == null && it.shareOwedSubunits != 0L }
            .associate { it.userId to it.shareOwedSubunits }

    private suspend fun amountOf(expenseId: ExpenseId): Long =
        db.expenseDao().getById(expenseId.value)!!.amountSubunits

    private suspend fun splitVersionOf(expenseId: ExpenseId): Long =
        db.expenseDao().getById(expenseId.value)!!.splitVersion

    private suspend fun propose(
        expenseId: ExpenseId,
        kind: String,
        itemId: String? = null,
        label: String? = null,
        quantity: Int? = null,
        unitPrice: Long? = null,
        previousLabel: String? = null,
        previousQuantity: Int? = null,
        previousUnitPrice: Long? = null,
    ): String {
        val id = "edit-$kind-${itemId ?: "new"}"
        db.pendingItemEditDao().upsert(
            PendingItemEditEntity(
                id = id,
                expenseId = expenseId.value,
                groupId = group.value,
                itemId = itemId,
                kind = kind,
                proposedLabel = label,
                proposedQuantity = quantity,
                proposedUnitPriceSubunits = unitPrice,
                previousLabel = previousLabel,
                previousQuantity = previousQuantity,
                previousUnitPriceSubunits = previousUnitPrice,
                proposedBy = guest.value,
                proposedAt = 1L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        return id
    }

    @Test
    fun approvingAnAdd_putsTheLineOnTheBillAndAdvancesTheSplitVersion() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val before = splitVersionOf(bill)
        val edit = propose(bill, "ADD", label = "Mango sticky rice", quantity = 1, unitPrice = 900)

        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        val line = db.expenseItemDao().getByExpense(bill.value).firstOrNull { it.label == "Mango sticky rice" }
        assertNotNull(line, "an approved ADD is the only thing that creates the line")
        assertEquals(900L, line.lineTotalSubunits)
        assertEquals(4050L + 900L, amountOf(bill))
        // Zone 2: without the bump, merge_expense treats the approval as causally stale and drops it.
        assertEquals(before + 1, splitVersionOf(bill))
        assertEquals("APPROVED", db.pendingItemEditDao().getById(edit)!!.decision)
    }

    @Test
    fun rejectingAnAdd_leavesTheBillExactlyAsItWas() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val before = splitVersionOf(bill)
        val edit = propose(bill, "ADD", label = "Mango sticky rice", quantity = 1, unitPrice = 900)

        assertTrue(bills.decidePendingEdit(edit, approve = false, decidedBy = me) is AppResult.Ok)

        assertEquals(2, db.expenseItemDao().getByExpense(bill.value).count { it.deletedAt == null })
        assertEquals(4050L, amountOf(bill))
        assertEquals(before, splitVersionOf(bill), "a rejection is not a money edit")
        assertEquals("REJECTED", db.pendingItemEditDao().getById(edit)!!.decision)
    }

    @Test
    fun approvingAReprice_movesTheMoneyOfEveryoneAlreadyOnTheLine() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")
        bills.setClaim(bill, pizza, bob, 1)
        bills.setClaim(bill, pizza, cara, 1)
        assertEquals(mapOf("b" to 1800L, "c" to 1800L), owed(bill))

        // $18.00 each becomes $20.00 each: the line total is the truth, so 2 × 2000.
        val edit = propose(bill, "REPRICE", itemId = pizza, unitPrice = 2000, previousUnitPrice = 1800, previousQuantity = 2)
        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        assertEquals(4000L, db.expenseItemDao().getByExpense(bill.value).first { it.id == pizza }.lineTotalSubunits)
        assertEquals(mapOf("b" to 2000L, "c" to 2000L), owed(bill), "an approved reprice re-derives the claims already on the line")
        assertEquals(4000L + 450L, amountOf(bill))
    }

    @Test
    fun approvingARequantity_holdsThePerUnitPriceAndRescalesTheLine() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")

        // "There were 3, not 2." Per unit stays $18.00, so the line becomes $54.00.
        val edit = propose(bill, "REQUANTITY", itemId = pizza, quantity = 3, previousQuantity = 2, previousUnitPrice = 1800)
        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        val line = db.expenseItemDao().getByExpense(bill.value).first { it.id == pizza }
        assertEquals(3, line.quantity)
        assertEquals(5400L, line.lineTotalSubunits)
    }

    @Test
    fun approvingARemove_takesTheLineAndEveryClaimOnItWithIt() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        bills.setClaim(bill, juice, bob, 1)
        assertEquals(mapOf("b" to 450L), owed(bill))

        val edit = propose(bill, "REMOVE", itemId = juice, previousQuantity = 1, previousUnitPrice = 450)
        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        // getByExpense excludes tombstones, so read the sync view to prove it was soft-deleted rather
        // than hard-deleted (Rule 1: the removal has to reach the server or it resurrects on the pull).
        assertNotNull(db.expenseItemDao().allForSync().first { it.id == juice }.deletedAt)
        assertNull(db.itemClaimDao().getActiveClaim(juice, bob.value), "a claim on a line that's gone must not keep billing")
        assertEquals(emptyMap(), owed(bill))
        assertEquals(3600L, amountOf(bill))
    }

    @Test
    fun decidingTwice_appliesTheChangeOnlyOnce() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val edit = propose(bill, "ADD", label = "Mango sticky rice", quantity = 1, unitPrice = 900)

        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)
        // A second tap on a slow card. Idempotent rather than an error: the payer did nothing wrong, and
        // applying it twice would put a second $9.00 line on somebody's dinner.
        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        assertEquals(1, db.expenseItemDao().getByExpense(bill.value).count { it.label == "Mango sticky rice" })
        assertEquals(4950L, amountOf(bill))
    }

    @Test
    fun approvingAnEditWhoseLineWentAway_stampsTheVerdictWithoutStrandingTheCard() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        val edit = propose(bill, "REPRICE", itemId = juice, unitPrice = 600, previousUnitPrice = 450, previousQuantity = 1)
        db.expenseItemDao().softDeleteByIds(listOf(juice), 99L)

        assertTrue(bills.decidePendingEdit(edit, approve = true, decidedBy = me) is AppResult.Ok)

        // Refusing would leave the card in the payer's inbox forever with nothing they could do about it.
        assertEquals("APPROVED", db.pendingItemEditDao().getById(edit)!!.decision)
    }

    // ── The remainder (§3.9.2, E17) ──────────────────────────────────────────────────────────────

    @Test
    fun assignRemainder_givesOnlyTheLeftoverUnitsToTheNamedPeople() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")
        // Bob took one of the two slices; the other one and the whole juice are still nobody's.
        bills.setClaim(bill, pizza, bob, 1)

        assertTrue(bills.assignRemainder(bill, listOf(cara), addedBy = me) is AppResult.Ok)

        // Bob keeps his one slice at $18.00; Cara picks up the other slice plus the $4.50 juice.
        assertEquals(mapOf("b" to 1800L, "c" to 1800L + 450L), owed(bill))
    }

    @Test
    fun assignRemainder_splitsTheLeftoverEvenlyWhenNamedMoreThanOnePerson() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        val pizza = itemId(bill, "Margherita pizza")
        bills.setClaim(bill, pizza, me, 2) // the pizza is fully spoken for

        assertTrue(bills.assignRemainder(bill, listOf(bob, cara), addedBy = me) is AppResult.Ok)

        // Only the juice was left, and it splits: $4.50 over two is 225/225, penny-exact.
        assertEquals(mapOf("a" to 3600L, "b" to 225L, "c" to 225L), owed(bill))
        val onPizza = db.itemShareDao().getByExpense(bill.value)
            .filter { it.itemId == pizza && it.deletedAt == null }
        assertTrue(onPizza.isEmpty(), "a fully claimed line has no remainder and must not be touched")
        assertEquals(
            2,
            db.itemShareDao().getByExpense(bill.value).count { it.itemId == juice && it.deletedAt == null },
        )
    }

    @Test
    fun assignRemainder_isIdempotent() = runTest {
        val bills = repo()
        val bill = newBill(bills)

        assertTrue(bills.assignRemainder(bill, listOf(bob), addedBy = me) is AppResult.Ok)
        val once = owed(bill)
        // Deterministic portion ids mean a second run lands on the same rows instead of stacking a
        // second slice on every line and billing the whole bill twice.
        assertTrue(bills.assignRemainder(bill, listOf(bob), addedBy = me) is AppResult.Ok)

        assertEquals(once, owed(bill))
        assertEquals(mapOf("b" to 4050L), once)
    }

    @Test
    fun assignRemainder_withNobodyNamed_isRejectedRatherThanSilentlyDoingNothing() = runTest {
        val bills = repo()
        val bill = newBill(bills)

        assertTrue(bills.assignRemainder(bill, emptyList(), addedBy = me) is AppResult.Err)
    }
}
