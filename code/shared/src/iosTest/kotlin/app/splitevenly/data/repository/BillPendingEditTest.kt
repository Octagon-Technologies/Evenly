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
 * The payer's half of a web guest's menu change (WEB_CLAIM_SPEC.md §2.7, §3.9.1).
 *
 * A guest's edit **applies** — server-side, in `apply_web_bill_edit`, because the edge function has a
 * service key and no `auth.uid()` and therefore cannot go through `merge_expense`. What reaches the app
 * is the applied change plus a log row, and what the app can do about it is **undo**.
 *
 * These pin the four things that would silently corrupt a bill if they drifted: that an undo actually
 * restores, that it restores **exactly** (the line total, not a rounded per-unit rebuilt into one), that
 * the causal `split_version` advances so `merge_expense` cannot drop the undo, and that undoing twice
 * changes nothing.
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

    private suspend fun lineOf(expenseId: ExpenseId, itemId: String) =
        db.expenseItemDao().allForSync().first { it.id == itemId && it.expenseId == expenseId.value }

    /**
     * The log row a guest's edit leaves behind, as it arrives from the server: already `APPLIED`, with
     * `previous_*` captured at apply time and `item_id` always filled in, including for an ADD.
     *
     * These tests write the row **and** the change it describes, because that is what the app pulls. The
     * app never applies one itself.
     */
    private suspend fun applied(
        expenseId: ExpenseId,
        kind: String,
        itemId: String,
        label: String? = null,
        quantity: Int? = null,
        unitPrice: Long? = null,
        previousLabel: String? = null,
        previousQuantity: Int? = null,
        previousUnitPrice: Long? = null,
        previousLineTotal: Long? = null,
        at: Long = 1L,
    ): String {
        val id = "edit-$kind-$itemId"
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
                previousLineTotalSubunits = previousLineTotal,
                proposedBy = guest.value,
                proposedAt = at,
                decidedAt = at,
                decidedBy = guest.value,
                decision = "APPLIED",
                createdAt = at,
                updatedAt = at,
            ),
        )
        return id
    }

    @Test
    fun undoingAnAdd_takesTheLineOffTheBillAndAdvancesTheSplitVersion() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        // The guest's ADD already landed: the line is on the bill and the log says so.
        val added = "added-line"
        db.expenseItemDao().upsert(
            db.expenseItemDao().getByExpense(bill.value).first().copy(
                id = added, label = "Mango sticky rice", quantity = 1,
                unitPriceSubunits = 900, lineTotalSubunits = 900, sortOrder = 9,
            ),
        )
        val edit = applied(bill, "ADD", added, label = "Mango sticky rice", quantity = 1, unitPrice = 900)
        val before = splitVersionOf(bill)

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        assertNotNull(lineOf(bill, added).deletedAt, "an undone ADD is soft-deleted, never hard-deleted")
        assertEquals(4050L, amountOf(bill))
        // Zone 2: without the bump, merge_expense treats the undo as causally stale and drops it.
        assertEquals(before + 1, splitVersionOf(bill))
        assertEquals("UNDONE", db.pendingItemEditDao().getById(edit)!!.decision)
    }

    @Test
    fun undoingAnAdd_takesTheClaimsOnItTooRatherThanBillingForALineThatIsGone() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val added = "added-line"
        db.expenseItemDao().upsert(
            db.expenseItemDao().getByExpense(bill.value).first().copy(
                id = added, label = "Mango sticky rice", quantity = 1,
                unitPriceSubunits = 900, lineTotalSubunits = 900, sortOrder = 9,
            ),
        )
        bills.setClaim(bill, added, bob, 1)
        assertEquals(900L, owed(bill)["b"])
        val edit = applied(bill, "ADD", added, label = "Mango sticky rice", quantity = 1, unitPrice = 900)

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        assertNull(db.itemClaimDao().getActiveClaim(added, bob.value), "spec E22")
        assertEquals(emptyMap(), owed(bill))
    }

    @Test
    fun undoingAReprice_restoresTheLineTotalExactlyRatherThanRebuildingItFromPerUnit() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")
        bills.setClaim(bill, pizza, bob, 1)
        bills.setClaim(bill, pizza, cara, 1)

        // The receipt says 2 for $36.01, which does not divide: per unit rounds to 1801, and 1801 x 2 is
        // 3602. Rebuilding the line from the rounded per-unit would hand back a cent that was never on
        // the bill, which is the one thing this layer may never do (`domain/AGENTS.md`).
        val original = 3601L
        db.expenseItemDao().upsert(lineOf(bill, pizza).copy(lineTotalSubunits = original, unitPriceSubunits = 1801))
        // Then the guest repriced it to $20.00 each.
        db.expenseItemDao().upsert(lineOf(bill, pizza).copy(lineTotalSubunits = 4000, unitPriceSubunits = 2000))
        val edit = applied(
            bill, "REPRICE", pizza, unitPrice = 2000,
            previousQuantity = 2, previousUnitPrice = 1801, previousLineTotal = original,
        )

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        assertEquals(original, lineOf(bill, pizza).lineTotalSubunits, "not 3602")
        assertEquals(mapOf("b" to 1801L, "c" to 1800L), owed(bill), "an undo re-derives the claims on the line")
    }

    @Test
    fun undoingARequantity_restoresBothTheCountAndTheLineTotal() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")
        // The guest said "there were 3, not 2", so the line is 3 x $18.00.
        db.expenseItemDao().upsert(lineOf(bill, pizza).copy(quantity = 3, lineTotalSubunits = 5400))
        val edit = applied(
            bill, "REQUANTITY", pizza, quantity = 3,
            previousQuantity = 2, previousUnitPrice = 1800, previousLineTotal = 3600,
        )

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        val line = lineOf(bill, pizza)
        assertEquals(2, line.quantity)
        assertEquals(3600L, line.lineTotalSubunits)
        assertEquals(4050L, amountOf(bill))
    }

    @Test
    fun undoingARelabel_putsTheOldNameBackAndMovesNoMoney() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        db.expenseItemDao().upsert(lineOf(bill, juice).copy(label = "Fresh orange juice"))
        val edit = applied(bill, "RELABEL", juice, label = "Fresh orange juice", previousLabel = "Juice")

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        assertEquals("Juice", lineOf(bill, juice).label)
        assertEquals(4050L, amountOf(bill))
    }

    @Test
    fun undoingARemove_putsTheLineBackOnTheBill() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        db.expenseItemDao().softDeleteByIds(listOf(juice), 99L)
        val edit = applied(
            bill, "REMOVE", juice,
            previousLabel = "Juice", previousQuantity = 1, previousUnitPrice = 450, previousLineTotal = 450,
        )

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        val line = lineOf(bill, juice)
        assertNull(line.deletedAt)
        assertEquals(450L, line.lineTotalSubunits)
        assertEquals(4050L, amountOf(bill))
    }

    @Test
    fun undoingTwice_changesNothingTheSecondTime() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val pizza = itemId(bill, "Margherita pizza")
        db.expenseItemDao().upsert(lineOf(bill, pizza).copy(lineTotalSubunits = 4000))
        val edit = applied(
            bill, "REPRICE", pizza, unitPrice = 2000,
            previousQuantity = 2, previousUnitPrice = 1800, previousLineTotal = 3600,
        )

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)
        val afterFirst = splitVersionOf(bill)
        // Two people tapping Undo at once, or one person tapping twice on a slow card. The conditional
        // UPDATE is what makes the second a no-op instead of a second Zone-2 edit re-running the restore.
        assertTrue(bills.undoPendingEdit(edit, undoneBy = bob) is AppResult.Ok)

        assertEquals(3600L, lineOf(bill, pizza).lineTotalSubunits)
        assertEquals(4050L, amountOf(bill))
        assertEquals(afterFirst, splitVersionOf(bill), "the second undo is not a money edit")
        assertEquals(me.value, db.pendingItemEditDao().getById(edit)!!.decidedBy, "first undo wins")
    }

    @Test
    fun undoingAChangeWhoseLineWentAway_stampsItWithoutStrandingTheCard() = runTest {
        val bills = repo()
        val bill = newBill(bills)
        val juice = itemId(bill, "Juice")
        val edit = applied(
            bill, "REPRICE", juice, unitPrice = 600,
            previousQuantity = 1, previousUnitPrice = 450, previousLineTotal = 450,
        )
        db.expenseItemDao().softDeleteByIds(listOf(juice), 99L)

        assertTrue(bills.undoPendingEdit(edit, undoneBy = me) is AppResult.Ok)

        // Refusing would leave the card on the payer's screen forever with nothing they could do about it.
        assertEquals("UNDONE", db.pendingItemEditDao().getById(edit)!!.decision)
        assertNotNull(lineOf(bill, juice).deletedAt, "and it does not resurrect a line the payer removed")
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
